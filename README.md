# SNMP Monitor

以 Java + SNMP4J 實作的輕量級 SNMP 監控系統，適合監控 5 台以下的 Linux Server。

## 功能

- **Polling**：定時取得 CPU 使用率、Memory 使用率、磁碟各掛載點容量；每台設備可設定 SNMP v1 / v2c / v3
- **Trap 接收**：同一個 port 同時支援 SNMPv1 / v2c / v3（USM）與 INFORM；v1 依 RFC 3584 轉換
- **OID 探索**：掃描設備的系統資訊、辨識廠牌、探測常見 MIB 支援，並可完整走訪 OID 子樹
- **Trap 對應**：網頁管理 Trap OID 對應，未定義 Trap 一鍵轉成告警規則
- **閾值告警**：每台設備各自設定 Warning／Critical 兩段閾值，連續超過才觸發、可在等級間升降、連續恢復才解除；沒有預設閾值（參考 OpenNMS 設計）
- **連線中斷告警**：每台設備可開啟，設備 OFFLINE／SNMP_ERROR 時告警、恢復後自動解除
- **Trap 告警等級**：每筆 Trap 對應可指定 Warning 或 Critical
- **Web 管理介面**：Vue 3 + Naive UI 單頁應用，設備新增/編輯/停用，修改後下一個 poll 週期立即生效；所有資源打包在 JAR 內，**執行時完全離線**，不連任何外部網址
- **多語系**：正體中文（預設）、簡體中文、英文；介面、API 訊息與系統產生的告警描述都依使用者語言顯示
- **帳號管理**：Session 認證、BCrypt 密碼、首次登入強制改密碼
- **SQLite 儲存**：無需額外資料庫

## 技術棧

| 層 | 技術 |
|---|---|
| SNMP | SNMP4J 3.7.x |
| Web Server | Jetty 11 (embedded) |
| 網頁介面 | Vue 3、Naive UI、vue-i18n、vue-router（TypeScript，Vite 建置） |
| 資料庫 | SQLite (sqlite-jdbc) |
| 密碼 | jBCrypt |
| JSON | Jackson |
| Log | SLF4J + Logback |

## 快速開始

### 需求

- Java 21+
- Maven 3.6+
- 建置時需要網路：Maven 會自動下載 Node.js 並安裝前端套件（不必自行安裝 Node）。執行時不需要網路。
- Linux Server 上已安裝並啟動 `snmpd`（net-snmp）

### 建置與執行

```bash
git clone https://github.com/your-username/snmp-monitor.git
cd snmp-monitor
mvn package -q
java -jar target/simplesnmp-1.0.0.jar
```

`mvn package` 會一併編譯網頁介面（`frontend/`：下載 Node、`npm ci`、`vite build`），把編譯好的 JS／CSS 打包進 JAR 的 `static/`。部署的機器只需要 Java。

**離線環境部署：** 在可以上網的機器執行 `mvn package`，再把 `target/simplesnmp-1.0.0.jar` 複製到離線環境執行即可；網頁介面所需的程式、樣式、圖示與語系檔都在 JAR 內。若建置機只能連內部鏡像，可加 `-Dnode.download.root=<Node 下載鏡像>/ -Dnpm.registry=<npm 鏡像>/`。

只修改後端時，可加 `-Dskip.frontend=true` 略過前端建置以加快速度（此時產生的 JAR 不含網頁介面）。

### 網頁介面開發

```bash
java -jar target/simplesnmp-1.0.0.jar   # 先啟動後端（8080）
cd frontend
npm install
npm run dev     # http://localhost:5173，/api 自動轉給 8080
npm run test    # Vitest 單元測試（語系一致性、API 用戶端、離線檢查）
npm run build   # 型別檢查 + 建置到 frontend/dist
```

`frontend/src/views/` 是各功能頁，`components/` 是共用元件與對話框，`api/client.ts` 包裝所有 API 請求（自動帶上 `Accept-Language`、登入失效時導回登入畫面）。

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

### 閾值與告警等級

閾值屬於個別設備，**沒有預設值**：沒有設定閾值的設備與指標只記錄數值、不告警。
在網頁「閾值設定」分頁選擇設備後設定，或呼叫 `/api/admin/device-thresholds`：

