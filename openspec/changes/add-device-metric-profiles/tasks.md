# Tasks

前置條件：`add-device-oid-discovery` 已完成並合併（需要 `SnmpSession` 與掃描結果）。

## 1. 計算器（與 SNMP 通訊無關）

- [x] 1.1 新增 `profile` 套件的 `MetricCalculator` 介面與 `MetricValue`、`SnmpResult`（GET 值與 walk 結果的唯讀包裝）；驗證：`mvn compile` 通過
- [x] 1.2 實作 `VALUE`、`HUNDRED_MINUS`、`USED_TOTAL_PERCENT`、`USED_FREE_PERCENT` 計算器與參數驗證（缺 OID、非數字、除以零）；驗證：各自的單元測試涵蓋正常值、noSuchObject、非數字、總量為 0
- [x] 1.3 實作 `TABLE_AVERAGE`、`STORAGE_TABLE`（依類型篩選、逐列使用率）、`UCD_MEMORY`；將現有 `PollParserTest` 的全部案例改寫為 `HUNDRED_MINUS`／`UCD_MEMORY` 的測試並保留；驗證：測試通過且案例數不少於原本

## 2. 範本資料與內建範本（device-metric-profiles 後端）

- [x] 2.1 `SchemaManager` 建立 `metric_profiles`、`profile_metrics`，補 `devices.profile_id`／`sys_object_id`；新增 `ProfileRepository` 並由 `MetricStore` 委派；驗證：`MetricStoreTest` 新增範本 CRUD、名稱重複、指標名稱重複測試
- [x] 2.2 新增 `BuiltinProfiles`（Net-SNMP、HOST-RESOURCES、Cisco IOS），啟動時依名稱同步；驗證：測試確認重複啟動不會產生重複範本、使用者複本不被覆蓋
- [x] 2.3 新增 `ProfileResolver`（手動指定 → 最長前綴 → HOST-RESOURCES）；驗證：單元測試涵蓋 spec「設備套用範本的規則」的四個情境
- [x] 2.4 新增 `ProfileServlet`（列表含套用設備數、CRUD、複製、內建 403、指標驗證 400）並註冊於 `JettyServer`；驗證：`ProfileServletTest` 涵蓋 201／400／403，`test.sh` 新增範本 API 段落
- [x] 2.5 `DeviceServlet`／`DeviceRepository` 支援 `profileId`，查詢回傳實際套用的範本名稱；刪除被指定的範本時清空設備的 `profile_id`；驗證：`DeviceServletTest` 與 `MetricStoreTest` 新增案例

## 3. 依範本收集（metric-collection 後端）

- [x] 3.1 `threshold_settings` 補 `direction`；`ThresholdEngine` 新增 `evaluate(device, metricKey, value)`，無閾值時略過；`ThresholdServlet` 新增 POST／DELETE（內建三項 403）；驗證：`ThresholdEngineTest` 新增高／低方向與無閾值案例，`test.sh` 新增自訂閾值段落
- [x] 3.2 `SchemaManager` 建立 `custom_metrics`；`MetricRepository` 新增寫入與最新值查詢，並納入 `retention_metrics_days` 清除；`queryLatestStatus()` 增加 `custom`；驗證：`MetricStoreTest` 新增案例，確認既有 `cpu`／`memory`／`disks` 欄位不變；`DataRetentionJobTest` 確認自訂指標會被清除
- [x] 3.3 `SnmpPoller` 改為 D5 流程（合併 GET、表格只 walk 一次、sysObjectID 更新、依指標分派儲存與評估、單一指標失敗不影響其他、警告 log 限流）；驗證：新增 Poller 端對端測試，本機 SNMP4J agent 分別模擬 Net-SNMP、Cisco、未知廠牌設備，確認套用的範本與儲存的數值
- [x] 3.4 Net-SNMP 回歸：以本機 agent 提供固定的 UCD 與 hrStorage 數值，比對新流程與改寫前 `PollParser`＋磁碟邏輯的計算結果；驗證：數值完全相同
- [x] 3.5 範本預覽 `POST /api/admin/profiles/preview`（不儲存、不告警、逐指標回報結果或錯誤）；驗證：`ProfileServletTest` 與端對端測試涵蓋成功與 OID 不存在
- [x] 3.6 更新 `CLAUDE.md` 資料流與設定常數段落（範本、計算類型、內建範本位置）；驗證：文件與程式一致（人工檢查）

## 4. 移除 Poll OID 管理

- [ ] 4.1 實作 D8 升級轉換（以 `poll_oids_migrated` 標記只執行一次）；驗證：`MetricStoreTest` 以含停用 OID 的舊 DB 開啟，確認產生轉換範本並指定給正確設備，第二次開啟不重複轉換
- [ ] 4.2 移除 `PollOidServlet` 註冊與 `PollOidRepository` 的使用、`test.sh` 第 11 段改為確認 `/api/admin/poll-oids` 回 404；驗證：`./test.sh --skip-build` 通過

## 5. 網頁

- [ ] 5.1 「監控範本」分頁（列表、內建標示、套用設備數、新增／複製／編輯／刪除、依計算類型動態欄位、預覽）；驗證：Playwright 腳本建立範本、加入指標、對本機 agent 預覽並截圖
- [ ] 5.2 「OID 探索」OID 表加「加入範本」；驗證：Playwright 從掃描結果加入自訂指標，範本頁出現該指標
- [ ] 5.3 設備對話框加範本選單（自動＋目前套用）；狀態頁顯示自訂指標；所有新顯示的值經 `esc()`；驗證：Playwright 截圖確認，並以含 `<script>` 的單位字串確認不執行

## 6. 整合檢查

- [ ] 6.1 `mvn -B clean verify` 與 `./test.sh --skip-build` 全數通過，CI 綠燈
- [ ] 6.2 以真實設備（Linux Net-SNMP、Cisco 或其他網路設備、Windows）實測：Linux 數值與升級前一致、其他設備出現 CPU／記憶體數值（需使用者在有設備的環境執行）
