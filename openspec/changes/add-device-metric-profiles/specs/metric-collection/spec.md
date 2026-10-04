# Spec Delta

## Purpose

定義 Poller 如何依每台設備套用的監控範本收集與計算指標、如何儲存 CPU／記憶體／磁碟與自訂指標、如何對指標做閾值告警，以及狀態查詢如何呈現這些數值。

## ADDED Requirements

### Requirement: 依範本收集指標
每個輪詢週期，Poller SHALL 依設備套用的範本，只查詢範本中已啟用指標所需的 OID，並依各指標的計算類型算出數值。單一指標失敗 SHALL NOT 影響同一設備其他指標的收集。

#### Scenario: Cisco 設備
- **WHEN** 一台套用「Cisco IOS」的設備到達輪詢週期
- **THEN** 系統儲存該設備的 CPU 與記憶體使用率，並依閾值評估

#### Scenario: 單一指標失敗
- **WHEN** 範本中的 `temperature` 指標 OID 回應 noSuchObject，`cpu` 正常
- **THEN** `cpu` 照常儲存與評估，`temperature` 本週期沒有數值並記錄警告 log，設備狀態仍為 `ONLINE`

#### Scenario: 停用的指標
- **WHEN** 範本中的 `disk` 指標被停用
- **THEN** Poller 不查詢磁碟表，也不儲存磁碟數值

### Requirement: Net-SNMP 設備升級後結果不變
套用內建「Net-SNMP（UCD-SNMP）」範本的設備，SHALL 得到與升級前相同的 CPU、記憶體與磁碟數值。

#### Scenario: 既有 Linux 主機
- **WHEN** 系統升級後輪詢一台 sysObjectID 為 `1.3.6.1.4.1.8072.3.2.10` 的 Linux 主機
- **THEN** CPU 為 100 − ssCpuIdle、記憶體為（total − free − buffer − cache）÷ total、磁碟為 hrStorage 中固定磁碟的使用率，與升級前的計算結果一致

### Requirement: 更新設備的 sysObjectID
Poller SHALL 在每次成功輪詢時讀取設備的 sysObjectID 並保存；sysObjectID 改變時，自動套用的範本 SHALL 在下一個週期依新值重新判定。

#### Scenario: 首次輪詢
- **WHEN** 一台新設備第一次被輪詢
- **THEN** 系統保存其 sysObjectID，並依此決定套用的範本，在同一週期內以該範本收集指標

### Requirement: 自訂指標儲存
CPU、記憶體、磁碟以外的指標數值 SHALL 以（時間、設備、指標名稱、數值、單位）儲存為時間序列。

#### Scenario: 儲存溫度
- **WHEN** `temperature` 指標本週期計算出 45.0
- **THEN** 系統新增一筆該設備 `temperature` = 45.0 的紀錄

### Requirement: 自訂指標閾值
管理者 SHALL 可為自訂指標名稱設定閾值（觸發值、恢復值、連續次數，以及超過或低於觸發值才告警的方向）；有閾值的自訂指標 SHALL 套用與 CPU 相同的連續次數與 hysteresis 告警規則。沒有閾值的自訂指標 SHALL 只儲存、不告警。

#### Scenario: 溫度過高
- **WHEN** `temperature` 的閾值為高於 70（恢復 65、連續 3 次），某設備連續 3 次輪詢都是 75
- **THEN** 系統產生一筆 `temperature` 的 TRIGGERED 告警

#### Scenario: 低於門檻告警
- **WHEN** `free_ports` 的閾值方向為低於 5，某設備連續達到次數都是 2
- **THEN** 系統產生 `free_ports` 的 TRIGGERED 告警

#### Scenario: 沒有閾值
- **WHEN** `uptime_days` 沒有設定閾值
- **THEN** 數值照常儲存，不會產生告警

### Requirement: 狀態查詢包含自訂指標
`/api/v1/status` 與網頁狀態頁 SHALL 在既有的 CPU、記憶體、磁碟之外，包含每台設備每個自訂指標的最新數值、單位與時間。既有欄位 SHALL 維持不變。

#### Scenario: 查詢狀態
- **WHEN** 外部系統呼叫 `/api/v1/status`
- **THEN** 每台設備的回應包含原有的 `cpu`、`memory`、`disks`，並新增 `custom` 陣列

### Requirement: 移除 Poll OID 管理
系統 SHALL 移除 Poll OID 管理功能；`/api/admin/poll-oids` SHALL 回應 HTTP 404。

#### Scenario: 呼叫舊 API
- **WHEN** 外部程式呼叫 `GET /api/admin/poll-oids`
- **THEN** 回應 HTTP 404

#### Scenario: 升級時保留既有停用設定
- **WHEN** 升級前的 `poll_oids` 中有 CPU 或磁碟相關 OID 被停用
- **THEN** 升級後產生「Net-SNMP（UCD-SNMP）（升級轉換）」範本，該範本的對應指標為停用，並手動指定給原本會套用 Net-SNMP 範本的設備
