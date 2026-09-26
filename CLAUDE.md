# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# 編譯並打包為可執行 fat JAR
mvn clean package

# 僅編譯
mvn clean compile

# 執行應用程式
java -jar target/snmp-monitor-1.0-SNAPSHOT.jar
```

目前尚未設定測試框架（無 JUnit 依賴）與 linting 工具。

## Architecture Overview

這是一個 Java 11+ Maven 專案，核心為 SNMP 網路設備監控系統，提供輪詢、Trap 接收、閾值告警與 Web 管理介面。

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

`MetricStore` 負責建立 schema（首次啟動自動建表）、BCrypt 密碼驗證，以及儲存 `cpu_metrics`、`mem_metrics`、`disk_metrics`、`alert_events`。

**預設帳號：** `admin` / `admin`（首次登入需強制改密碼）

### 設定常數

- SNMP OID 對應：`OidConstants.java`
- 閾值預設值（CPU 90%、記憶體 85%、磁碟 80%）：`ThresholdConfig.java`
- Trap OID → 告警類型對應：`TrapOidMapping.java`

## Language Convention

程式碼、log 訊息與註解均使用**繁體中文**撰寫，請維持此慣例。