| 欄位 | 說明 |
|---|---|
| `warning` / `critical` | 兩段觸發值，至少設定一個；可為 `null` |
| `direction` | `HIGH`（超過才告警）或 `LOW`（低於才告警）；cpu／memory／disk 固定 `HIGH` |
| `rearmMargin` | 恢復寬度：HIGH 時數值低於或等於「觸發值 − 寬度」才算自該等級恢復 |
| `triggerCount` / `rearmCount` | 連續幾次超過才觸發或升級／連續幾次恢復才降級或解除 |

- 磁碟：設備的 `disk` 閾值套用到所有掛載點，`disk:/data` 這類掛載點閾值可個別覆寫。
- 告警事件有 `TRIGGERED`（發生）、`CHANGED`（等級改變）、`RESOLVED`（解除）三種，每筆都帶 `WARNING` 或 `CRITICAL`。
- 程式重新啟動後，依資料庫最後一筆事件延續未解除的告警，不會重複觸發。
- 「套用到其他設備」可把一台設備的閾值與連線中斷告警設定一次複製到多台設備。
- 從舊版升級時，原本的全域閾值會轉成每台既有設備的 Critical 閾值（只執行一次）；之後新增的設備沒有閾值。

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

## 多語系

- **語言決定：** 使用者在登入畫面或頁首選擇的語言（記在瀏覽器）→ 瀏覽器偏好語言（繁體相關 → 正體中文、簡體相關 → 簡體中文、英文 → 英文）→ 正體中文。
- **API：** 錯誤與成功訊息依請求的 `Accept-Language` 產生（`zh-TW`／`zh-CN`／`en`），未帶或不支援時為正體中文，與舊版相同。外部程式可帶 `Accept-Language: en` 取得英文訊息。
- **系統產生的文字**（告警描述、稽核說明、OID 掃描進度與探測結果）以訊息代碼存入資料庫，讀取時依語言翻譯；升級前的舊資料原樣顯示。使用者輸入的資料（設備名稱、Trap 對應說明等）不翻譯。
- **新增文字：**
  - 前端：`frontend/src/locales/zh-TW.ts`（基準）、`zh-CN.ts`、`en.ts` 三份都要加，缺鍵時型別檢查失敗。
  - 後端：`src/main/resources/i18n/messages.properties`（正體中文）、`messages_zh_CN.properties`、`messages_en.properties`，程式中以 `Msg.of("代碼", 參數…)` 使用；`MessagesTest` 檢查三份鍵與參數一致，`NoHardcodedUserTextTest` 禁止在程式中直接寫中文字串（log 一律英文）。

## 專案結構

```
src/main/java/io/github/chiayi1224/simplesnmp/
├── SnmpMonitor.java           主程式入口
├── i18n/                      多語系：Msg（訊息代碼）、Messages（依語系產生文字）、LocaleFilter、StoredText
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
│   ├── Severity.java          告警等級（WARNING / CRITICAL）
│   ├── ThresholdConfig.java   閾值參數（Warning / Critical / 恢復寬度）
│   ├── ThresholdState.java    多等級狀態機
│   ├── ThresholdEngine.java   每設備評估、重啟還原、連線中斷告警
│   └── AlertEvent.java        事件 model
├── trap/
│   └── TrapReceiver.java      v1/v2c/v3 Trap 接收
└── web/
    ├── AuthFilter.java        Session 認證 Filter
    ├── StaticCacheFilter.java 前端靜態檔快取標頭
    ├── JettyServer.java       Jetty 啟動
    └── servlet/
        ├── AuthServlet.java   登入/登出 API
        ├── UserServlet.java   帳號管理 API
        ├── DeviceServlet.java 設備管理 API
        ├── V1StatusServlet.java 狀態查詢 API
        ├── V1AlertServlet.java  告警查詢 API
        ├── DeviceThresholdServlet.java 每台設備閾值 API
        └── BaseJsonServlet.java JSON 共用基底

frontend/                      網頁介面（Vue 3 + Naive UI + TypeScript，Vite 建置，打包進 JAR 的 static/）
├── src/views/                 各功能頁（狀態、設備、OID 探索、範本、Trap、閾值、告警、帳號、Token、稽核、設定）
├── src/components/            版面、對話框、共用元件
├── src/locales/               語系檔（zh-TW 為基準、zh-CN、en）
└── src/api/                   API 用戶端與回應型別
```

