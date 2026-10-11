# Tasks

## 1. 後端多語系基礎

- [x] 1.1 新增 `i18n` 套件：`Msg`、`Messages`（`resolve(Accept-Language)` 依 design D6 對應、不回退 JVM 語系、缺鍵回退正體中文）、`LocalizedException`（`getMessage()` 為英文）、`StoredText`（`@i18n:` 編碼與解碼）；新增三份 `i18n/messages*.properties`；驗證：`MessagesTest` 涵蓋語系對應（`zh-TW`、`zh-Hant`、`zh-HK`、`zh-CN`、`zh-Hans`、`zh`、`en-US`、`ja`、空值）、參數格式化、缺鍵回退，以及三份檔案鍵集合與參數數量一致；`StoredTextTest` 涵蓋有無參數、舊文字原樣
- [x] 1.2 新增 `LocaleFilter`（請求語系存入 ThreadLocal）；`BaseJsonServlet` 新增 `t(key, args)`、`writeError(resp, status, Msg)`，JSON 解析與必填欄位錯誤改用訊息代碼；驗證：Servlet 單元測試以 `Accept-Language: en` 取得英文的必填欄位錯誤

## 2. 後端訊息轉換

- [x] 2.1 驗證方法改回傳 `Msg`：`DeviceConfig`、`DiskFilter`、`ThresholdConfig`、`ProfileMetric`、`MetricKind`、`MetricProfile`；自訂例外改攜帶 `Msg`：`SnmpException`、`MetricException`、`DeviceNotFoundException`；驗證：既有單元測試改為比對訊息代碼，全數通過
- [x] 2.2 所有 Servlet 與 `AuthFilter` 的錯誤與成功訊息改用訊息代碼並依請求語系輸出；`UserRepository` 等拋給使用者的例外訊息一併轉換；驗證：既有 Servlet 測試（預設正體中文）通過，並各新增至少一個 `Accept-Language: en` 案例
- [x] 2.3 存入資料庫的系統文字改存 `@i18n:` 編碼，讀取端轉換：告警描述（`ThresholdEngine`、`ThresholdState`、`MetricStore.deleteDeviceByName`、`DeviceServlet` 的磁碟過濾解除）、稽核說明（`AuthFilter`）、OID 掃描進度／錯誤與 MIB 探測名稱及取樣（`DiscoveryService`、`MibProbes`）；驗證：`MetricStoreTest`／`V1AlertServletTest` 確認同一筆告警以英文與簡體中文查詢得到對應文字、舊文字原樣；`DiscoveryServiceEndToEndTest` 確認探測結果以英文查詢為英文
- [x] 2.4 內建項目顯示名稱：監控範本回應新增 `displayName`／`displayDescription`、設備回應新增 `appliedProfileDisplayName`、系統設定 `description` 依語系、複製範本後綴依語系；驗證：`ProfileServletTest`、`SettingsServletTest` 新增英文案例，識別用 `name` 不變
- [x] 2.5 以掃描程式檢查 `src/main/java` 中殘留的中文字串字面值（排除註解與 log），列入 `mvn test`（`NoHardcodedUserTextTest`）；驗證：測試通過，且刻意加入一個中文字串時測試失敗

## 3. Web 層：靜態路徑與快取

- [x] 3.1 `AuthFilter` 公開前端外殼路徑、未登入或需改密碼的非 API 請求導向 `/`；新增 `StaticCacheFilter`；`JettyServer` 設定歡迎頁；驗證：`AuthFilterTest` 涵蓋 `/assets/x.js` 未登入可取得、`/login.html` 導向 `/`、未登入 `/api/admin/devices` 仍 401、must_change session 存取非 API 導向 `/`；快取標頭以單元測試或 `test.sh` 驗證
- [x] 3.2 刪除 `src/main/resources/static/*.html`；`test.sh` 改以 `/api/info` 判斷就緒，新增首頁可取得、`index.html` 不快取、英文錯誤訊息（`Accept-Language: en`）與已移除舊頁面導向的案例；驗證：`./test.sh --skip-build` 全數通過

## 4. 前端專案與建置

