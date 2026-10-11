# Design

## Context

動機見 proposal.md，需求見 `specs/web-ui` 與 `specs/localization`。現況：

- 前端是 `src/main/resources/static/` 下三個手寫 HTML，由 `JettyServer` 的 `DefaultServlet` 直接提供，沒有建置流程；`AuthFilter` 對所有非 API 路徑要求登入，未登入導向 `/login.html`、需改密碼導向 `/change-password.html`。
- 後端約 275 處使用者可見的中文字串，分布在 Servlet、驗證方法（`DeviceConfig`、`ThresholdConfig`、`ProfileMetric`、`MetricKind`、`MetricProfile`）、自訂例外（`SnmpException`、`MetricException`、`DiscoveryService.DeviceNotFoundException`）與存入資料庫的文字（`ThresholdEngine`／`ThresholdState` 的告警描述、`AuthFilter` 的稽核說明、`DiscoveryService`／`MibProbes` 的掃描進度、錯誤與取樣、`SettingsRepository` 的設定說明、`BuiltinProfiles` 的名稱與說明）。
- 依專案規範，log 一律英文，使用者可見文字依語系。
- Web 伺服器共用一個 Jetty `ServletContextHandler`，`test.sh` 以 `/login.html` 判斷伺服器就緒。

## Goals / Non-Goals

**Goals:**
- 功能與升級前網頁一致，畫面改用 Naive UI 元件。
- 執行期完全離線；建置仍是 `mvn package` 一條指令。
- 訊息代碼在前端與後端各自有編譯期或測試期的一致性檢查，新增文字漏翻時建置失敗。

**Non-Goals:**
- 不翻譯使用者輸入的資料（設備名稱、自訂範本、Trap 對應說明、預設 Trap 對應的種子說明）。
- 不轉換升級前已存入資料庫的舊文字。
- 不改變任何 API 的路徑與欄位語意（只新增顯示用欄位）。
- 不做深色主題、行動版專屬版面、E2E 測試框架進 CI（以瀏覽器實測取代，見 tasks）。
- 建置期離線（npm 套件與 Node 下載）不在範圍；可透過鏡像設定支援，見 Risks。

## Decisions

### D1. 前端專案結構與技術
- `frontend/`：Vue 3（`<script setup>` + TypeScript）、Naive UI、vue-i18n（Composition API 模式）、vue-router（hash 模式）、Vite、Vitest。不引入 Pinia——全域狀態只有登入者與語言，用 `reactive` 模組即可。
- 圖示用 `@vicons/ionicons5`（npm 套件，打包進資源）；字型使用系統字型堆疊，不載入任何網路字型。
- 目錄：`src/api/`（`client.ts` 與各資源）、`src/i18n/`、`src/locales/`、`src/views/`（每個功能頁一個）、`src/components/`（設備對話框、等級標籤、使用率條、語言切換等共用元件）、`src/router.ts`、`src/auth.ts`。
- **hash 模式而非 history 模式**：Jetty 不需要為前端路由做 fallback，也不會和 `/api/*` 路徑衝突；網址帶 `#/` 對管理介面無影響。

### D2. Maven 整合建置
- `frontend-maven-plugin`：`install-node-and-npm`（Node 22 LTS，裝在 `frontend/node/`）、`npm ci`、`npm run build`（`vue-tsc --noEmit && vite build`）於 `generate-resources`；`npm run test`（Vitest）於 `test` 階段，`-DskipTests` 時略過。
- `maven-resources-plugin` 於 `process-resources` 把 `frontend/dist/` 複製到 `target/classes/static/`。
- 屬性 `skip.frontend=true` 可略過整個前端建置（只改後端時加速），此時 JAR 不含新前端。
- Node 與 npm 來源可用 `-Dnode.download.root=…`、`-Dnpm.registry=…` 指向內部鏡像。
- 開發：`cd frontend && npm run dev`，Vite 代理 `/api` 到 `http://localhost:8080`。
- `.gitignore` 加入 `frontend/node/`、`frontend/node_modules/`、`frontend/dist/`；`package-lock.json` 提交以固定版本。

