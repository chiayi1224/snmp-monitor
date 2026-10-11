# Spec Delta

## Purpose

定義每台設備各自的指標閾值：哪些指標要告警、Warning 與 Critical 的觸發值、恢復條件與連續次數，讓不同用途的設備可以套用不同的告警標準。

## ADDED Requirements

### Requirement: 閾值屬於個別設備
每一筆閾值 SHALL 屬於一台設備與一個指標名稱（`cpu`、`memory`、`disk`、`disk:<掛載點>` 或自訂指標名稱），同一設備同一指標只能有一筆。系統 SHALL NOT 提供跨設備共用的全域閾值；一台設備的閾值 SHALL NOT 影響其他設備的告警。

#### Scenario: 兩台設備不同門檻
- **WHEN** 設備 A 的 `cpu` Critical 為 80，設備 B 的 `cpu` Critical 為 95，兩台設備 CPU 都連續達到次數為 90
- **THEN** 設備 A 產生 `cpu` Critical 告警，設備 B 不產生告警

#### Scenario: 同一設備同一指標重複設定
- **WHEN** 管理者為設備 A 新增第二筆 `cpu` 閾值
- **THEN** 系統以新值取代原本的 `cpu` 閾值，設備 A 仍只有一筆 `cpu` 閾值

### Requirement: 沒有預設閾值
系統 SHALL NOT 自動建立任何閾值；新增的設備 SHALL 沒有閾值。沒有閾值的設備與指標 SHALL 照常收集與儲存數值，但 SHALL NOT 產生閾值告警。

#### Scenario: 新增設備
- **WHEN** 管理者新增一台設備，且尚未為它設定閾值
- **THEN** 該設備沒有任何閾值，CPU 達到 100% 也不產生告警，數值照常儲存

#### Scenario: 全新安裝
- **WHEN** 系統以空的資料庫第一次啟動
- **THEN** 資料庫中沒有任何閾值設定

### Requirement: 閾值內容
一筆閾值 SHALL 包含方向（`HIGH` 超過才告警或 `LOW` 低於才告警）、Warning 觸發值、Critical 觸發值、恢復寬度、觸發所需連續次數與恢復所需連續次數。Warning 與 Critical SHALL 至少設定一個。`cpu`、`memory`、`disk` 與 `disk:<掛載點>` 的方向 SHALL 固定為 `HIGH`。

#### Scenario: 只設定 Critical
- **WHEN** 管理者為設備 A 設定 `memory` 只有 Critical 85
- **THEN** 閾值被儲存，該指標只會產生 Critical 告警

#### Scenario: 兩個觸發值都沒有
- **WHEN** 管理者儲存一筆沒有 Warning 也沒有 Critical 的閾值
- **THEN** API 回應 HTTP 400，閾值不變

#### Scenario: 內建指標改方向
- **WHEN** 管理者將設備 A 的 `cpu` 閾值方向設為 `LOW`
- **THEN** API 回應 HTTP 400

### Requirement: 閾值數值驗證
系統 SHALL 拒絕不合理的閾值並回應 HTTP 400：方向為 `HIGH` 時 Warning SHALL 小於 Critical，方向為 `LOW` 時 Warning SHALL 大於 Critical；恢復寬度 SHALL 不小於 0；連續次數 SHALL 至少為 1；`cpu`、`memory`、`disk` 與 `disk:<掛載點>` 的觸發值 SHALL 介於 0（不含）到 100（含）之間。

#### Scenario: Warning 高於 Critical
- **WHEN** 管理者為 `cpu` 設定 Warning 95、Critical 90
- **THEN** API 回應 HTTP 400，說明 Warning 必須小於 Critical

#### Scenario: 百分比超過 100
- **WHEN** 管理者為 `disk` 設定 Critical 120
- **THEN** API 回應 HTTP 400

#### Scenario: LOW 方向
- **WHEN** 管理者為自訂指標 `free_ports` 設定方向 `LOW`、Warning 10、Critical 3
- **THEN** 閾值被儲存

