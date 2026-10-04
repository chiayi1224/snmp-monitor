# SNMP Monitor

以 Java + SNMP4J 實作的輕量級 SNMP 監控系統，適合監控 5 台以下的 Linux Server。

## 功能

- **Polling**：定時取得 CPU 使用率、Memory 使用率、磁碟各掛載點容量；每台設備可設定 SNMP v1 / v2c / v3
- **Trap 接收**：同一個 port 同時支援 SNMPv1 / v2c / v3（USM）與 INFORM；v1 依 RFC 3584 轉換
- **OID 探索**：掃描設備的系統資訊、辨識廠牌、探測常見 MIB 支援，並可完整走訪 OID 子樹
- **Trap 對應**：網頁管理 Trap OID 對應，未定義 Trap 一鍵轉成告警規則
- **閾值告警**：連續超過閾值才觸發，連續恢復才解除，不重複寫入（參考 OpenNMS 設計）
- **Web 管理介面**：設備新增/編輯/停用，修改後下一個 poll 週期立即生效
- **帳號管理**：Session 認證、BCrypt 密碼、首次登入強制改密碼
- **SQLite 儲存**：無需額外資料庫

## 技術棧

| 層 | 技術 |
|---|---|
| SNMP | SNMP4J 3.7.x |
| Web Server | Jetty 11 (embedded) |
| 資料庫 | SQLite (sqlite-jdbc) |
| 密碼 | jBCrypt |
| JSON | Jackson |
| Log | SLF4J + Logback |

## 快速開始

### 需求

- Java 11+
- Maven 3.6+
- Linux Server 上已安裝並啟動 `snmpd`（net-snmp）

### 建置與執行

```bash
git clone https://github.com/your-username/snmp-monitor.git
cd snmp-monitor
mvn package -q
java -jar target/simplesnmp-1.0.0.jar
```

### 第一次使用

1. 開啟瀏覽器：`http://localhost:8080`
2. 用預設帳號登入：`admin / admin`
3. 系統強制要求修改密碼（規則：8字元以上、含大寫、含數字）
4. 到「設備管理」頁面新增要監控的 Linux Server

## 設定說明

### 受監控設備的 snmpd.conf

```
# SNMPv2c
rocommunity public  192.168.1.100   # 監控主機 IP

# 允許 hrStorageTable（磁碟）查詢
view   all  included .1
access notConfigGroup "" any noauth exact all none none
```

### Trap 接收（Port 說明）

預設監聽 UDP **1162**（不需要 root）。

若要收標準 162：
```bash
iptables -t nat -A PREROUTING -p udp --dport 162 -j REDIRECT --to-port 1162
```

### SNMPv3 Trap 設定

以 API 管理 Trap 用的 v3 使用者（`/api/admin/v3-users`），新增或刪除後立即生效。
認證協定支援 MD5、SHA、SHA-224/256/384/512；加密協定支援 DES、AES-128/192/256。

v1 / v2c 的 community 可用設定 `trap_communities` 限制（逗號分隔，預設 `*` 代表全部接受）：
```bash
curl -X PUT -H 'Content-Type: application/json' -d '{"value":"public,monitor"}' \
  http://localhost:8080/api/admin/settings/trap_communities
```

設備端 `snmpd.conf`：
```
createUser trapUser SHA "myAuthPass" AES "myPrivPass"
trap2sink 192.168.1.100:1162 trapUser
```

### Trap OID 對應

在網頁「Trap 對應」分頁管理。收到但沒有對應規則的 Trap 會列在「未定義 Trap」，
可直接按「建立對應」轉成告警規則；來源 IP 不在設備清單中的會列在「未登錄來源」，可一鍵新增為設備。

> 首次啟動寫入的三組預設對應（cpu / memory / disk）使用佔位 OID，需改成設備實際的 Trap OID 才會生效。

### OID 探索

在網頁「OID 探索」分頁選擇設備並掃描：會顯示 sysDescr、sysObjectID、廠牌，
探測 HOST-RESOURCES-MIB、UCD-SNMP-MIB、IF-MIB、Cisco CPU／記憶體池是否支援並列出取樣值。
勾選「完整走訪」會收集 MIB-2 與廠牌子樹（或自訂子樹）的所有 OID，單次最多 10,000 筆、120 秒，
每台設備保留最近 3 次結果。掃描只發出讀取請求（GET / GETNEXT / GETBULK）。

### 閾值設定

修改 `ThresholdConfig.java`：

```java
public static ThresholdConfig cpu() {
    return new Builder("cpu")
        .threshold(90.0)    // 超過 90% 才告警
        .rearmValue(80.0)   // 低於 80% 才恢復
        .triggerCount(3)    // 連續 3 次才觸發
        .rearmCount(2)      // 連續 2 次才恢復
        .build();
}
```

### 資料保存期限與備份

系統每天清除超過保留天數的資料（啟動 5 分鐘後首次執行）。天數可在網頁「系統設定」分頁調整（0–36500 天），修改後下一次執行即生效：

