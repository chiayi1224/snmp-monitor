# Repository Guidelines

## 專案結構與模組分工
本專案是 Java 21 的 Maven 應用程式，核心程式碼位於 `src/main/java/io/github/chiayi1224/simplesnmp`。依功能分為 `poller`、`trap`、`threshold`、`storage`、`config`、`i18n` 與 `web` 等模組。網頁介面是 `frontend/`（Vue 3 + Naive UI + TypeScript，Vite 建置，由 Maven 打包進 JAR 的 `static/`）；後端語系檔位於 `src/main/resources/i18n/`，Logback 設定位於 `src/main/resources/logback.xml`。測試放在 `src/test/java` 下對應的 package；前端測試與程式放在一起（`*.test.ts`）。

## 建置、測試與開發指令
- `mvn clean compile`：編譯專案。
- `mvn clean package`：打包成可執行 fat JAR（含前端建置；只改後端可加 `-Dskip.frontend=true`）。注意 `clean` 會刪除 `target/snmp_monitor.db`。
- `cd frontend && npm run dev`：前端開發伺服器（5173，`/api` 代理到 8080）；`npm run test`：前端單元測試。
- `java -jar target/simplesnmp-1.0.0.jar`：啟動本機服務。
- `mvn test`：執行 JUnit 5 單元測試；`./test.sh --skip-build`：API 整合測試。

執行後 Jetty 預設使用 `8080`，Trap 接收埠為 `1162`，資料持久化使用 SQLite。

## 程式風格與命名慣例
沿用既有 Java 寫法：4 個空白縮排、每檔一個主要類別、package 維持在 `io.github.chiayi1224.simplesnmp` 之下。類別名稱使用 `PascalCase`，方法與欄位使用 `camelCase`，Servlet 類別請採明確命名，例如 `StatusServlet`、`DeviceServlet`。註解、Javadoc 使用正體中文；log 輸出一律使用英文（既有的中文 log 在修改該檔案時一併改為英文）。使用者可見文字（API 錯誤訊息、告警描述、網頁文字）一律經由訊息代碼，正體中文為基準（`src/main/resources/i18n/messages.properties`、`frontend/src/locales/zh-TW.ts`），並同步補上簡體中文與英文。專案目前未配置 formatter 或 lint，提交前請自行整理 imports 與基本排版。

## 測試指引
測試採用 JUnit 5 + Mockito，放在 `src/test/java` 下對應的 package，檔名使用 `*Test`，例如 `ThresholdEngineTest`。提交前請執行 `mvn clean package` 與 `./test.sh --skip-build`；GitHub Actions 會在每個 PR 自動執行相同檢查。

## Commit 與 Pull Request 指引
目前工作目錄未包含 `.git`，無法直接檢視歷史紀錄；請採保守慣例撰寫 commit subject，使用簡短祈使句，例如 `新增 Trap 告警映射`。每個 commit 聚焦單一主題。PR 請附上變更摘要、設定或資料表影響、驗證步驟；若調整 `frontend/` 的網頁介面，請附畫面截圖（涉及文字時附三種語言）。

## 安全與設定注意事項
不要提交真實設備帳密或已填入資料的 SQLite 資料庫。預設管理者帳號為 `admin/admin`，非展示環境應於首次啟動後立即修改。若變更驗證、埠口、閾值或告警邏輯，請在 PR 中明確說明行為差異。
