# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# 編譯並打包為可執行 fat JAR
mvn clean package

# 僅編譯
mvn clean compile

# 執行應用程式
java -jar target/simplesnmp-1.0.0.jar

# 只改後端時略過前端建置（JAR 不含網頁介面）
mvn package -Dskip.frontend=true

# 前端開發（Vite 開發伺服器 5173，/api 代理到 8080）
cd frontend && npm install && npm run dev
```

`mvn package` 透過 frontend-maven-plugin 下載 Node（`frontend/node/`）、`npm ci`、`npm run build`（`vue-tsc` + `vite build`），結果 `frontend/dist/` 以 resource 打包進 JAR 的 `static/`；`npm run test`（Vitest）於 Maven `test` 階段執行。建置需要網路，執行時完全離線。

**資料目錄：** 資料庫與 keystore 放在 `config.DataDirectory` 決定的目錄（`-Dsnmp.data.dir` → `SNMP_MONITOR_DATA_DIR` → JAR 所在目錄）。`run.sh` 使用 `data/`（不進版控、`mvn clean` 不影響），第一次執行時把舊版 `target/` 的資料搬過去，每次啟動前備份到 `data/backups/`（保留 5 份）。直接 `java -jar target/…` 而不指定資料目錄時，資料庫會建在 `target/`，`mvn clean` 會刪除它。

## 測試與 CI

```bash
# 單元測試（JUnit 5 + Mockito，含 JaCoCo 覆蓋率報告 target/site/jacoco/）
mvn test

# 執行單一測試類別
mvn test -Dtest=ThresholdEngineTest

