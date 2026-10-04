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
```

## 測試與 CI

```bash
# 單元測試（JUnit 5 + Mockito，含 JaCoCo 覆蓋率報告 target/site/jacoco/）
mvn test

# 執行單一測試類別
mvn test -Dtest=ThresholdEngineTest

# 整合測試：啟動 JAR 並以 curl 驗證所有 API（需先 mvn package）
./test.sh --skip-build
```

GitHub Actions（`.github/workflows/ci.yml`）在 push 到 main 與每個 PR 時執行 `mvn -B clean verify` 與 `./test.sh --skip-build`。目前未設定 linting 工具。

## Architecture Overview

這是一個 Java 21 Maven 專案，核心為 SNMP 網路設備監控系統，提供輪詢、Trap 接收、閾值告警與 Web 管理介面。

Maven 座標為 `io.github.chiayi1224:simplesnmp`，Java 根套件為 `io.github.chiayi1224.simplesnmp`。

**主要執行緒與埠口：**
| 元件 | 類別 | 埠口/行為 |
|---|---|---|
| Web Server | `JettyServer` | 8080 (HTTP REST + Static UI) |
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
   - 每台設備可設定 SNMP 版本（`1`／`2c`／`3`）與 v3 認證；舊設備預設 `2c`。設備 API 不回傳 v3 密碼，更新時未帶密碼即沿用舊值。
   - 所有對設備的查詢都透過 `snmp.SnmpSession`（`get()`、`walk()`）：使用自己的 `USM`／`SecurityProtocols`，v3 使用者依設備 engine ID 本地化後加入 USM（同名使用者不同密碼不會互相覆蓋），只發出 GET／GETNEXT／GETBULK。v1 的 GET 遇到 `noSuchName` 會移除該 OID 後重送。
   - 測試可用 `src/test/.../snmp/TestSnmpAgent`（記憶體內的 v1/v2c/v3 agent）做端對端驗證，不需要外部設備。
3. `ThresholdEngine` 使用狀態機（`ThresholdState`）與 hysteresis 防止告警抖動：CPU/記憶體需連續 3 次超標才觸發，磁碟需 2 次；恢復需連續 2 次低於 rearm 值。
   - 每次評估都重新讀取 `threshold_settings`，修改後下次 poll 即生效；狀態以「設備＋指標名稱」為鍵。
   - 自訂指標以 `evaluate(device, metricKey, value)` 評估，可設定方向 `HIGH`（超過才告警）或 `LOW`（低於才告警）；沒有閾值的自訂指標只儲存、不告警。`cpu`／`memory`／`disk` 閾值不可刪除。
4. `TrapReceiver` 依來源 IP 查找設備名稱（啟動時建立 IP→name 對應表），trap OID 透過 `TrapOidMapping` 轉換為告警類型。
   - 同一個 port 同時接收 v1 / v2c / v3 與 INFORM；v1 依 RFC 3584 轉成 v2 格式，INFORM 會回覆 RESPONSE。
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

- `AuthFilter` 保護所有路由，僅放行 `/login.html` 與 `/api/auth/login`。
- Session 超時 1 小時，支援 `must_change` 旗標強制首次登入改密碼。
- 所有 API servlet 繼承 `BaseJsonServlet`，以 Jackson 序列化回應。

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
- 閾值預設值（CPU 90%、記憶體 85%、磁碟 80%）：`ThresholdConfig.java`
- 內建監控範本：`profile/BuiltinProfiles.java`；指標計算類型：`profile/MetricKind.java`
- Trap OID → 告警類型對應：`TrapOidMapping.java`

## Language Convention

程式碼、log 訊息與註解均使用**繁體中文**撰寫，請維持此慣例。

## 授權

本專案採 Apache-2.0（`LICENSE`、`NOTICE`）。新增或升級相依套件時，須同步更新 `THIRD-PARTY-NOTICES.md`；有 NOTICE 的 Apache-2.0 套件要轉載到 `NOTICE`，新的授權類型要把全文放進 `licenses/`。`pom.xml` 的 shade 設定會排除各相依 JAR 互相覆蓋的 LICENSE / NOTICE，改為打包本專案的授權文件。
