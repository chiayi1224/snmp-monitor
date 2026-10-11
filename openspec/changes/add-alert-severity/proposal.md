# Proposal

## Why

目前告警沒有等級：每個指標只有一組全域的「觸發值／恢復值」，所有設備共用，事件只有 TRIGGERED／RESOLVED。結果是無法區分「該注意」與「必須立刻處理」，而一台資料庫主機和一台測試機也只能套用同一個 CPU 門檻。系統也會預先種入 CPU 90％、記憶體 85％、磁碟 80％ 的全域閾值，新增的設備在使用者還沒考慮過之前就開始告警。另外，設備連不上時只會改狀態、不會告警，而閾值狀態只存在記憶體，重啟後未解除的告警會重複觸發或永遠不會解除。

## What Changes

- **兩段式告警等級**：所有告警都帶等級 `WARNING` 或 `CRITICAL`。一個閾值可以設定 Warning 與 Critical 兩個觸發值（至少一個），同一個告警會在等級之間升降（Warning → Critical → Warning → 解除），每次變化都留下紀錄。
- **閾值改為每台設備設定**：CPU、記憶體、磁碟與自訂指標的閾值都屬於個別設備。磁碟有一組「設備預設」，可以為個別掛載點覆寫。
- **BREAKING：取消預設閾值**：不再種入任何閾值；沒有設定閾值的設備與指標只記錄數值、不告警。移除全域閾值 API `/api/admin/thresholds`。
- **升級轉換**：既有資料庫中的全域閾值，會在升級時複製到每台既有設備（作為 Critical），原本運作中的告警行為不中斷；之後新增的設備沒有閾值。
- **複製到其他設備**：可以把一台設備的閾值設定一次套用到多台設備。
- **設備無法連線告警**：每台設備可開啟「連線中斷告警」並選擇等級與連續失敗次數（預設關閉）；設備變成 OFFLINE 或 SNMP_ERROR 時產生告警，恢復連線後自動解除。
- **Trap 告警等級**：Trap 對應維持全域設定（不分設備），每筆對應新增等級欄位；既有對應升級後為 Critical。
- **告警狀態在重啟後延續**：閾值引擎啟動後依資料庫中最後一筆告警還原各設備各指標的等級，不再重複觸發或遺漏解除。
- **告警頁與狀態頁**：告警記錄依等級著色（Critical 紅、Warning 橘、解除綠），可依等級、設備、「只看未解除」篩選，並顯示 Trap 描述文字；狀態頁每台設備顯示目前最高的未解除告警等級。告警查詢 API 增加等級與未解除篩選，回應增加 `severity` 欄位（向下相容）。

## Capabilities

### New Capabilities
- `device-thresholds`：每台設備的閾值設定（Warning／Critical、方向、恢復寬度、連續次數）、磁碟的設備預設與掛載點覆寫、無預設閾值、從全域設定升級轉換、複製到其他設備。
- `alert-severity`：告警等級模型、等級升降與解除規則、重啟後狀態延續、設備連線中斷告警、Trap 對應的等級、告警查詢與畫面呈現。

### Modified Capabilities
（無：`openspec/specs/` 目前沒有已封存的 spec。本 change 取代 `add-device-metric-profiles` 的 `metric-collection` 中「自訂指標閾值」需求（全域閾值改為每台設備、兩段等級），並擴充 `add-device-oid-discovery` 的 `trap-mapping`（對應增加等級）。若那兩個 change 先封存，實作前應將本 change 的相關需求改寫為對應 spec 的 MODIFIED delta。）

## Impact

- **資料表**：新增 `device_thresholds`（每台設備每個指標一筆）；`devices` 新增連線中斷告警設定欄位；`alert_events` 新增 `severity`；`trap_oid_mappings` 新增 `severity`；`threshold_settings` 升級轉換後停止使用（保留資料表不刪，方便回滾）。
- **程式碼**：`threshold` 套件（`ThresholdConfig`、`ThresholdState` 改為多等級狀態機、`ThresholdEngine` 改以設備查閾值並從資料庫還原狀態、`AlertEvent` 增加等級與 `CHANGED` 事件）；`ThresholdRepository` 改為每設備；`AlertRepository` 查詢；`SnmpPoller` 在狀態改變時評估連線告警；`TrapReceiver` 寫入對應的等級；刪除設備時一併刪除其閾值。
- **API**：**移除** `/api/admin/thresholds`；新增 `/api/admin/device-thresholds/*`（查詢、儲存、刪除、複製）；`/api/admin/devices` 增加連線中斷告警欄位；`/api/admin/trap-mappings` 增加 `severity`；`/api/v1/alerts` 增加 `severity`、`active` 篩選與回應欄位 `severity`。
- **網頁**：「閾值設定」分頁改為先選設備再編輯；設備對話框增加連線中斷告警；Trap 對應對話框增加等級；告警記錄與狀態頁著色與篩選。
- **測試**：`ThresholdStateTest`、`ThresholdEngineTest`、`ThresholdServletTest`、Trap 相關測試、`MetricStoreTest`、`test.sh` 第 9 與 13 節需改寫或擴充。
- **相依套件**：無新增。