# 整合測試：啟動 JAR 並以 curl 驗證所有 API（需先 mvn package）
./test.sh --skip-build
```

GitHub Actions（`.github/workflows/ci.yml`）在 push 到 main 與每個 PR 時執行 `mvn -B clean verify`（含前端建置與 Vitest）與 `./test.sh --skip-build`。目前未設定 linting 工具；前端以 `vue-tsc` 做型別檢查。

## Architecture Overview

這是一個 Java 21 Maven 專案，核心為 SNMP 網路設備監控系統，提供輪詢、Trap 接收、閾值告警與 Web 管理介面。

Maven 座標為 `io.github.chiayi1224:simplesnmp`，Java 根套件為 `io.github.chiayi1224.simplesnmp`。

**主要執行緒與埠口：**
| 元件 | 類別 | 埠口/行為 |
|---|---|---|
| Web Server | `JettyServer` | 8080 (HTTP REST + Vue 單頁應用) |
| SNMP Poller | `SnmpPoller` | 每 60 秒輪詢所有啟用設備 |
| Trap Receiver | `TrapReceiver` | 1162 (SNMP v1/v2c/v3 Trap 接收) |
| 持久化 | `MetricStore` | SQLite 檔案 `snmp_monitor.db` |

### 資料流

```
SnmpPoller (60s)  ─────────────────────┐
TrapReceiver (1162) ──> ThresholdEngine ──> MetricStore (SQLite)
JettyServer (8080) ◄──────────────────────────────────────────┘
```

1. `SnmpMonitor`（main）依序初始化 `MetricStore` → `SnmpPoller` → `TrapReceiver` → `JettyServer`，並掛載 shutdown hook。
2. `SnmpPoller` 每次 poll 前都重新從 DB 讀取設備清單與監控範本，因此新增/修改設備或範本無需重啟即生效。
   - 每台設備依套用的監控範本收集指標（見第 6 點）：範本所需 OID 與 sysObjectID 合併一次 GET，sysObjectID 改變時同一週期改用新範本；表格同一子樹只 walk 一次。GET 與計算邏輯在 `profile.ProfileCollector`，Poller 與範本預覽共用。
   - `cpu`／`memory`／`disk` 寫入 `cpu_metrics`／`mem_metrics`／`disk_metrics`，其他指標寫入 `custom_metrics`；單一指標失敗只記警告（每台設備每個指標每小時最多一次），不影響其他指標與設備狀態（GET 完全無回應才是 `SNMP_ERROR`）。
   - 每台設備可設定 SNMP 版本（`1`／`2c`／`3`）與 v3 認證；舊設備預設 `2c`。設備 API（`/api/v1/devices`、`/api/admin/devices`）不回傳 community 與 v3 密碼，只回 `communityConfigured`／`v3AuthConfigured`／`v3PrivConfigured`；更新時未帶或留空即沿用舊值（`DeviceRepository.toRow` 不可再加入機密欄位）。
   - 每台設備可設定磁碟過濾 `disk_filter`（`config.DiskFilter`：逗號分隔的掛載點完整名稱、`*` 萬用字元、不分大小寫，空白代表全部）。過濾在 `ProfileCollector.calculate` 套用於 `disk` 指標，Poller 與範本預覽一致；不符合的磁碟不寫入也不評估閾值，全部不符合時該指標視為失敗。設備對話框的「探測磁碟」呼叫 `POST /api/admin/devices[/{name}]` 帶 `"action":"probe-disks"`（`ProfileCollector.probeDisks`，只列 hrStorage 固定磁碟；既有設備未帶 community／v3 密碼時沿用已儲存的值）。
   - 所有對設備的查詢都透過 `snmp.SnmpSession`（`get()`、`walk()`）：使用自己的 `USM`／`SecurityProtocols`，v3 使用者依設備 engine ID 本地化後加入 USM（同名使用者不同密碼不會互相覆蓋），只發出 GET／GETNEXT／GETBULK。v1 的 GET 遇到 `noSuchName` 會移除該 OID 後重送。
   - 測試可用 `src/test/.../snmp/TestSnmpAgent`（記憶體內的 v1/v2c/v3 agent）做端對端驗證，不需要外部設備。
3. `ThresholdEngine` 以每台設備的閾值（`device_thresholds`，`ThresholdRepository`）評估，沒有預設閾值：查無設定即只儲存、不告警。
   - 告警等級 `Severity`（WARNING／CRITICAL）。`ThresholdConfig` 有 `warning`／`critical`（至少一個）與共用的恢復寬度 `rearmMargin`；cpu／memory／disk 方向固定 HIGH、值 0～100。
   - `ThresholdState` 是多等級狀態機（純邏輯、閾值於每次評估時傳入）：連續 `triggerCount` 次超過才觸發或升級（取期間每次都達到的最低等級），連續 `rearmCount` 次恢復才降級或解除；事件為 `TRIGGERED`／`CHANGED`／`RESOLVED`，都帶等級。設定移除目前等級時直接重新定位。
   - 磁碟以 `disk:<掛載點>` 評估：有該掛載點閾值時使用，否則使用設備的 `disk`。
   - 第一次評估某「設備＋指標」時以 `openAlertSeverity`（最後一筆事件）還原等級，重啟後不重複觸發；記憶體中有等級時每次再與資料庫比對，告警在別處被解除（刪除設備、磁碟過濾變更）時重新同步。閾值被移除時記錄解除。
   - 連線中斷告警：`SnmpPoller` 每次更新狀態後呼叫 `evaluateReachability`，以保留指標名 `device_unreachable`（`ProfileMetric.DEVICE_UNREACHABLE`，不可作為自訂指標或閾值名稱）走同一套狀態機；設定在 `devices.unreachable_severity`／`unreachable_count`，預設關閉。
   - 「未解除」判定一律是每組設備＋指標最後一筆（`MAX(id)`）不是 RESOLVED；保存期限清理保留最後一筆。
   - 舊版全域閾值 `threshold_settings` 由 `ThresholdMigration` 一次性轉成每台既有設備的 Critical 閾值，資料表保留不刪。
4. `TrapReceiver` 依來源 IP 查找設備名稱（啟動時建立 IP→name 對應表），trap OID 透過 `TrapOidMapping` 轉換為告警類型。
   - 同一個 port 同時接收 v1 / v2c / v3 與 INFORM；v1 依 RFC 3584 轉成 v2 格式，INFORM 會回覆 RESPONSE。
   - 每筆 Trap 對應有等級（`trap_oid_mappings.severity`，預設 CRITICAL）；Trap 不經過 `ThresholdEngine`，解除事件沿用解除前的等級。
   - v1 / v2c 的 community 依設定 `trap_communities` 過濾（逗號分隔，`*` 代表全部接受）；v3 使用者由 `reloadV3Users()` 即時增刪。
   - 使用自己的 `USM` / `SecurityProtocols` 實例，不依賴 SNMP4J 的全域 singleton（全域實例預設沒有載入任何加密協定）。
   - `TrapReceiverEndToEndTest` 會在本機實際送出各版本 Trap 驗證，修改接收邏輯後請執行。

5. `DiscoveryService`（`discovery` 套件）在單一背景執行緒執行 OID 掃描：系統資訊 → `VendorRegistry` 辨識廠牌 → `MibProbes` 探測常見 MIB →（選擇性）走訪子樹，結果存於 `device_scans`／`device_scan_results`，每台設備保留 3 次。新增探測項目只需在 `MibProbes.ALL` 加一筆。

6. 監控範本（`profile` 套件，`metric_profiles`／`profile_metrics`）定義每種設備要收集哪些指標：
   - 每個指標有名稱（`cpu`／`memory`／`disk` 或自訂的小寫英數底線）與固定的計算類型 `MetricKind`（`VALUE`、`HUNDRED_MINUS`、`USED_TOTAL_PERCENT`、`USED_FREE_PERCENT`、`TABLE_AVERAGE`、`STORAGE_TABLE`、`UCD_MEMORY`），刻意不支援任意運算式。`disk` 必須是多值的 `STORAGE_TABLE`，其他指標只能是單一值。
   - 套用規則（`ProfileResolver`）：設備手動指定 → sysObjectID 最長前綴相符 → 「HOST-RESOURCES 通用」。
   - 內建範本定義在 `BuiltinProfiles`（Net-SNMP、HOST-RESOURCES、Cisco IOS），啟動時依名稱同步、不可修改或刪除，可複製後編輯。Net-SNMP 範本的計算結果必須與升級前完全相同（`SnmpPollerEndToEndTest` 有回歸測試）。
   - `POST /api/admin/profiles/preview` 對設備試算一次（不儲存、不告警），使用 OID 掃描的 `SnmpSession`。

### Web 層

- Filter 順序：`LocaleFilter`（依 `Accept-Language` 設定本次請求語系）→ `StaticCacheFilter`（`/`、`/index.html` 設 `no-cache`；`/assets/*` 長期快取）→ `AuthFilter`。
- 網頁介面是 `frontend/` 的 Vue 3 + Naive UI 單頁應用（hash 路由），由 `DefaultServlet` 提供 JAR 內的 `static/`。`AuthFilter` 放行前端外殼（`/`、`/index.html`、`/favicon.ico`、`/assets/*`）與 `/api/auth/login`、`/api/info`；其餘非 API 路徑（含舊的 `/login.html`）未登入時導向 `/`，登入、改密碼畫面由前端路由處理。資料一律經需要認證的 API 取得。
- Session 超時 1 小時，支援 `must_change` 旗標強制首次登入改密碼；`must_change` 在 `AuthFilter` 伺服器端強制，該 session 只能呼叫 `/api/auth/me`、`/api/auth/logout` 與修改自己的密碼。
- 帳號管理（`UserServlet`）：新增／修改／刪除帳號與修改他人密碼僅限 `admin`；不能變更自己的角色、停用或刪除自己。admin 重設他人密碼會設定該帳號 `must_change`；自己改密碼則清除。
- 強制踢出：`SessionRevocation`（記憶體內、遞增序號）由 `JettyServer` 建立並共用給 `AuthFilter`、`AuthServlet`、`UserServlet`。重設他人密碼、修改或刪除他人帳號時撤銷該帳號既有 session，下一個請求回 401（頁面導回登入頁）；自己改密碼不撤銷。
- 所有 API servlet 繼承 `BaseJsonServlet`，以 Jackson 序列化回應；錯誤用 `writeError(resp, status, Msg.of(...))`、成功訊息用 `t("代碼", 參數…)`。

### 多語系（i18n）

- 支援 `zh-TW`（預設）、`zh-CN`、`en`。後端 `i18n` 套件：`Msg`（訊息代碼＋參數）、`Messages`（ResourceBundle `i18n/messages*.properties`、`MessageFormat`，單引號寫成 `''`；不回退 JVM 語系）、`LocaleFilter`（請求語系存 ThreadLocal）、`LocalizedException`／`Localized`（例外帶 `Msg`，`getMessage()` 為英文供 log）。
- 背景執行緒（輪詢、Trap、掃描）沒有請求語系：存入資料庫的系統文字用 `StoredText.encode(Msg)`（`@i18n:<key>|<JSON 參數>`），API 回應前以 `StoredText.localize` 翻譯（告警 `message`、稽核 `detail`、掃描 `progress`／`error`／探測結果）。舊資料與 Trap VarBind 原文原樣輸出。
- 內建範本以 `BuiltinProfiles.displayName/displayDescription` 提供翻譯後的 `displayName`；識別用的 `name` 不變。系統設定的 `description` 依 `setting.<key>.description` 翻譯。
- 前端：`frontend/src/locales/zh-TW.ts` 為基準並推導 `MessageSchema`，`zh-CN.ts`、`en.ts` 缺鍵時 `vue-tsc` 失敗；API 用戶端每個請求帶 `Accept-Language`；切換語言時頁面重新掛載以取得新語言的後端文字。
- 測試：`MessagesTest`（三份 properties 鍵與參數一致）、`NoHardcodedUserTextTest`（`src/main/java` 不可有中文字串字面值，log 與註解除外）、前端 `i18n.test.ts`。

### 持久化 (SQLite)

`MetricStore` 是對外統一入口（facade），負責開啟連線、建立 schema 並寫入預設資料，公開方法皆委派給 `storage` 套件內的各 Repository（package-private）：

- `Db`：JDBC 共用工具（參數綁定、ResultSet 關閉、`RowMapper`）
- `SchemaManager`：建表 DDL 與既有 DB 欄位遷移
- `UserRepository`、`DeviceRepository`、`MetricRepository`、`AlertRepository`、`TrapRepository`、`SettingsRepository`、`ThresholdRepository`、`V3UserRepository`、`ApiTokenRepository`、`AuditLogRepository`、`DiscoveryRepository`、`ProfileRepository`：各自負責對應資料表

`poll_oids` 資料表已停用（Poll OID 管理改由監控範本取代），只為回滾保留；升級時由 `PollOidMigration` 依舊設定轉換一次。

新增資料表時：DDL 加到 `SchemaManager`，SQL 寫在新的 Repository，再於 `MetricStore` 新增委派方法。

連線啟用 WAL 模式（`synchronous=NORMAL`、`busy_timeout` 5 秒）：外部讀取不會擋住寫入，但同一時間仍只能有一個寫入者。`maintenance.DataRetentionJob` 每天依設定 `retention_metrics_days`（90，含 `custom_metrics`）、`retention_alerts_days`（365）、`retention_audit_days`（365）清除過期資料，`0` 代表永久保留；告警清除時保留每個設備＋指標最後一筆狀態。時間欄位一律存 `Instant.toString()` 的 ISO-8601 字串，以字串比較時間先後。

**預設帳號：** `admin` / `admin`（首次登入需強制改密碼）

### 設定常數

- SNMP OID 對應：`OidConstants.java`
- 閾值：沒有預設值，每台設備於網頁或 `/api/admin/device-thresholds` 設定（`threshold/ThresholdConfig.java`）
- 內建監控範本：`profile/BuiltinProfiles.java`；指標計算類型：`profile/MetricKind.java`
- Trap OID → 告警類型對應：`TrapOidMapping.java`

## Language Convention

註解、Javadoc 使用**繁體中文**；**log 輸出一律使用英文**，既有的中文 log 在修改該檔案時一併改為英文。使用者可見文字（API 錯誤訊息、告警描述、網頁文字）一律經由訊息代碼：正體中文寫在 `messages.properties`／`locales/zh-TW.ts`（基準），並同步補上簡體中文與英文。

## 授權

本專案採 Apache-2.0（`LICENSE`、`NOTICE`）。新增或升級相依套件時，須同步更新 `THIRD-PARTY-NOTICES.md`；有 NOTICE 的 Apache-2.0 套件要轉載到 `NOTICE`，新的授權類型要把全文放進 `licenses/`。`pom.xml` 的 shade 設定會排除各相依 JAR 互相覆蓋的 LICENSE / NOTICE，改為打包本專案的授權文件。
