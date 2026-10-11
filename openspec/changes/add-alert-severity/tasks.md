# Tasks

## 1. 等級模型與多等級狀態機

- [x] 1.1 新增 `threshold.Severity`（WARNING／CRITICAL，大小寫不敏感解析，不合法回 null）；`AlertEvent` 增加 `severity` 與 `CHANGED` 類型；驗證：`AlertEventTest` 涵蓋三種事件與等級
- [x] 1.2 `ThresholdConfig` 改為 `direction`、`warning`、`critical`（可為 null）、`rearmMargin`、`triggerCount`、`rearmCount`，提供 `rawLevel(value)`、`recoveredFrom(level, value)` 與 `validate()`（spec「閾值內容」「閾值數值驗證」，含內建指標方向固定 HIGH 與 0～100 範圍）；移除 `cpu()/memory()/disk()` 預設工廠；驗證：`ThresholdConfigTest` 改寫並涵蓋每個驗證情境
- [x] 1.3 依 design D2 改寫 `ThresholdState` 為多等級狀態機（含初始等級參數、設定變更後重新定位）；驗證：`ThresholdStateTest` 改寫，spec「閾值告警的觸發」「升級」「降級與解除」「閾值變更後立即生效」的每個 Scenario 各一個測試，另含 LOW 方向

## 2. 資料表、Repository 與升級轉換

- [x] 2.1 `SchemaManager` 新增 `device_thresholds` 資料表與 D1 的欄位遷移；移除 `ThresholdRepository.seedDefaults()` 的呼叫；驗證：`MetricStoreTest` 以全新 DB 開啟後沒有任何閾值，以舊 schema 開啟後 `alert_events`／`trap_oid_mappings` 既有資料的 `severity` 為 CRITICAL
- [x] 2.2 `ThresholdRepository` 改為每設備：`load(device, metric)`（含 `disk:<掛載點>` → `disk` 的查找，找不到回 null）、`queryByDevice`、`upsert`、`delete`、`deleteByDevice`、`copy(from, to)`（單一交易）；由 `MetricStore` 委派；驗證：`MetricStoreTest` 新增 upsert 取代、覆寫優先、只有覆寫時其他掛載點為 null、copy 覆蓋同名保留其他、copy 目標不存在時整批不變
- [x] 2.3 `AlertRepository`：`save` 寫入 severity；`hasOpen`、`purgeBefore` 納入 `CHANGED`；新增 `lastAlertState(device, metric)`、`queryOpenAlerts()`（每組 `MAX(id)`）、`resolveOpenAlerts(device, message)`；`query` 支援 severity 與 active 篩選；驗證：`MetricStoreTest` 新增未解除查詢（含 CHANGED 為最後一筆）、等級篩選、刪除設備解除；`DataRetentionJobTest` 新增「最後一筆為 CHANGED 時保留」
- [x] 2.4 新增 `ThresholdMigration`（design D8，以 `thresholds_migrated` 標記只執行一次）並在 `MetricStore` 啟動時呼叫；驗證：`MetricStoreTest` 涵蓋 spec「從全域閾值升級」三個 Scenario，並以轉換後的設定餵入 `ThresholdState` 確認與舊版觸發／恢復結果相同

## 3. 閾值引擎與輪詢

- [x] 3.1 `ThresholdEngine` 改以 `(device, metric)` 讀取設定、首次建立狀態時以 `lastAlertState` 還原（D4）、`config == null` 且有等級時記錄 RESOLVED（D3）、寫入事件含等級；驗證：`ThresholdEngineTest` 改寫，涵蓋兩台設備不同門檻、無閾值不告警、磁碟預設與覆寫、刪除閾值後解除、重啟後持續超標不重複觸發、重啟後恢復會解除
- [x] 3.2 `devices` 讀寫 `unreachable_severity`／`unreachable_count`（`DeviceConfig`、`DeviceRepository`、`DeviceServlet` 驗證：等級只能是空白／WARNING／CRITICAL、次數至少 1，更新時省略沿用）；驗證：`DeviceConfigTest`、`DeviceServletTest` 新增合法與 400 案例
- [x] 3.3 `ThresholdEngine.evaluateReachability` 與 `SnmpPoller` 呼叫（D5），訊息區分 Ping 失敗與 SNMP 無回應；驗證：`SnmpPollerEndToEndTest` 新增：預設不告警、CRITICAL 連續 2 次後觸發、恢復 ONLINE 後解除、關閉設定後解除
- [x] 3.4 `device_unreachable` 列為保留名稱：閾值 API 與範本自訂指標名稱驗證都拒絕；驗證：`ProfileResolverTest`（或範本驗證測試）與 3 群組的 Servlet 測試各一個 400 案例
- [x] 3.5 刪除設備時刪除其閾值並解除其未解除告警；儲存設備時若磁碟過濾改變，解除不再符合過濾條件的 `disk:<掛載點>` 未解除告警（design Risks）；驗證：`DeviceServletTest` 或端對端測試涵蓋兩種情境