### D3. 前端 API 用戶端
- `client.ts` 包裝 `fetch`：一律帶 `Accept-Language: <目前語言>` 與 `Content-Type: application/json`；非 2xx 時讀取 `{error}` 拋出 `ApiError(status, message)`；401 時清除登入狀態並導向 `#/login?redirect=<原路由>`；403 且為「需先改密碼」時導向 `#/change-password`。
- 錯誤訊息由後端依語言產生，前端直接顯示 `ApiError.message`；網路錯誤顯示前端自己的翻譯訊息。

### D4. 路由與認證流程
- 路由：`/login`、`/change-password`、以及版面下的 `/status`（預設）、`/devices`、`/discovery`、`/profiles`、`/traps`、`/thresholds`、`/alerts`、`/users`、`/tokens`、`/audit`、`/settings`。
- 全域前置守衛：未取得登入者時呼叫 `GET /api/auth/me`；失敗 → `/login?redirect=`；`mustChange` → 只能進 `/change-password`。
- 後端仍是權限的最終把關（`AuthFilter` 的 must_change 規則不變）。

### D5. 後端靜態路徑與快取
- `AuthFilter` 公開路徑改為：`/`、`/index.html`、`/favicon.ico`、`/assets/*`、`/api/auth/login`、`/api/info`。未登入的其他非 API 路徑（含舊的 `/login.html`）導向 `/`；需改密碼的 session 存取非 API 路徑同樣導向 `/`（由前端顯示改密碼畫面）。
- 新增 `StaticCacheFilter`：`/`、`/index.html` 設 `Cache-Control: no-cache`；`/assets/*`（Vite 產生帶雜湊的檔名）設 `public, max-age=31536000, immutable`。
- `JettyServer` 設定歡迎頁 `index.html`。

### D6. 前端語系
- 語系檔 `src/locales/zh-TW.ts`（基準）、`zh-CN.ts`、`en.ts`，以 TypeScript 物件撰寫；`zh-CN`、`en` 宣告為 `MessageSchema`（由 zh-TW 推導的型別），缺少或多出鍵時 `vue-tsc` 編譯失敗。另有 Vitest 測試比對三份的鍵集合。
- 語言決定（spec「介面語言的決定與切換」）：`localStorage` 的 `snmp-monitor.locale` → `navigator.languages` 依序比對（`zh-TW`／`zh-HK`／`zh-MO`／`zh-Hant*` → `zh-TW`；`zh-CN`／`zh-SG`／`zh-Hans*`／單純 `zh` → `zh-CN`；`en*` → `en`）→ `zh-TW`。
  - 單純 `zh` 對應簡體中文：瀏覽器送出單純 `zh` 時，絕大多數是簡體環境。
- `setLocale()` 同時更新 vue-i18n、Naive UI 的 `locale`／`dateLocale`（`zhTW`／`zhCN`／`enUS` 與對應日期語系）、`<html lang>` 與 API 用戶端的 `Accept-Language`。
- 日期時間以 `Intl.DateTimeFormat(locale)` 格式化；後端時間一律為 ISO-8601 UTC 字串。

### D7. 後端訊息代碼
- 新增 `i18n` 套件：
  - `Msg`：訊息代碼與參數的不可變值物件（`record Msg(String key, Object... args)`）。
  - `Messages`：以 `ResourceBundle`（`i18n/messages*.properties`，UTF-8）與 `MessageFormat` 產生文字；`resolve(Accept-Language)` 依 D6 相同的對應規則選出 `zh_TW`／`zh_CN`／`en`，預設 `zh_TW`；使用不回退到 JVM 預設語系的 Control，避免伺服器 OS 語系影響結果。缺鍵時回退正體中文，再缺則回傳代碼並記錄 warn log。
  - `LocalizedException`：帶 `Msg` 的例外基底；`getMessage()` 回傳英文（供 log 使用），Servlet 以請求語系另行產生使用者訊息。`SnmpException`、`MetricException`、`DeviceNotFoundException`、`UserRepository.LastActiveUserException` 改為繼承或攜帶 `Msg`。
  - 種子資料（系統設定說明、內建範本說明、預設 Trap 對應說明）改由語系檔的正體中文產生後寫入資料庫，程式中不再有中文字串；顯示時設定說明與內建範本另依請求語系翻譯（D9）。