### Requirement: 磁碟閾值的設備預設與掛載點覆寫
設備的 `disk` 閾值 SHALL 套用到該設備每一個被記錄的掛載點。設備若有 `disk:<掛載點>` 閾值，該掛載點 SHALL 改用此閾值，不再套用 `disk`。設備沒有 `disk` 也沒有對應的 `disk:<掛載點>` 時，該掛載點 SHALL NOT 告警。

#### Scenario: 套用設備預設
- **WHEN** 設備 A 的 `disk` Critical 為 80，`/` 與 `/data` 都連續達到次數為 85
- **THEN** 設備 A 分別產生 `disk:/` 與 `disk:/data` 的 Critical 告警

#### Scenario: 掛載點覆寫
- **WHEN** 設備 A 的 `disk` Critical 為 80、`disk:/data` Critical 為 95，`/data` 連續達到次數為 90
- **THEN** 不產生 `disk:/data` 告警

#### Scenario: 只有覆寫沒有預設
- **WHEN** 設備 A 只有 `disk:/data` 閾值，`/` 使用率為 99
- **THEN** 不產生 `disk:/` 告警

### Requirement: 刪除與修改閾值
管理者 SHALL 可修改或刪除設備的任一筆閾值，修改後下一次輪詢即生效。刪除閾值後該設備該指標 SHALL 不再產生新的閾值告警；若該指標有未解除的告警，系統 SHALL 記錄一筆解除事件。刪除設備時，系統 SHALL 一併刪除該設備的所有閾值。

#### Scenario: 刪除有未解除告警的閾值
- **WHEN** 設備 A 的 `cpu` 目前有 Critical 告警，管理者刪除設備 A 的 `cpu` 閾值
- **THEN** 系統記錄一筆設備 A `cpu` 的解除事件，之後 CPU 再高也不產生告警

#### Scenario: 刪除設備
- **WHEN** 管理者刪除設備 A
- **THEN** 設備 A 的所有閾值一併刪除，其他設備的閾值不受影響

### Requirement: 複製閾值到其他設備
管理者 SHALL 可將一台設備的全部閾值與連線中斷告警設定，一次套用到一或多台其他設備。目標設備中與來源同名的指標 SHALL 被來源的設定取代；目標設備有、來源沒有的指標 SHALL 保留。來源設備 SHALL 不受影響。

#### Scenario: 套用到兩台設備
- **WHEN** 設備 A 有 `cpu`、`disk` 閾值，設備 B 有 `cpu`、`temperature` 閾值，管理者將 A 的設定套用到 B 與 C
- **THEN** B 的 `cpu` 與 `disk` 和 A 相同、`temperature` 保留；C 的 `cpu` 與 `disk` 和 A 相同

#### Scenario: 目標設備不存在
- **WHEN** 管理者將設定套用到不存在的設備
- **THEN** API 回應 HTTP 400，所有目標設備的閾值都不變

### Requirement: 從全域閾值升級
系統啟動時若偵測到升級前的全域閾值設定且尚未轉換，SHALL 為每台既有設備建立對應的閾值：原觸發值成為 Critical、不設定 Warning、恢復寬度為原觸發值與恢復值之差、方向與連續次數沿用。全域的 `disk` 閾值 SHALL 轉成每台設備的 `disk`。轉換 SHALL 只執行一次。

#### Scenario: 升級轉換
- **WHEN** 升級前全域 `cpu` 閾值為觸發 90、恢復 80、連續 3 次，資料庫中有設備 A 與 B
- **THEN** 升級後設備 A 與 B 各有 `cpu` 閾值：Critical 90、恢復寬度 10、觸發連續 3 次

#### Scenario: 升級後新增設備
- **WHEN** 升級轉換完成後管理者新增設備 C
- **THEN** 設備 C 沒有任何閾值

#### Scenario: 重複啟動
- **WHEN** 升級轉換完成後，管理者刪除了設備 A 的 `cpu` 閾值並重新啟動系統
- **THEN** 設備 A 不會再次被建立 `cpu` 閾值
