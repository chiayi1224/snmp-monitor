# Design

## Context

動機見 proposal.md。本設計建立在 `add-device-oid-discovery` 之上：

- `SnmpSession`（前一個 change 新增）提供依設備版本的 `get()`／`walk()`。
- 現行 `SnmpPoller.pollDevice()`：GET `poll_oids` 中所有啟用的 SCALAR OID，再由 `PollParser` 只取 `CPU_IDLE` 與四個 `MEM_*`，其餘丟棄；磁碟用 `TableUtils` walk hrStorage 並篩選固定磁碟。
- `ThresholdEngine` 只有 `evaluateCpu／Memory／Disks`，以 `store.loadThresholdConfig(metricName)` 取閾值；`threshold_settings.metric_name` 為 UNIQUE，目前只有 `cpu`／`memory`／`disk` 三筆，且沒有方向欄位（`ThresholdConfig` 本身已支援高／低兩種方向）。
- 狀態查詢 `MetricRepository.queryLatestStatus()` 只讀三張固定指標表。

## Goals / Non-Goals

**Goals:**
- 設定新型號設備的監控，不需要改程式或重新部署。
- 以內建 Net-SNMP 範本保證既有 Linux 設備升級後數值完全一致（以既有 `PollParser` 測試案例作為回歸基準）。
- 計算邏輯與 SNMP 通訊分離，可不連設備就單元測試。

**Non-Goals:**
- 不提供任意運算式語言（安全性與可維護性考量，見 D2）。
- 不做跨週期的差值計算（例如介面流量需要兩次 counter 相減）；介面流量留待後續。
- 不提供歷史圖表；自訂指標只保證儲存與最新值查詢。
- 不做範本匯入／匯出。

## Decisions

### D1. 範本與指標的資料模型
- `metric_profiles(id, name UNIQUE, description, match_prefix, builtin, created, updated)`。
- `profile_metrics(id, profile_id, metric_key, kind, params_json, unit, enabled)`，`UNIQUE(profile_id, metric_key)`。`params_json` 依計算類型存所需 OID 與參數（例如 `{"used":"…","total":"…"}`、`{"column":"…"}`、`{"table":"…","typeFilter":"1.3.6.1.2.1.25.2.1.4"}`、`{"oid":"…","scale":0.1}`）。
- **為何用 JSON 參數而非固定欄位**：各計算類型需要的 OID 數量不同，固定欄位會有大量空欄位；驗證集中在每種類型的 `MetricCalculator.validate()`。
- `devices` 新增 `profile_id`（NULL 代表自動）與 `sys_object_id`。

### D2. 固定的計算類型，而非運算式
每種類型實作一個 `MetricCalculator`（介面：`requiredOids(params)`、`validate(params)`、`compute(params, SnmpResult) → List<MetricValue>`）。`STORAGE_TABLE` 會回傳多個值（每個掛載點一個，對應現有磁碟邏輯）；其他類型回傳一個值。
- **替代方案：運算式（例如 `100 - $1`）**：彈性較高，但需要撰寫並維護解析器、處理除以零與注入問題，而目前觀察到的廠牌 MIB 都可用這七種類型涵蓋。之後若有需要再新增類型即可，不影響既有範本。

### D3. 內建範本以程式碼定義，啟動時同步
內建範本定義在 `BuiltinProfiles`（程式常數），啟動時以名稱比對寫入或更新資料表（`builtin=1`）；API 拒絕修改 `builtin=1` 的範本（403）。
- **為何不只寫在資料庫**：升級時能修正內建範本的錯誤，而不覆蓋使用者複製出來的版本。
- Net-SNMP 範本：`cpu`=`HUNDRED_MINUS(ssCpuIdle)`、`memory`=`UCD_MEMORY`、`disk`=`STORAGE_TABLE(fixedDisk)`，與現行 `PollParser` 和磁碟 walk 一一對應，現有 `PollParserTest` 改寫為對 `HUNDRED_MINUS`／`UCD_MEMORY` 計算器的測試並保留全部案例。
- HOST-RESOURCES：`cpu`=`TABLE_AVERAGE(hrProcessorLoad)`、`memory`=`STORAGE_TABLE(hrStorageRam)` 取第一列、`disk`=`STORAGE_TABLE(fixedDisk)`。
- Cisco IOS：`cpu`=`TABLE_AVERAGE(cpmCPUTotal5minRev)`、`memory`=`USED_FREE_PERCENT(ciscoMemoryPoolUsed.1, ciscoMemoryPoolFree.1)`（processor pool）。

