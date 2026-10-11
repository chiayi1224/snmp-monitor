# alert-severity Specification

## Purpose
定義告警的等級（Warning、Critical）、告警如何在等級之間升降與解除、哪些來源會產生告警（閾值、設備連線中斷、Trap），以及告警在查詢與畫面上如何依等級呈現。

## Requirements

### Requirement: 告警等級
每一筆告警事件 SHALL 帶有等級 `WARNING` 或 `CRITICAL`。事件類型 SHALL 為 `TRIGGERED`（告警發生）、`CHANGED`（等級改變）或 `RESOLVED`（告警解除）；`RESOLVED` 事件的等級 SHALL 為解除前的等級。升級前已存在的告警事件 SHALL 視為 `CRITICAL`。

#### Scenario: 查詢舊告警
- **WHEN** 升級前已有一筆 `cpu` 的 TRIGGERED 事件，升級後查詢告警
- **THEN** 該事件的等級為 `CRITICAL`

#### Scenario: 解除事件的等級
- **WHEN** 一個 Warning 告警解除
- **THEN** 系統記錄一筆等級為 `WARNING` 的 RESOLVED 事件

### Requirement: 閾值告警的觸發
設備某指標目前沒有告警時，若連續達到「觸發連續次數」的輪詢都超過 Warning 觸發值（方向為 `LOW` 時為低於，以下同），系統 SHALL 產生 TRIGGERED 事件。等級 SHALL 為這段期間每次都達到的最高等級：每次都超過 Critical 時為 `CRITICAL`，否則為 `WARNING`。只設定 Critical 時，SHALL 只在連續超過 Critical 時觸發。

#### Scenario: 觸發 Warning
- **WHEN** 設備 A 的 `cpu` Warning 80、Critical 95、觸發連續 3 次，連續三次 CPU 為 85、88、90
- **THEN** 產生一筆 `cpu` WARNING 的 TRIGGERED 事件

#### Scenario: 直接觸發 Critical
- **WHEN** 同上設定，連續三次 CPU 為 96、97、99
- **THEN** 產生一筆 `cpu` CRITICAL 的 TRIGGERED 事件，不會先產生 WARNING

#### Scenario: 混合時取每次都達到的等級
- **WHEN** 同上設定，連續三次 CPU 為 96、85、97
- **THEN** 產生一筆 `cpu` WARNING 的 TRIGGERED 事件

#### Scenario: 中途回到正常
- **WHEN** 同上設定，連續三次 CPU 為 85、70、85
- **THEN** 不產生事件

### Requirement: 閾值告警升級
告警為 `WARNING` 時，若連續達到「觸發連續次數」的輪詢都超過 Critical 觸發值，系統 SHALL 記錄一筆 CHANGED 事件，等級改為 `CRITICAL`。

#### Scenario: Warning 升為 Critical
- **WHEN** 設備 A 的 `cpu` 目前為 WARNING，Critical 95、觸發連續 3 次，之後連續三次 CPU 為 96、97、98
- **THEN** 產生一筆 `cpu` CRITICAL 的 CHANGED 事件

### Requirement: 閾值告警降級與解除
「已恢復」的判定為：方向 `HIGH` 時數值小於或等於「觸發值減恢復寬度」，方向 `LOW` 時數值大於或等於「觸發值加恢復寬度」。告警為 `CRITICAL` 時，連續達到「恢復連續次數」的輪詢都自 Critical 恢復，系統 SHALL 依最後一次數值降為 `WARNING`（記錄 CHANGED）或解除（記錄 RESOLVED）；告警為 `WARNING` 時，連續達到次數都自 Warning 恢復，系統 SHALL 解除。沒有設定 Warning 時，自 Critical 恢復即解除。

#### Scenario: Critical 降為 Warning
- **WHEN** 設備 A 的 `cpu` 目前為 CRITICAL，Warning 80、Critical 95、恢復寬度 5、恢復連續 2 次，之後兩次 CPU 為 88、86
- **THEN** 產生一筆 `cpu` WARNING 的 CHANGED 事件

#### Scenario: Critical 直接解除
- **WHEN** 同上設定，之後兩次 CPU 為 60、50
- **THEN** 產生一筆 `cpu` CRITICAL 的 RESOLVED 事件

#### Scenario: 恢復寬度內不降級
- **WHEN** 同上設定，之後兩次 CPU 為 92、91（未低於 95 − 5 = 90）
- **THEN** 不產生事件，告警維持 CRITICAL

#### Scenario: Warning 解除
- **WHEN** 設備 A 的 `cpu` 目前為 WARNING，Warning 80、恢復寬度 5、恢復連續 2 次，之後兩次 CPU 為 70、72
- **THEN** 產生一筆 `cpu` WARNING 的 RESOLVED 事件

### Requirement: 閾值變更後立即生效
修改閾值後，下一次輪詢 SHALL 依新的閾值判定，目前的告警等級與計數保留。若新設定移除了目前告警所在的等級（例如告警為 WARNING 但新設定只有 Critical），系統 SHALL 依新設定重新判定該告警，並記錄對應的 CHANGED 或 RESOLVED 事件。

#### Scenario: 移除 Warning
- **WHEN** 設備 A 的 `cpu` 目前為 WARNING（CPU 85），管理者將閾值改為只有 Critical 95
- **THEN** 下一次輪詢時產生一筆 `cpu` WARNING 的 RESOLVED 事件

