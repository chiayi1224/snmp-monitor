# Proposal

## Why

目前的網頁是三個手寫的靜態 HTML（`index.html` 約 1,700 行、82 個函式），所有分頁、對話框與狀態都擠在同一個檔案，用字串拼接 HTML 與手動 `esc()` 防 XSS，越來越難維護與擴充。介面文字與後端訊息全部寫死為正體中文，無法提供給使用簡體中文或英文的使用者。系統常部署在沒有網際網路的環境，前端不能依賴任何外部資源。

## What Changes

- **前端改用 Vue 3 + Naive UI**（TypeScript、Vite 建置），以單頁應用取代 `index.html`、`login.html`、`change-password.html`，保留現有所有功能：狀態、設備管理（含磁碟探測、連線中斷告警）、OID 探索、監控範本、Trap 對應、閾值設定、告警記錄、帳號管理、API Token、稽核日誌、系統設定、登入與強制改密碼。
- **完全離線可用**：Vue、Naive UI、圖示、字型與語系檔全部打包進 JAR，執行時不向任何外部網址發出請求。
- **建置整合進 Maven**：`mvn package` 仍是單一指令即可產出含前端的 JAR（由 Maven 下載 Node 並建置前端）；開發時可用 Vite 開發伺服器代理到 Jetty。
- **多語系架構**：支援正體中文（預設）、簡體中文、英文。
  - 前端介面文字全部經由語系檔，可在頁首與登入頁切換，選擇會記住；首次使用依瀏覽器語言決定。Naive UI 元件文字與日期格式隨語系切換。
  - 後端 API 的錯誤與成功訊息依請求的 `Accept-Language` 回傳對應語言（未指定時為正體中文，與現在相同）。
  - 系統產生並存入資料庫的文字（告警描述、稽核日誌說明、OID 掃描的錯誤與探測取樣）改存訊息代碼，讀取時依語系翻譯；升級前已存在的舊資料維持原文。
  - 內建監控範本與系統設定的說明文字依語系顯示。
- **靜態檔快取**：`index.html` 不快取、帶雜湊檔名的資源長期快取，升級後瀏覽器不會停留在舊版畫面。
- **BREAKING**：移除 `/login.html`、`/change-password.html` 與舊的 `index.html`；登入與改密碼改為單頁應用內的路由。前端外殼（`index.html` 與 `/assets/*`）改為不需登入即可取得（不含任何資料，資料仍由 API 認證保護）。

## Capabilities

### New Capabilities
- `web-ui`：網頁介面的交付方式與行為契約——單頁應用、離線可用、功能涵蓋、登入／強制改密碼／登入失效的導向、靜態資源快取。
- `localization`：支援的語言、語言決定與切換規則、介面與後端訊息的翻譯涵蓋範圍、存入資料庫之系統文字的翻譯方式與舊資料相容。

### Modified Capabilities
（無：`openspec/specs/` 目前沒有已封存的 spec。其他進行中 change 的 spec 中描述網頁行為的情境，功能不變，只是改由新介面實現。）

## Impact

- **前端**：新增 `frontend/`（Vue 3、Naive UI、vue-i18n、vue-router、TypeScript、Vite、Vitest）；刪除 `src/main/resources/static/*.html`。
- **建置**：`pom.xml` 加入 frontend-maven-plugin（安裝 Node、`npm ci`、建置、前端單元測試）與複製建置結果到 JAR 的步驟；CI 不需另外安裝 Node。建置時需能連到 Node 與 npm 套件來源（或設定內部鏡像），執行時不需要網路。
- **後端**：新增 `i18n` 套件（訊息代碼、語系判斷、ResourceBundle 語系檔三份）；約 275 處使用者可見的中文字串改為訊息代碼；驗證方法與自訂例外改為攜帶訊息代碼；例外的 `getMessage()` 改為英文供 log 使用。
- **Web 層**：`AuthFilter` 公開前端外殼路徑、未登入的非 API 請求導向首頁；新增靜態資源快取標頭；`JettyServer` 設定歡迎頁。
- **API**：回應欄位不變，訊息文字依 `Accept-Language`；監控範本與設備回應增加內建範本的顯示名稱欄位（向下相容）。
- **測試**：`test.sh` 改以 `/api/info` 判斷伺服器就緒，並新增英文訊息案例；新增後端語系檔鍵值一致性測試與前端單元測試。
- **相依套件**：前端 npm 套件（建置時）；後端無新增。
