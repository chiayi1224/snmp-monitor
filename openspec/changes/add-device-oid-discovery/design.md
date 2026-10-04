# Design

## Context

動機見 proposal.md。目前與本設計相關的現況：

- `SnmpPoller` 用 `new Snmp(new DefaultUdpTransportMapping())` 建立連線，`buildTarget()` 寫死 `CommunityTarget` + v2c。這個 `Snmp` 的 MPv3 用的是 SNMP4J 全域 `SecurityProtocols`，而全域實例預設沒有載入任何協定（`TrapReceiver` 已因此修正過一次），所以就算加上 v3 設定也無法運作。
- `devices` 資料表只有 `community`；新欄位要用 `SchemaManager.MIGRATIONS` 的 `ALTER TABLE ... ADD COLUMN`（失敗即忽略）方式補上。
- Trap 對應、未定義 Trap、未登錄來源都已有資料表與 API（`TrapMappingServlet`、`UnknownTrapServlet`），但 `index.html` 沒有對應分頁。
- 前端是單一 `index.html`（無建置工具），以 `showTab()` 切換 `<section>`，用 `esc()` 做 HTML 跳脫。
- 沒有 MIB 檔案，也沒有 MIB 解析器；系統只認得 `OidConstants` 裡的數字 OID。

## Goals / Non-Goals

**Goals:**
- Poller 與掃描共用同一套「依設備設定建立 SNMP 連線」的元件，v1／v2c／v3 行為一致。
- 掃描不阻塞 Web 請求，也不影響定期輪詢的時間點。
- 掃描結果的資料結構足以讓後續 change `add-device-metric-profiles` 直接從結果挑選 OID 建立範本。

**Non-Goals:**
- 不載入、不解析 MIB 檔案；OID 以數字顯示，只有內建探測項目附上名稱。
- 不提供網段掃描（自動發現網路上有哪些設備）；掃描對象限於已登錄的設備。
- 不把掃描到的 OID 套用成監控指標（屬於後續 change）。
- 不加密 SQLite 內的密碼（與現有 community、Trap v3 使用者一致，另案處理）。

## Decisions

### D1. 共用 `SnmpSession` 元件取代 Poller 內的連線程式碼
新增 `snmp` 套件的 `SnmpSession`：持有一個 `Snmp` 實例、自己的 `SecurityProtocols`（`maxCompatibility`）與 `USM`，提供 `get()`、`walk()`（v2c／v3 用 GETBULK，v1 用 GETNEXT）與 `targetFor(DeviceConfig)`。Poller 與掃描服務各自持有一個 `SnmpSession`。
- **為何不直接改 `SnmpPoller`**：掃描也需要相同邏輯，且 v3 的 USM 管理需要集中處理。
- **為何各自持有一個實例**：掃描的大量 GETBULK 不應與輪詢搶同一個 UDP socket 的回應佇列；兩個實例成本很低。

### D2. v3 使用者依 engine ID 本地化
USM 使用者表以（engineID, username）為鍵。若以未本地化方式加入使用者，兩台設備使用相同 username、不同密碼時會互相覆蓋。因此 `SnmpSession` 對 v3 設備先以 `discoverAuthoritativeEngineID()` 取得設備的 engine ID（快取於記憶體，設備設定變更或認證失敗時清除），再以 `usm.addUser(username, engineID, user)` 加入本地化使用者。
- **替代方案**：每台 v3 設備各建一個 `Snmp`／`USM`。可行但浪費 socket 與執行緒，設備多時不划算。

### D3. 設備資料表擴充欄位
`devices` 新增：`snmp_version TEXT DEFAULT '2c'`、`v3_user`、`v3_auth_protocol`、`v3_auth_password`、`v3_priv_protocol`、`v3_priv_password`（皆 `TEXT NOT NULL DEFAULT ''`）。安全等級不另存，由是否有密碼推導（見 spec `device-snmp-access`）。`DeviceConfig` 擴充同名欄位並保留既有建構子（預設 v2c），現有測試與呼叫端不需修改。
- API 回應以 `v3AuthConfigured`／`v3PrivConfigured` 布林值取代密碼；更新時密碼欄位缺席即沿用舊值、空字串代表清除。

### D4. 協定列舉擴充共用於設備與 Trap 使用者
`V3UserConfig.AuthProtocol` 增加 `SHA224/SHA256/SHA384/SHA512`，`PrivProtocol` 增加 `AES192/AES256`，對應 SNMP4J 的 `AuthHMAC128SHA224` 等類別。資料庫存放字串名稱，解析時大小寫不敏感；未知字串回到預設（SHA／AES128）並記錄警告，與現有 `V3UserRepository` 的容錯行為一致。