## 4. Trap 等級

- [x] 4.1 `trap_oid_mappings.severity` 讀寫、`TrapOidMapping.Mapping` 增加等級、`TrapMappingServlet` 接受 `severity`（省略為 CRITICAL、不合法回 400）；驗證：`TrapMappingServletTest` 新增預設、指定 WARNING、不合法三個案例
- [x] 4.2 `TrapReceiver` 觸發時寫入對應等級、解除時使用 `lastAlertState` 的等級（D6）；驗證：`TrapReceiverHandleTest` 與 `TrapReceiverEndToEndTest` 新增 WARNING 對應的觸發與解除

## 5. 閾值與告警 API

- [x] 5.1 新增 `DeviceThresholdServlet`（D7：GET／PUT／DELETE／POST copy）並在 `JettyServer` 註冊；移除 `ThresholdServlet`、未註冊的 `AlertServlet` 與其測試；驗證：新增 `DeviceThresholdServletTest` 涵蓋 upsert、400（兩個觸發值都沒有、Warning 高於 Critical、內建指標改 LOW、百分比超過 100、保留名稱）、404 設備不存在、copy 成功與目標不存在 400
- [x] 5.2 `V1AlertServlet` 支援 `severity`、`active` 篩選並回傳 `severity`；驗證：`V1AlertServlet` 測試新增等級篩選、只看未解除、不合法等級 400
- [x] 5.3 `test.sh` 改寫第 9 節（每設備閾值：新增、更新、刪除、copy、各種 400）、擴充第 7 節（Trap 對應等級）與第 13 節（告警等級與 active 篩選），並新增「已移除 `/api/admin/thresholds` 回 404」；驗證：`./test.sh --skip-build` 全數通過
- [x] 5.4 更新 README（API 表、閾值設定說明、移除 `ThresholdConfig.java` 範例）與 `CLAUDE.md`（閾值引擎、等級、還原、保留名稱）；驗證：文件中不再出現 `/api/admin/thresholds` 與全域預設閾值的描述

## 6. 網頁

- [x] 6.1 「閾值設定」分頁改為選設備後編輯（D9），含指標選單（cpu／memory／disk／最近回報的掛載點／範本自訂指標）、沒有閾值時的提示、「套用到其他設備」對話框；驗證：Playwright 腳本為設備 A 設定 `cpu` Warning 80／Critical 95 與 `disk:/data` 覆寫、套用到設備 B，重新整理後 B 顯示相同設定
- [x] 6.2 設備對話框新增連線中斷告警（關閉／Warning／Critical、連續次數）；Trap 對應對話框與清單新增等級；驗證：Playwright 腳本儲存後重新開啟對話框，值保留
- [x] 6.3 告警記錄頁：等級徽章、描述文字欄、事件依等級著色、等級／設備／只看未解除篩選，全部經 `esc()`；狀態頁顯示每台設備最高未解除等級；驗證：Playwright 腳本以本機 agent 觸發 WARNING 與 CRITICAL 告警並截圖，確認顏色與篩選結果，且含 `<script>` 的 Trap 描述顯示為文字

## 7. 整合檢查

- [x] 7.1 `mvn -B clean verify` 與 `./test.sh --skip-build` 全數通過，CI 綠燈
- [x] 7.2 以既有資料庫（含全域閾值與未解除告警）升級實測：每台設備取得與原本相同的 Critical 閾值，未解除告警在下一輪不重複觸發；並以本機 Net-SNMP 設定 Warning／Critical 觀察一次升級與降級
