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
2. `SnmpPoller` 每次 poll 前都重新從 DB 讀取設備清單，因此新增/修改設備無需重啟即生效。
3. `ThresholdEngine` 使用狀態機（`ThresholdState`）與 hysteresis 防止告警抖動：CPU/記憶體需連續 3 次超標才觸發，磁碟需 2 次；恢復需連續 2 次低於 rearm 值。
4. `TrapReceiver` 依來源 IP 查找設備名稱（啟動時建立 IP→name 對應表），trap OID 透過 `TrapOidMapping` 轉換為告警類型。

### Web 層

- `AuthFilter` 保護所有路由，僅放行 `/login.html` 與 `/api/auth/login`。
- Session 超時 1 小時，支援 `must_change` 旗標強制首次登入改密碼。
- 所有 API servlet 繼承 `BaseJsonServlet`，以 Jackson 序列化回應。

### 持久化 (SQLite)

`MetricStore` 是對外統一入口（facade），負責開啟連線、建立 schema 並寫入預設資料，公開方法皆委派給 `storage` 套件內的各 Repository（package-private）：

- `Db`：JDBC 共用工具（參數綁定、ResultSet 關閉、`RowMapper`）
- `SchemaManager`：建表 DDL 與既有 DB 欄位遷移
- `UserRepository`、`DeviceRepository`、`MetricRepository`、`AlertRepository`、`TrapRepository`、`PollOidRepository`、`SettingsRepository`、`ThresholdRepository`、`V3UserRepository`、`ApiTokenRepository`、`AuditLogRepository`：各自負責對應資料表

新增資料表時：DDL 加到 `SchemaManager`，SQL 寫在新的 Repository，再於 `MetricStore` 新增委派方法。

**預設帳號：** `admin` / `admin`（首次登入需強制改密碼）

### 設定常數

- SNMP OID 對應：`OidConstants.java`
- 閾值預設值（CPU 90%、記憶體 85%、磁碟 80%）：`ThresholdConfig.java`
- Trap OID → 告警類型對應：`TrapOidMapping.java`

## Language Convention

程式碼、log 訊息與註解均使用**繁體中文**撰寫，請維持此慣例。

## 授權

本專案採 Apache-2.0（`LICENSE`、`NOTICE`）。新增或升級相依套件時，須同步更新 `THIRD-PARTY-NOTICES.md`；有 NOTICE 的 Apache-2.0 套件要轉載到 `NOTICE`，新的授權類型要把全文放進 `licenses/`。`pom.xml` 的 shade 設定會排除各相依 JAR 互相覆蓋的 LICENSE / NOTICE，改為打包本專案的授權文件。
