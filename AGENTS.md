# Repository Guidelines

## 專案結構與模組分工
本專案是 Java 21 的 Maven 應用程式，核心程式碼位於 `src/main/java/io/github/chiayi1224/simplesnmp`。依功能分為 `poller`、`trap`、`threshold`、`storage`、`config` 與 `web` 等模組。靜態頁面放在 `src/main/resources/static`，Logback 設定位於 `src/main/resources/logback.xml`。目前尚未建立 `src/test/java`；若新增測試，請依正式程式碼的 package 結構對應放置。

## 建置、測試與開發指令
- `mvn clean compile`：編譯專案。
- `mvn clean package`：打包成可執行 fat JAR。
- `java -jar target/simplesnmp-1.0.0.jar`：啟動本機服務。
- `mvn test`：目前可執行，但因尚未加入測試框架，不會跑出實際測試案例。

執行後 Jetty 預設使用 `8080`，Trap 接收埠為 `1162`，資料持久化使用 SQLite。

## 程式風格與命名慣例
沿用既有 Java 寫法：4 個空白縮排、每檔一個主要類別、package 維持在 `io.github.chiayi1224.simplesnmp` 之下。類別名稱使用 `PascalCase`，方法與欄位使用 `camelCase`，Servlet 類別請採明確命名，例如 `StatusServlet`、`AlertServlet`。依專案既有規範，註解、log 訊息與使用者可見文字請使用正體中文。專案目前未配置 formatter 或 lint，提交前請自行整理 imports 與基本排版。

## 測試指引
目前沒有 JUnit 或其他測試相依套件。若補測試，建議採用 JUnit 5，檔名使用 `*Test`，例如 `ThresholdEngineTest`。在自動化測試建立前，至少執行 `mvn clean package`，並手動驗證受影響的 API、登入流程或 SNMP 輪詢/Trap 行為。

## Commit 與 Pull Request 指引
目前工作目錄未包含 `.git`，無法直接檢視歷史紀錄；請採保守慣例撰寫 commit subject，使用簡短祈使句，例如 `新增 Trap 告警映射`。每個 commit 聚焦單一主題。PR 請附上變更摘要、設定或資料表影響、驗證步驟；若調整 `src/main/resources/static` 內頁面，請附畫面截圖。

## 安全與設定注意事項
不要提交真實設備帳密或已填入資料的 SQLite 資料庫。預設管理者帳號為 `admin/admin`，非展示環境應於首次啟動後立即修改。若變更驗證、埠口、閾值或告警邏輯，請在 PR 中明確說明行為差異。
