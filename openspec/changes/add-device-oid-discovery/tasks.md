# Tasks

## 1. SNMPv3 協定擴充

- [x] 1.1 `V3UserConfig` 的 `AuthProtocol` 加入 SHA224／SHA256／SHA384／SHA512、`PrivProtocol` 加入 AES192／AES256，並提供大小寫不敏感的字串解析（未知值回預設並記錄警告）；驗證：`V3UserConfigTest` 新增各協定 OID 對照與解析測試通過
- [x] 1.2 `V3UserRepository.toConfig` 改用 1.1 的解析；驗證：`TrapReceiverEndToEndTest` 新增 SHA-256／AES-256 的 v3 Trap 案例並通過

## 2. 設備 SNMP 連線設定（device-snmp-access）

- [x] 2.1 `SchemaManager.MIGRATIONS` 補 `devices` 的 `snmp_version`、`v3_*` 欄位；驗證：`MetricStoreTest` 以舊版 schema 建立的 DB 開啟後，既有設備版本為 `2c`
- [x] 2.2 `DeviceConfig` 擴充版本與 v3 欄位（保留既有建構子預設 v2c），加入驗證規則（版本值、priv 需要 auth、密碼至少 8 字元）；驗證：`DeviceConfigTest` 覆蓋 spec 中每個驗證情境
- [x] 2.3 `DeviceRepository` 讀寫新欄位；查詢回傳 `v3AuthConfigured`／`v3PrivConfigured` 而非密碼；更新時密碼缺席沿用舊值；驗證：`MetricStoreTest` 新增「查詢不含密碼」「更新不改密碼」測試
- [x] 2.4 `DeviceServlet` 接受新欄位，不合法時回 400；驗證：`DeviceServletTest` 新增版本不合法、priv 無 auth、密碼過短三個 400 案例
- [x] 2.5 新增 `snmp.SnmpSession`（自有 `SecurityProtocols`／`USM`、`targetFor()` 依版本建立 target、v3 依 engine ID 本地化使用者並快取、`get()`、`walk()`）；驗證：新增 `SnmpSessionEndToEndTest`，在本機以 SNMP4J 扮演 v1／v2c／v3 agent，GET 與 walk 皆成功，且兩台同 username 不同密碼的 v3 agent 可同時查詢
- [x] 2.6 `SnmpPoller` 改用 `SnmpSession`；v3 認證失敗時清除 engine ID 快取並標記 `SNMP_ERROR`；驗證：既有測試與 `./test.sh --skip-build` 全數通過（v2c 無回歸），並新增 Poller 對 v3 本機 agent 的輪詢測試
- [x] 2.7 設備對話框（`index.html`）依版本切換 community／v3 欄位，密碼欄位留空代表不變更；驗證：以 Playwright 腳本新增一台 v3 設備並重新開啟對話框，確認密碼欄位為空且顯示「已設定」
- [x] 2.8 更新 `CLAUDE.md` 架構段落（`SnmpSession` 與 v3 本地化說明）與 `test.sh`（新增 v3 設備 API 案例）；驗證：`./test.sh --skip-build` 通過

## 3. 掃描服務（device-discovery 後端）

- [x] 3.1 `SchemaManager` 建立 `device_scans`、`device_scan_results`；新增 `DiscoveryRepository`（建立、更新進度、寫入結果、分頁查詢、保留最近 3 次）並由 `MetricStore` 委派；驗證：`MetricStoreTest` 新增保留 3 次與前綴／關鍵字篩選測試
- [x] 3.2 新增 `VendorRegistry`（企業編號 → 廠牌）；驗證：單元測試涵蓋 Cisco、未知編號、非 enterprises 開頭的 sysObjectID
- [x] 3.3 新增 `MibProbe` 定義（HOST-RESOURCES 處理器／儲存、UCD-SNMP CPU／記憶體、IF-MIB、Cisco CPU／記憶體池）與探測執行；驗證：以本機 SNMP4J agent 提供部分 MIB，測試支援／不支援的判定與取樣值
- [x] 3.4 新增 `DiscoveryService`（單執行緒排程、同設備 409、系統資訊、探測、子樹 walk、10,000 筆／120 秒上限與截斷標記、失敗保留部分結果）；驗證：端對端測試涵蓋成功、截斷（將上限注入為小值）、設備無回應三種結果
- [x] 3.5 新增 `DiscoveryServlet`（D7 的四個端點）並在 `JettyServer` 註冊、`SnmpMonitor` 啟動與關閉 `DiscoveryService`；驗證：`DiscoveryServletTest` 涵蓋 202／404／409 與分頁上限，`test.sh` 新增掃描 API 案例（對不存在的設備 404）
- [x] 3.6 掃描只發出 GET／GETNEXT／GETBULK：本機 agent 測試中記錄收到的 PDU 類型；驗證：測試斷言沒有 SET

## 4. OID 探索網頁

- [x] 4.1 `index.html` 新增「OID 探索」分頁：設備選單、掃描選項（是否 walk、子樹）、進度輪詢、系統資訊、MIB 探測表、OID 表（前綴／關鍵字篩選、分頁）；所有值經 `esc()`；驗證：Playwright 腳本對本機 SNMP4J agent 執行一次掃描並截圖，確認三個區塊都有內容
- [x] 4.2 XSS 檢查：本機 agent 回傳含 `<script>` 的 sysDescr；驗證：Playwright 確認頁面顯示為文字且未執行

## 5. Trap 對應與未定義 Trap（trap-mapping）

- [x] 5.1 `TrapMappingServlet` 觸發 OID 重複回 409；支援 `fromUnknownId` 建立後刪除未定義記錄；驗證：`TrapMappingServletTest` 涵蓋 201、409（記錄保留）、帶 `fromUnknownId` 的刪除
- [x] 5.2 `UnknownTrapServlet` 新增兩個 DELETE 端點，`TrapRepository` 新增對應刪除方法；驗證：`MetricStoreTest` 確認刪除後同 OID 再次寫入從 1 計次
- [x] 5.3 `DeviceServlet` 建立設備後刪除來源 IP 等於該設備 host 的未登錄來源並 reload Trap Receiver（見 design D7）；驗證：`DeviceServletTest` 新增案例，`TrapReceiverEndToEndTest` 新增「新增設備後同 IP 的 Trap 以新名稱記錄」
- [x] 5.4 `index.html` 新增「Trap 對應」分頁：對應清單 CRUD、未定義 Trap 清單（建立對應、刪除）、未登錄來源清單（新增為設備、刪除）；驗證：Playwright 腳本以本機送出未定義 Trap，從頁面一鍵建立對應後再送一次，確認產生告警
- [x] 5.5 `test.sh` 新增 409 與刪除未定義記錄的 API 案例；驗證：`./test.sh --skip-build` 通過

## 6. 整合檢查

- [ ] 6.1 `mvn -B clean verify` 與 `./test.sh --skip-build` 全數通過，CI 綠燈
- [ ] 6.2 以真實設備（至少一台 Linux Net-SNMP 與一台網路設備）實測 v2c／v3 輪詢與掃描，記錄結果於 PR 說明（需使用者在有設備的環境執行）
