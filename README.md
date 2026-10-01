# SNMP Monitor

以 Java + SNMP4J 實作的輕量級 SNMP 監控系統，適合監控 5 台以下的 Linux Server。

## 功能

- **Polling**：定時取得 CPU 使用率、Memory 使用率、磁碟各掛載點容量
- **Trap 接收**：同時支援 SNMPv1 / v2c / v3（USM）
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

在 `SnmpMonitor.java` 修改：

```java
List<V3UserConfig> v3Users = List.of(
    V3UserConfig.authPriv("trapUser", "myAuthPass", "myPrivPass")
);
```

設備端 `snmpd.conf`：
```
createUser trapUser SHA "myAuthPass" AES "myPrivPass"
trap2sink 192.168.1.100:1162 trapUser
```

### Trap OID 對應

確認設備的 Trap OID 後，修改 `TrapOidMapping.java`：

```java
private static final String CPU_TRIGGERED  = "1.3.6.1.4.1.xxxx.cpu.triggered";
private static final String CPU_RESOLVED   = "1.3.6.1.4.1.xxxx.cpu.resolved";
// Memory、Disk 同上
```

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

| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/auth/login` | 登入 |
| POST | `/api/auth/logout` | 登出 |
| GET | `/api/auth/me` | 目前登入帳號 |
| GET | `/api/status` | 各設備最新狀態 |
| GET/POST | `/api/devices` | 設備清單 / 新增 |
| PUT/DELETE | `/api/devices/{id}` | 更新 / 刪除設備 |
| GET | `/api/alerts` | 告警記錄 |
| GET/POST | `/api/users` | 帳號清單 / 新增 |
| PUT | `/api/users/{id}` | 更新帳號 |
| PUT | `/api/users/{id}/password` | 修改密碼 |
| DELETE | `/api/users/{id}` | 刪除帳號 |

## License

本專案採用 [Apache License 2.0](LICENSE) 授權，版權宣告見 [NOTICE](NOTICE)。

使用的第三方套件及其授權、版權宣告，請見 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)；授權全文放在 [licenses/](licenses/) 目錄。打包後的 JAR 會在 `META-INF/` 內附上同一份文件。