### Requirement: 重啟後延續告警狀態
系統重新啟動後，SHALL 依每台設備每個指標最後一筆告警事件還原目前的告警等級：最後一筆不是 RESOLVED 時，視為仍在該等級的告警中。系統 SHALL NOT 因重新啟動而對同一個未解除告警再產生 TRIGGERED 事件，且數值恢復時 SHALL 正常記錄解除。

#### Scenario: 重啟後持續超標
- **WHEN** 設備 A 的 `cpu` 有未解除的 CRITICAL 告警，系統重新啟動後 CPU 仍持續超過 Critical
- **THEN** 不產生新的 TRIGGERED 事件

#### Scenario: 重啟後恢復
- **WHEN** 同上，系統重新啟動後 CPU 連續達到恢復次數都低於恢復值
- **THEN** 產生一筆 `cpu` CRITICAL 的 RESOLVED 事件

### Requirement: 設備連線中斷告警
每台設備 SHALL 可設定連線中斷告警：關閉（預設）、`WARNING` 或 `CRITICAL`，以及連續失敗次數（至少 1，預設 1）。開啟時，設備連續達到次數的輪詢都是 OFFLINE 或 SNMP_ERROR，系統 SHALL 產生指標名稱為 `device_unreachable` 的 TRIGGERED 事件，訊息說明是 Ping 失敗或 SNMP 無回應；之後第一次輪詢恢復 ONLINE 時 SHALL 記錄 RESOLVED。關閉時 SHALL NOT 產生此告警；從開啟改為關閉時，未解除的此告警 SHALL 記錄解除。

#### Scenario: 預設不告警
- **WHEN** 新增的設備沒有開啟連線中斷告警，且輪詢結果為 SNMP_ERROR
- **THEN** 不產生告警，設備狀態照常更新為 SNMP_ERROR

#### Scenario: 連續失敗後告警
- **WHEN** 設備 A 的連線中斷告警為 CRITICAL、連續 2 次，連續兩次輪詢都是 SNMP_ERROR
- **THEN** 產生一筆 `device_unreachable` CRITICAL 的 TRIGGERED 事件，訊息說明 SNMP 無回應

#### Scenario: 恢復連線
- **WHEN** 設備 A 有未解除的 `device_unreachable` 告警，下一次輪詢恢復 ONLINE
- **THEN** 產生一筆 `device_unreachable` 的 RESOLVED 事件

#### Scenario: 名稱保留
- **WHEN** 管理者建立名稱為 `device_unreachable` 的閾值或自訂指標
- **THEN** API 回應 HTTP 400

### Requirement: 刪除設備時解除其告警
刪除設備時，系統 SHALL 為該設備每一個未解除的告警記錄 RESOLVED 事件，訊息說明設備已刪除；既有的告警歷史 SHALL 保留。

#### Scenario: 刪除有告警的設備
- **WHEN** 設備 A 有未解除的 `cpu` CRITICAL 告警，管理者刪除設備 A
- **THEN** 系統記錄一筆設備 A `cpu` CRITICAL 的 RESOLVED 事件，查詢未解除告警時不再出現設備 A

### Requirement: Trap 告警等級
每筆 Trap 對應 SHALL 有等級 `WARNING` 或 `CRITICAL`，新增時未指定為 `CRITICAL`，升級前既有的對應 SHALL 為 `CRITICAL`。收到觸發 OID 的 Trap 時，產生的 TRIGGERED 事件 SHALL 使用該對應的等級；收到恢復 OID 時的 RESOLVED 事件 SHALL 使用解除前告警的等級。Trap 對應 SHALL 維持全域設定，不分設備。

#### Scenario: Warning 等級的 Trap
- **WHEN** Trap 對應「`fan`、等級 WARNING」的觸發 OID 從設備 A 送達
- **THEN** 產生一筆設備 A `fan` WARNING 的 TRIGGERED 事件

#### Scenario: 不合法的等級
- **WHEN** 管理者建立等級為 `INFO` 的 Trap 對應
- **THEN** API 回應 HTTP 400

### Requirement: 告警查詢
告警查詢 SHALL 回傳每筆事件的等級，並 SHALL 支援依等級、設備篩選，以及只回傳「目前未解除」的告警（每台設備每個指標最後一筆事件不是 RESOLVED 者，以最後一筆的等級為準）。

#### Scenario: 只看未解除
- **WHEN** 設備 A 的 `cpu` 告警已解除、`memory` 告警為 WARNING，查詢未解除告警
- **THEN** 回應只包含設備 A `memory` 一筆，等級為 `WARNING`

#### Scenario: 依等級篩選
- **WHEN** 查詢等級為 `CRITICAL` 的告警記錄
- **THEN** 回應只包含等級為 `CRITICAL` 的事件

#### Scenario: 不合法的等級篩選
- **WHEN** 查詢等級為 `MAJOR` 的告警
- **THEN** API 回應 HTTP 400

### Requirement: 告警畫面呈現
告警記錄頁 SHALL 依等級以顏色區分（Critical 紅、Warning 橘、解除綠），並提供等級、設備與「只看未解除」篩選，且顯示事件的描述文字。狀態頁 SHALL 在每台設備顯示其目前未解除告警中最高的等級；沒有未解除告警時不顯示。所有顯示的設備名稱、指標名稱與描述文字 SHALL 以純文字呈現。

#### Scenario: 狀態頁顯示最高等級
- **WHEN** 設備 A 有一個 WARNING 與一個 CRITICAL 的未解除告警
- **THEN** 狀態頁設備 A 那一列顯示 Critical 標示

#### Scenario: 描述文字含 HTML
- **WHEN** 一筆 Trap 告警的描述文字含有 `<script>`
- **THEN** 告警記錄頁將其顯示為文字，不執行