### D4. 範本比對與快取
`ProfileResolver` 於每個輪詢週期開始時載入所有範本（數量少，直接整份讀取），依 spec 的順序決定每台設備的範本。sysObjectID 在輪詢時與指標一起 GET（`1.3.6.1.2.1.1.2.0`），首次輪詢的設備先 GET sysObjectID 再決定範本。

### D5. Poller 執行流程
每台設備：（Ping）→ 收集範本中所有 SCALAR 類型所需 OID 一次 GET（再加 sysObjectID）→ 表格類型各自 walk（同一表格只 walk 一次）→ 交給各計算器 → 依指標名稱分派：`cpu`／`memory` 寫既有表並走既有閾值；`disk` 每個掛載點寫 `disk_metrics` 並以 `disk:<mount>` 評估；其他寫 `custom_metrics` 並以 `ThresholdEngine.evaluate(device, key, value)` 評估。設備狀態判定沿用現行規則（GET 完全無回應才是 `SNMP_ERROR`）。

### D6. 自訂指標閾值
`threshold_settings` 新增 `direction TEXT DEFAULT 'HIGH'`；`/api/admin/thresholds` 新增 POST（建立自訂指標閾值）與 DELETE（只能刪除自訂指標，`cpu`／`memory`／`disk` 回 403）。`loadThresholdConfig()` 對沒有設定的自訂指標回傳 null，`ThresholdEngine` 遇到 null 即略過評估。

### D7. 自訂指標儲存
`custom_metrics(id, ts, device_name, metric_key, value, unit)`，索引 `(device_name, metric_key, ts)` 與 `(ts)`；最新值查詢以 `MAX(ts)` 子查詢取得。納入既有的資料保存期限：`DataRetentionJob` 依 `retention_metrics_days` 與 cpu／mem／disk 一起清除（本提案撰寫後才加入保存期限功能，此處同步）。

### D8. Poll OID 移除與升級轉換
`SchemaManager` 升級步驟：若 `poll_oids` 存在且有任何 `enabled=0` 的 cpu／memory／disk 群組 OID，且尚未轉換過（以 `app_settings` 的 `poll_oids_migrated` 標記），則建立「Net-SNMP（UCD-SNMP）（升級轉換）」範本並停用對應指標，指定給 `sys_object_id` 尚未知但目前 `status != 'UNKNOWN'` 的設備（升級前曾成功輪詢、即目前走 Net-SNMP 邏輯的設備）。移除 `PollOidServlet` 註冊與 `test.sh` 第 11 段；`poll_oids` 資料表保留以利回滾。

### D9. 前端
- 「監控範本」分頁：範本清單 → 範本編輯對話框（指標列表，依計算類型動態顯示 OID 欄位）→ 預覽（選設備後呼叫 `POST /api/admin/profiles/preview`，body 為範本內容與設備名稱）。
- 「OID 探索」OID 表加「加入範本」；設備對話框加範本選單；狀態頁每台設備卡片下方列出自訂指標。

## Risks / Trade-offs

- [Net-SNMP 數值回歸] → 內建範本對應現有公式，並以現有 `PollParserTest` 案例與新增的 Poller 端對端測試（本機 SNMP4J agent 提供 UCD／hrStorage 資料）比對升級前後數值。
- [範本設定錯誤導致大量無效查詢] → 預覽功能；單一指標失敗只記 log；OID 不存在時每台設備每個指標每小時最多記一次警告，避免 log 洪水。
- [sysObjectID 改變造成範本切換、閾值狀態不連續] → 閾值狀態以「設備＋指標名稱」為鍵，範本切換不影響同名指標的狀態。
- [Poll OID 移除影響外部整合] → 標示 BREAKING；該 API 僅在 `test.sh` 中使用，網頁沒有入口。
- [每週期多一次 sysObjectID 查詢] → 併入同一個 GET，不增加封包數。

## Migration Plan

1. 先完成並部署 `add-device-oid-discovery`。
2. 部署本 change：`SchemaManager` 建立新表、補欄位、同步內建範本、執行 D8 轉換；第一個輪詢週期取得各設備 sysObjectID 並套用範本。
3. 驗證：比對升級前後同一台 Linux 主機的 CPU／記憶體／磁碟數值；檢查非 Net-SNMP 設備開始出現數值。
4. 回滾：換回舊 JAR 即可；舊版不讀取新表與新欄位，`poll_oids` 資料仍在。