- 基底檔 `messages.properties` 為正體中文，另有 `messages_zh_CN.properties`、`messages_en.properties`。單元測試確認三份鍵集合一致，且每則訊息的 `MessageFormat` 參數數量一致。
- 驗證方法（`validate()`）改回傳 `Msg`（合法時 null），呼叫端以請求語系格式化。
- 請求語系由 `LocaleFilter`（排在所有 Filter 之前）依 `Accept-Language` 存入 ThreadLocal，請求結束清除；`Messages.get(Msg)` 預設使用它。如此 `BaseJsonServlet` 只需新增 `writeError(resp, status, Msg)` 與 `t(key, args)`，既有的 `requireText(body, field, resp)` 等方法不必多傳 `req`。`writeError(resp, status, String)` 保留給已在別處格式化的文字。`AuthFilter` 的錯誤回應同樣依請求語系。背景執行緒（輪詢、Trap、掃描）沒有請求語系，產生的文字一律以 D8 的編碼保存。
- 帶訊息代碼的例外共用 `Localized` 介面（`SnmpException` 須繼承 `IOException`，無法共用基底類別）。
- 代碼命名：`<領域>.<情境>`，例如 `device.not_found`、`threshold.warning_must_be_lower`、`alert.unreachable.snmp`。

### D8. 存入資料庫之系統文字
- 系統產生、需要翻譯的文字以編碼字串保存：`@i18n:<key>` 或帶參數時 `@i18n:<key>|<JSON 陣列>`（`StoredText.encode(Msg)` / `decode`）。不改資料表結構，舊資料（不以 `@i18n:` 開頭）原樣顯示。
- 讀取端在回應前轉換：告警（`/api/v1/alerts` 的 `message`）、稽核日誌（`detail`）、OID 掃描（`progress`、`error`、探測的 `name`、`samples`、`error`）。
- 告警描述的參數只有簡單值；MIB 探測取樣改為每筆一個 `Msg`（例如 `probe.sample.processor` 帶處理器編號與負載），由讀取端組成文字。
- **替代方案：新增 `message_key`／`message_args` 欄位**：語意更明確，但要改三張資料表的結構與所有查詢；編碼字串只在寫入與讀取的單一函式中處理，影響最小。

### D9. 內建項目的顯示名稱
- 監控範本回應新增 `displayName`、`displayDescription`：內建範本依 `profile.builtin.<key>.name` 翻譯（`net_snmp`、`host_resources`、`cisco_ios`，依內建名稱常數對應），自訂範本等於 `name`。設備回應新增 `appliedProfileDisplayName`。識別用的 `name` 不變。
- 系統設定回應的 `description` 依 `setting.<key>.description` 翻譯，查無代碼時用資料庫原值。
- 複製範本時產生的「（複本）」後綴依請求語系。

### D10. 頁面實作對照
- 各頁以升級前 `index.html` 的對應區塊為行為依據逐一移植（API 呼叫、欄位、驗證、確認提示），表格用 `NDataTable`、表單用 `NForm`、對話框用 `NModal`、提示用 `useMessage`／`useDialog`。
- 所有伺服器文字以 Vue 範本插值輸出（自動跳脫），禁止 `v-html`。
- 狀態頁每 30 秒自動更新，離開頁面時停止。

## Risks / Trade-offs

- [建置需要網路下載 Node 與 npm 套件] → 支援以 Maven 屬性指定內部鏡像；`package-lock.json` 固定版本；執行期完全離線。README 記載離線建置的設定方式。
- [CI 時間增加（安裝套件與建置前端）] → 以 Actions cache 快取 `~/.npm` 與 Maven 本機儲存庫中的 Node。
- [後端 275 處字串一次轉換容易漏改] → 語系檔一致性測試 + 以掃描程式檢查 `src/main/java` 中殘留的中文字串（排除註解），列入整合檢查。
- [`@i18n:` 編碼字串與真實文字衝突] → 只有系統產生的欄位會被解碼；Trap VarBind 原文格式為 `oid=value`，不會以 `@i18n:` 開頭。
- [移除舊頁面後外部書籤失效] → 舊路徑一律導向 `/`。
- [前端外殼公開] → 外殼不含資料；API 的認證、must_change 與 Token 限制完全不變。

## Migration Plan

1. 部署新版 JAR：既有資料不需轉換，舊告警與稽核文字原樣顯示，新產生的記錄可依語言顯示。
2. 使用者重新整理即取得新介面（`index.html` 不快取）。
3. 回滾：舊版 JAR 會看到新寫入的 `@i18n:` 編碼字串原樣顯示（可讀但不美觀），其餘資料完全相容。