### D5. 掃描服務與執行模型
新增 `discovery` 套件：
- `DiscoveryService`：單一執行緒的 `ExecutorService`（同時最多一個掃描在跑，其他排隊），以 `ConcurrentHashMap<deviceName, scanId>` 防止同一設備重複掃描（回 409）。
- 掃描步驟：系統群組 GET → 依 `MibProbe` 清單逐項探測 → （選擇性）walk 子樹 → 寫入結果 → 清除超過 3 次的舊掃描。每個步驟更新 `device_scans.status`／`progress`，前端每 2 秒輪詢。
- `MibProbe`：以程式內常數定義（名稱、根 OID、取樣方式：單值 GET 或子樹 walk 前 N 筆、欄位名稱對照）。新增探測項目只需加一筆定義。
- `VendorRegistry`：企業編號 → 廠牌名稱的內建對照（Cisco 9、HP 11、Microsoft 311、Dell 674、Juniper 2636、Check Point 2620、Huawei 2011、VMware 6876、Net-SNMP 8072、Fortinet 12356、MikroTik 14988、H3C 25506、Ubiquiti 41112 等）。
- 上限：10,000 筆、120 秒，以 walk 迴圈中的計數與 deadline 檢查實作，超過時標記 `truncated`。
- **為何不用 SNMP4J `TableUtils`**：walk 任意子樹（非表格）需要逐筆控制上限與 deadline，直接迴圈 GETBULK／GETNEXT 較直接。

### D6. 資料表
- `device_scans(id, device_name, started, finished, status, progress, error, sys_descr, sys_object_id, sys_name, sys_uptime, vendor, probes_json, oid_count, truncated)`：`probes_json` 存 MIB 探測結果（每項的支援與否與取樣值），不需要逐欄正規化，前端直接顯示。
- `device_scan_results(scan_id, oid, type, value)`，索引 `(scan_id, oid)`；刪除掃描時一併刪除（手動刪除，SQLite 外鍵預設未啟用）。
- 掃描結果存的 `value` 為 SNMP4J `Variable.toString()`，`type` 為 `getSyntaxString()`；OCTET STRING 中不可列印的內容以 hex 顯示。

### D7. API
- `POST /api/admin/discovery/scans`（body：`deviceName`、`walk`、`subtrees[]`）→ 202 `{scanId}`；409／404 依 spec。
- `GET /api/admin/discovery/scans?device=` → 掃描清單（不含 OID 明細）。
- `GET /api/admin/discovery/scans/{id}` → 單次掃描資訊與探測結果。
- `GET /api/admin/discovery/scans/{id}/oids?prefix=&q=&offset=&limit=` → OID 明細分頁（`limit` 上限 500）。
- `DELETE /api/admin/unknown-traps/oids/{id}`、`DELETE /api/admin/unknown-traps/sources/{id}`。
- `POST /api/admin/trap-mappings` 新增選填 `fromUnknownId`：建立成功後刪除該筆未定義記錄；觸發 OID 重複時回 409（目前 UNIQUE 違反會變成 500）。
- `POST /api/admin/devices` 建立設備成功後，刪除來源 IP 等於該設備 host 的未登錄來源記錄（不論是否從未登錄來源清單建立，該 IP 都已不再是未登錄來源；因此不需要額外的 `fromUnknownSourceId` 參數）。

### D8. 前端
沿用 `index.html` 單檔結構，新增「OID 探索」與「Trap 對應」兩個 `<section>` 與對應的 `load*()` 函式；所有插入表格的值一律經過 `esc()`（OID 值、sysDescr、VarBind 都可能含設備端可控的字串）。設備對話框依版本切換顯示 community 或 v3 欄位。

## Risks / Trade-offs

- [大型設備 walk 耗時、對設備造成負載] → 預設只探測固定項目，完整 walk 需勾選；GETBULK max-repetitions 設 20；單一執行緒序列化所有掃描；上限 10,000 筆／120 秒。
- [掃描與輪詢同時查同一台設備] → 兩者使用不同 `SnmpSession`，互不阻塞；設備端多一次查詢負載可接受。
- [設備端字串含惡意 HTML] → 前端一律 `esc()`；後端不做 HTML 處理，由前端負責（與現有 XSS 修正一致）。
- [engine ID 快取失效（設備重灌或更換）] → 認證錯誤或 `unknownEngineID` 報告時清除該設備的快取並重新探索一次。
- [SQLite 掃描結果膨脹] → 每台設備只保留 3 次、每次最多 10,000 筆。
- [明文密碼] → 與現狀一致；API 不回傳；文件註明 DB 檔案權限需限制。

## Migration Plan

1. 部署新版本：`SchemaManager` 自動補 `devices` 新欄位、建立兩張新表；既有設備預設 v2c，輪詢行為不變。
2. Poller 改用 `SnmpSession` 後，以既有 154+ 個測試與 `test.sh` 確認 v2c 行為無回歸；新增 v1／v3 的端對端測試（同 `TrapReceiverEndToEndTest` 模式，以 SNMP4J 在本機扮演 agent）。
3. 回滾：新欄位與新表不影響舊版程式（舊版不讀取），可直接換回舊 JAR。

## Open Questions

- 廠牌對照表的初始清單是否需要加入使用者環境中的特定品牌（可隨時擴充，不影響設計）。