- [x] 4.1 建立 `frontend/`（Vue 3、TypeScript、Naive UI、vue-i18n、vue-router、`@vicons/ionicons5`、Vite、Vitest），Vite 開發伺服器代理 `/api` 到 8080；`.gitignore` 加入建置產物；驗證：`npm run build` 與 `npm run test` 成功
- [x] 4.2 `pom.xml` 整合 frontend-maven-plugin 與複製建置結果，支援 `skip.frontend`、`node.download.root`、`npm.registry` 屬性；CI 不需另外安裝 Node（npm 快取因推送權限需 `workflow` scope，另行補上）；驗證：`mvn -B clean package` 產出的 JAR 內含 `static/index.html` 與 `static/assets/`，`-Dskip.frontend=true` 時略過前端
- [x] 4.3 新增離線檢查：掃描 `frontend/dist` 中指向外部主機的網址（排除 SVG 命名空間等非請求用途），列入前端測試；驗證：檢查通過，刻意加入外部網址時失敗
- [x] 4.4 README 與 `CLAUDE.md` 更新：前端架構、開發與建置指令、離線建置（鏡像設定）、多語系架構與新增訊息的方式；驗證：依 README 指令可完成前端開發伺服器啟動與完整建置

## 5. 前端基礎

- [x] 5.1 `i18n` 模組與三份語系檔骨架（zh-TW 為基準型別），語言決定、切換、記憶，同步 Naive UI 語系與 `<html lang>`；驗證：Vitest 涵蓋 spec「介面語言的決定與切換」四個 Scenario 與三份語系鍵集合一致
- [x] 5.2 API 用戶端（`Accept-Language`、`ApiError`、401／需改密碼導向）；驗證：Vitest 以模擬 `fetch` 確認標頭、錯誤訊息與導向
- [x] 5.3 路由與認證守衛、主版面（側邊選單、頁首語言切換、登入者選單：改密碼／登出、版本資訊）、登入頁（含語言切換）、強制改密碼頁；驗證：瀏覽器實測登入、預設帳號強制改密碼、登入後回到原頁、登出

## 6. 前端功能頁

- [x] 6.1 狀態頁（使用率條、最高未解除等級、無指標數據的設備、30 秒自動更新）；驗證：瀏覽器實測與升級前畫面資訊一致
- [x] 6.2 設備管理（清單含閾值數量與連線中斷設定、新增／編輯對話框含 v3 欄位、監控範本、磁碟過濾與磁碟探測、連線中斷告警、刪除）；驗證：瀏覽器實測新增 v2c 與 v3 設備、探測磁碟並勾選、編輯時密碼留空不變更
- [x] 6.3 OID 探索（設備選擇、掃描選項、進度輪詢、系統資訊、MIB 探測、OID 表篩選與分頁、加入範本）；驗證：瀏覽器實測對本機 snmpd 完成一次掃描並將一個 OID 加入範本
- [x] 6.4 監控範本（清單、新增／編輯／複製／刪除、指標編輯、對設備試算，內建範本唯讀並顯示翻譯名稱）；驗證：瀏覽器實測複製內建範本、修改指標並試算
- [x] 6.5 Trap 對應（對應清單含等級、新增／編輯對話框、未定義 Trap 建立對應與刪除、未登錄來源新增為設備與刪除）；驗證：瀏覽器實測從未定義 Trap 建立 WARNING 對應
- [x] 6.6 閾值設定（選設備、清單編輯、新增、刪除、套用到其他設備、無閾值提示）；驗證：瀏覽器實測設定兩段閾值並套用到另一台設備
- [x] 6.7 告警記錄（等級、設備、未解除篩選、等級顏色、描述）；驗證：瀏覽器實測三種篩選
- [x] 6.8 帳號管理、API Token、稽核日誌、系統設定（含保存期限）；驗證：瀏覽器實測新增帳號、重設他人密碼、產生與刪除 Token、查詢稽核日誌、修改保存期限

## 7. 整合檢查

- [x] 7.1 `mvn -B clean verify` 與 `./test.sh --skip-build` 全數通過，CI 綠燈
- [x] 7.2 離線實測：瀏覽器只允許連到本系統（封鎖所有外部網域），走過登入與每個功能頁，確認功能正常且沒有被封鎖的外部請求
- [x] 7.3 三語實測：分別以正體中文、簡體中文、英文走過每個功能頁，畫面上沒有其他語言的固定文字（使用者資料除外），並觸發一個後端錯誤確認訊息語言一致；附三種語言的截圖