## API 端點

`/api/admin/*` 需登入（Session），`/api/v1/*` 可用 Session 或 Bearer Token。

| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/auth/login`、`/api/auth/logout` | 登入 / 登出 |
| GET | `/api/auth/me` | 目前登入帳號 |
| POST | `/api/v1/status` | 各設備最新狀態 |
| POST | `/api/v1/devices`、`/api/v1/devices/{name}` | 設備清單 / 單台設備（不含 v3 密碼） |
| POST | `/api/v1/alerts` | 告警記錄（body：`device`、`severity`、`active` 只看未解除、`limit`；每筆含 `severity`） |
| GET / POST | `/api/admin/devices` | 設備清單 / 新增（含 `snmpVersion`、v3、`diskFilter`、`unreachableSeverity`／`unreachableCount` 欄位） |
| GET / POST | `/api/admin/devices/{name}` | 單台設備 / 更新（body 為 `{"action":"delete"}` 時刪除） |
| GET / POST | `/api/admin/users` | 帳號清單 / 新增 |
| PUT / DELETE | `/api/admin/users/{id}` | 更新 / 刪除帳號 |
| PUT | `/api/admin/users/{id}/password` | 修改密碼 |
| GET / POST | `/api/admin/trap-mappings` | Trap OID 對應清單 / 新增（`severity` 省略為 CRITICAL；觸發 OID 重複回 409） |
| PUT / DELETE | `/api/admin/trap-mappings/{id}` | 更新 / 刪除對應 |
| GET | `/api/admin/unknown-traps` | 未定義 Trap 與未登錄來源 |
| DELETE | `/api/admin/unknown-traps/oids/{id}`、`/sources/{id}` | 刪除單筆未定義記錄 |
| POST | `/api/admin/discovery/scans` | 啟動 OID 掃描（202；設備已有掃描回 409） |
| GET | `/api/admin/discovery/scans?device=` | 掃描紀錄 |
| GET | `/api/admin/discovery/scans/{id}` | 掃描結果（系統資訊與 MIB 探測） |
| GET | `/api/admin/discovery/scans/{id}/oids?prefix=&q=&offset=&limit=` | OID 明細分頁 |
| GET / POST / PUT / DELETE | `/api/admin/v3-users` | Trap 用 SNMPv3 使用者 |
| GET / PUT | `/api/admin/settings`、`/api/admin/settings/{key}` | 系統設定 |
| GET | `/api/admin/device-thresholds?device=` | 設備的閾值與可設定的指標；不帶 `device` 時回傳每台設備的閾值筆數 |
| PUT | `/api/admin/device-thresholds` | 以 `device`＋`metricName` 新增或取代閾值 |
| DELETE | `/api/admin/device-thresholds/{id}` | 刪除閾值（未解除的告警於下一次輪詢解除） |
| POST | `/api/admin/device-thresholds/copy` | `{"from":"A","to":["B"]}` 複製閾值與連線中斷告警設定 |
| GET / POST | `/api/admin/profiles` | 監控範本清單（含套用設備數）／新增 |
| GET / PUT / DELETE | `/api/admin/profiles/{id}` | 單一範本／更新／刪除（內建範本 403） |
| POST | `/api/admin/profiles/{id}/copy` | 複製範本 |
| POST | `/api/admin/profiles/preview` | 以範本對設備試算一次（不儲存、不告警） |
| GET / POST | `/api/admin/tokens`、`/api/admin/tokens/{id}` | API Token（更新或 `{"action":"delete"}` 刪除皆用 POST） |
| GET | `/api/admin/audit-logs` | 稽核日誌 |

## License

本專案採用 [Apache License 2.0](LICENSE) 授權，版權宣告見 [NOTICE](NOTICE)。

使用的第三方套件及其授權、版權宣告，請見 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)；授權全文放在 [licenses/](licenses/) 目錄。打包後的 JAR 會在 `META-INF/` 內附上同一份文件。
