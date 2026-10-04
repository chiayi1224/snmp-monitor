# Proposal

## Why

每個廠牌、型號的設備支援的 MIB 與 OID 都不同，但目前系統只能用寫死的 Net-SNMP（UCD-SNMP）OID 抓 CPU／記憶體，換成交換器、Windows 或其他廠牌就抓不到；使用者也沒有工具知道某台設備到底提供哪些 OID。另外，設備只能用 SNMP v2c 連線，只開 v1 或 v3 的設備無法監控也無法探索；收到的未定義 Trap 雖然會被記錄，但網頁上看不到，也沒有辦法把它轉成告警規則。

本 change 先提供「探索」能力：讓使用者能掃描任一台設備、看到它支援什麼，並把未定義 Trap 轉成告警對應。把掃描到的 OID 套用成監控指標，則在後續 change `add-device-metric-profiles` 處理。

## What Changes

- **設備 SNMP 連線設定**：每台設備可設定 SNMP 版本（v1／v2c／v3）；v3 設備可設定使用者、認證協定與密碼、加密協定與密碼。Poller 與掃描程式都依此連線。API 不回傳任何密碼或 community 以外的機密欄位。
- **SNMPv3 協定擴充**：認證協定新增 SHA-224／256／384／512，加密協定新增 AES-192／256；設備連線與 Trap 接收的 v3 使用者共用。
- **設備 OID 掃描**：
  - 對指定設備非同步執行掃描，讀取系統資訊（sysDescr、sysObjectID、sysName、sysUpTime），並依 sysObjectID 的企業編號辨識廠牌。
  - 探測常見 MIB 是否支援並取樣：HOST-RESOURCES-MIB（處理器負載、儲存空間）、UCD-SNMP-MIB（Linux CPU／記憶體）、IF-MIB（網路介面）、Cisco CPU／記憶體。
  - 可選擇完整 walk 指定的 OID 子樹（預設為 MIB-2 與該設備的廠牌子樹），有筆數與時間上限。
  - 掃描結果存入資料庫，每台設備保留最近 3 次。
- **網頁介面**：
  - 新增「OID 探索」分頁：選設備、啟動掃描、查看系統資訊、支援的 MIB 清單與可搜尋的 OID 表。
  - 新增「Trap 對應」分頁：管理 Trap OID 對應（API 已存在，僅缺畫面）；列出未定義 Trap 與未登錄來源，可一鍵轉成告警對應或新增為設備。
  - 設備對話框加入 SNMP 版本與 v3 欄位。
- 未定義 Trap 建立對應後，該筆未定義記錄自動移除；也可手動刪除未定義記錄。

## Capabilities

### New Capabilities
- `device-snmp-access`：每台設備的 SNMP 連線設定（版本、community、v3 認證），以及 Poller／掃描程式依設定建立連線的行為。
- `device-discovery`：對設備執行 OID 掃描、辨識廠牌、探測常見 MIB、儲存與查詢掃描結果。
- `trap-mapping`：Trap OID 對應的管理，以及未定義 Trap／未登錄來源的檢視與轉換。

### Modified Capabilities
（無：專案目前沒有既有 spec）

## Impact

- **資料表**：`devices` 新增 SNMP 版本與 v3 欄位（以既有 `SchemaManager` 遷移機制補欄位，舊設備預設 v2c，行為不變）；新增 `device_scans`、`device_scan_results`。
- **程式碼**：`SnmpPoller.buildTarget()` 改為依設備版本建立 `CommunityTarget`／`UserTarget`；新增 `discovery` 套件（掃描服務、MIB 探測定義、廠牌對照）；`DeviceRepository`、`DeviceServlet`、`V3UserConfig`、`TrapRepository`、`UnknownTrapServlet` 擴充；`index.html` 新增兩個分頁。
- **API**：新增 `/api/admin/discovery/*`；`/api/admin/devices` 增加選填欄位（向下相容）；`/api/admin/unknown-traps` 增加刪除。
- **相依套件**：無新增（SNMP4J 已提供 GETBULK、TableUtils 與 SHA-2／AES-256）。
- **安全**：v3 密碼與現有 community 一樣以明文存在 SQLite；API 回應一律遮蔽密碼。掃描只發出唯讀的 GET／GETNEXT／GETBULK，不會寫入設備。