| 設定 | 預設 | 清除範圍 |
|---|---|---|
| `retention_metrics_days` | 90 | CPU / 記憶體 / 磁碟指標 |
| `retention_alerts_days` | 365 | 告警記錄（每個設備＋指標最後一筆狀態一律保留，避免未恢復的告警被當成已結束） |
| `retention_audit_days` | 365 | 稽核日誌 |

設為 `0` 代表永久保留。刪除後的空間會被重複利用，但 DB 檔案不會縮小。

資料庫使用 SQLite **WAL 模式**，執行中會多出 `snmp_monitor.db-wal`、`snmp_monitor.db-shm` 兩個檔案。
執行中備份請用 `sqlite3 snmp_monitor.db ".backup backup.db"`；若直接複製檔案，必須在程式停止後進行，
或連同 `-wal` 檔一起複製，否則可能遺漏最近寫入的資料。

## 專案結構

```
src/main/java/io/github/chiayi1224/simplesnmp/
├── SnmpMonitor.java           主程式入口
├── config/
│   ├── DeviceConfig.java      設備設定 model
│   ├── OidConstants.java      OID 常數（CPU/Mem/Disk）
│   ├── TrapOidMapping.java    Trap OID 對應（待填入）
│   └── V3UserConfig.java      SNMPv3 user 設定
├── model/
│   └── DiskInfo.java          磁碟掛載點 model
├── poller/
│   └── SnmpPoller.java        定時 GET + WALK
├── storage/
│   └── MetricStore.java       SQLite 讀寫
├── threshold/
│   ├── ThresholdConfig.java   閾值參數
│   ├── ThresholdState.java    狀態機
│   ├── ThresholdEngine.java   統一管理
│   └── AlertEvent.java        事件 model
├── trap/
│   └── TrapReceiver.java      v1/v2c/v3 Trap 接收
└── web/
    ├── AuthFilter.java        Session 認證 Filter
    ├── JettyServer.java       Jetty 啟動
    └── servlet/
        ├── AuthServlet.java   登入/登出 API
        ├── UserServlet.java   帳號管理 API
        ├── DeviceServlet.java 設備管理 API
        ├── StatusServlet.java 狀態查詢 API
        ├── AlertServlet.java  告警查詢 API
        └── BaseJsonServlet.java JSON 共用基底
```

## API 端點

`/api/admin/*` 需登入（Session），`/api/v1/*` 可用 Session 或 Bearer Token。

| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/auth/login`、`/api/auth/logout` | 登入 / 登出 |
| GET | `/api/auth/me` | 目前登入帳號 |
| POST | `/api/v1/status` | 各設備最新狀態 |
| POST | `/api/v1/devices`、`/api/v1/devices/{name}` | 設備清單 / 單台設備（不含 v3 密碼） |
| POST | `/api/v1/alerts` | 告警記錄 |
| GET / POST | `/api/admin/devices` | 設備清單 / 新增（含 `snmpVersion` 與 v3 欄位） |
| GET / POST | `/api/admin/devices/{name}` | 單台設備 / 更新（body 為 `{"action":"delete"}` 時刪除） |
| GET / POST | `/api/admin/users` | 帳號清單 / 新增 |
| PUT / DELETE | `/api/admin/users/{id}` | 更新 / 刪除帳號 |
| PUT | `/api/admin/users/{id}/password` | 修改密碼 |
| GET / POST | `/api/admin/trap-mappings` | Trap OID 對應清單 / 新增（觸發 OID 重複回 409） |
| PUT / DELETE | `/api/admin/trap-mappings/{id}` | 更新 / 刪除對應 |
| GET | `/api/admin/unknown-traps` | 未定義 Trap 與未登錄來源 |
| DELETE | `/api/admin/unknown-traps/oids/{id}`、`/sources/{id}` | 刪除單筆未定義記錄 |
| POST | `/api/admin/discovery/scans` | 啟動 OID 掃描（202；設備已有掃描回 409） |
| GET | `/api/admin/discovery/scans?device=` | 掃描紀錄 |
| GET | `/api/admin/discovery/scans/{id}` | 掃描結果（系統資訊與 MIB 探測） |
| GET | `/api/admin/discovery/scans/{id}/oids?prefix=&q=&offset=&limit=` | OID 明細分頁 |
| GET / POST / PUT / DELETE | `/api/admin/v3-users` | Trap 用 SNMPv3 使用者 |
| GET / PUT | `/api/admin/settings`、`/api/admin/settings/{key}` | 系統設定 |
| GET / PUT | `/api/admin/thresholds`、`/api/admin/thresholds/{id}` | 閾值設定 |
| GET / POST / PUT / DELETE | `/api/admin/poll-oids` | Poll OID |
| GET / POST | `/api/admin/tokens`、`/api/admin/tokens/{id}` | API Token（更新或 `{"action":"delete"}` 刪除皆用 POST） |
| GET | `/api/admin/audit-logs` | 稽核日誌 |

## License

本專案採用 [Apache License 2.0](LICENSE) 授權，版權宣告見 [NOTICE](NOTICE)。

使用的第三方套件及其授權、版權宣告，請見 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)；授權全文放在 [licenses/](licenses/) 目錄。打包後的 JAR 會在 `META-INF/` 內附上同一份文件。
