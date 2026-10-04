# Proposal

## Why

`add-device-oid-discovery` 讓使用者看得到每台設備支援哪些 OID，但 Poller 仍只認得寫死的 Net-SNMP OID：Cisco 交換器、Windows、其他廠牌的 CPU 與記憶體依舊抓不到，而現有的「Poll OID 管理」雖然可以新增 OID，抓到的值卻不會被使用或儲存。使用者需要能把掃描到的 OID 指定成監控指標，並讓同型號的設備自動共用這份設定。

依賴：本 change 需在 `add-device-oid-discovery` 之後實作（使用其 `SnmpSession` 連線元件與掃描結果）。

## What Changes

- **監控範本（metric profile）**：一份範本定義「CPU、記憶體、磁碟與自訂指標分別從哪些 OID、用什麼方式計算」。計算方式為固定的幾種類型（單一值、100 減去該值、已用÷總量、已用÷（已用＋剩餘）、表格欄位平均、儲存空間表、Net-SNMP 記憶體公式），不提供任意運算式。
- **自動套用**：範本可設定 sysObjectID 前綴；設備依自己的 sysObjectID 自動套用前綴最長的範本，也可在設備上手動指定範本。
- **內建範本**：
  - Net-SNMP（UCD-SNMP）：與目前寫死的計算方式完全相同，既有 Linux 設備升級後結果不變。
  - HOST-RESOURCES 通用：沒有其他範本符合時的預設，用 hrProcessorLoad 與 hrStorage 計算 CPU、記憶體、磁碟。
  - Cisco IOS：cpmCPUTotal 與 ciscoMemoryPool。
  - 內建範本不可修改或刪除，但可以複製後編輯。
- **自訂指標**：範本可定義 CPU／記憶體／磁碟以外的指標（例如溫度、連線數），數值存入新的時間序列資料表，可設定閾值並套用既有的告警狀態機。
- **Poller 依範本抓取**：每台設備依套用的範本決定要查哪些 OID 與如何計算；輪詢時順便更新設備的 sysObjectID。
- **預覽**：儲存範本前可對指定設備試算一次，看到每個指標算出的值或錯誤原因。
- **網頁介面**：新增「監控範本」分頁；「OID 探索」的 OID 表每列加上「加入範本」；設備對話框可選擇範本（預設自動）；狀態頁與 `/api/v1/status` 增加自訂指標。
- **BREAKING**：移除「Poll OID 管理」（`/api/admin/poll-oids` 與 `poll_oids` 的使用）。該功能新增的 OID 原本就不會被使用，只有停用有效果；升級時若既有設定停用了某些 OID，會轉成內建 Net-SNMP 範本的複本並停用對應指標，套用到原本的設備。

## Capabilities

### New Capabilities
- `device-metric-profiles`：監控範本的定義、內建範本、範本與設備的對應規則（自動依 sysObjectID 或手動指定）、範本預覽。
- `metric-collection`：Poller 依範本收集指標、計算規則、自訂指標的儲存與閾值告警、狀態查詢輸出。

### Modified Capabilities
（無：專案目前沒有既有 spec；`device-snmp-access`、`device-discovery` 由前一個 change 新增，本 change 不改變其需求）

## Impact

- **資料表**：新增 `metric_profiles`、`profile_metrics`、`custom_metrics`；`devices` 新增 `profile_id`（可為空，代表自動）與 `sys_object_id`；`threshold_settings` 允許自訂指標名稱；`poll_oids` 停止使用（保留資料表不刪，方便回滾）。
- **程式碼**：`SnmpPoller` 改為依範本執行；新增 `profile` 套件（範本模型、計算器、比對）；`ThresholdEngine` 新增通用的 `evaluate(device, metricKey, value)`；`MetricStore` 新增對應 Repository；移除 `PollOidServlet`、`PollOidRepository` 的使用與 `test.sh` 的 Poll OID 段落。
- **API**：新增 `/api/admin/profiles/*`；`/api/admin/devices` 增加選填 `profileId`；`/api/admin/thresholds` 增加新增與刪除（自訂指標）；`/api/v1/status` 回應增加 `custom` 欄位（向下相容）；**移除** `/api/admin/poll-oids`。
- **相依套件**：無新增。
