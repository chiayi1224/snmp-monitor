# Spec Delta

## Purpose

定義「監控範本」：描述一類設備的 CPU、記憶體、磁碟與自訂指標要從哪些 OID 取得、如何計算，並決定每台設備套用哪一份範本，讓不同廠牌與型號的設備都能被一致地監控。

## ADDED Requirements

### Requirement: 範本內容
一份範本 SHALL 包含名稱、說明、選填的 sysObjectID 前綴，以及一組指標定義。每個指標定義 SHALL 包含指標名稱、計算類型、該類型所需的 OID、單位與啟用狀態。指標名稱 SHALL 為 `cpu`、`memory`、`disk` 或符合 `^[a-z][a-z0-9_]{1,31}$` 的自訂名稱，同一範本內不可重複。

#### Scenario: 建立範本
- **WHEN** 管理者建立範本「Switch-X」，sysObjectID 前綴 `1.3.6.1.4.1.99999.1`，指標 `cpu`（單一值，OID `1.3.6.1.4.1.99999.2.1.0`）
- **THEN** 範本被儲存，API 回應 HTTP 201 與範本編號

#### Scenario: 指標名稱不合法
- **WHEN** 管理者在範本中新增名稱為 `CPU Temp` 的指標
- **THEN** API 回應 HTTP 400，範本不變

#### Scenario: 指標名稱重複
- **WHEN** 管理者在同一範本中新增第二個 `cpu` 指標
- **THEN** API 回應 HTTP 400

### Requirement: 支援的計算類型
指標計算類型 SHALL 僅限下列幾種：
`VALUE`（單一 OID 的值，可乘以倍率）、`HUNDRED_MINUS`（100 減去單一 OID 的值）、`USED_TOTAL_PERCENT`（已用 OID ÷ 總量 OID × 100）、`USED_FREE_PERCENT`（已用 ÷（已用＋剩餘）× 100）、`TABLE_AVERAGE`（表格欄位所有列的平均）、`STORAGE_TABLE`（hrStorage 格式表格，依儲存類型篩選，逐列計算使用率）、`UCD_MEMORY`（Net-SNMP 的 total − free − buffer − cache 公式）。

#### Scenario: 不支援的計算類型
- **WHEN** 管理者建立計算類型為 `EXPRESSION` 的指標
- **THEN** API 回應 HTTP 400，列出支援的類型

#### Scenario: 缺少必要的 OID
- **WHEN** 管理者建立 `USED_TOTAL_PERCENT` 指標但只提供已用 OID
- **THEN** API 回應 HTTP 400，說明缺少總量 OID

### Requirement: 內建範本
系統 SHALL 提供內建範本「Net-SNMP（UCD-SNMP）」（前綴 `1.3.6.1.4.1.8072`）、「Cisco IOS」（前綴 `1.3.6.1.4.1.9`）與「HOST-RESOURCES 通用」（無前綴）。內建範本 SHALL NOT 可被修改或刪除，但 SHALL 可被複製成可編輯的新範本。

#### Scenario: 修改內建範本
- **WHEN** 管理者嘗試更新或刪除內建範本
- **THEN** API 回應 HTTP 403，範本不變

#### Scenario: 複製內建範本
- **WHEN** 管理者複製「Net-SNMP（UCD-SNMP）」
- **THEN** 產生一份內容相同、名稱加上「（複本）」、非內建、可編輯的範本

### Requirement: 設備套用範本的規則
每台設備 SHALL 恰好套用一份範本，規則依序為：設備手動指定的範本；否則為 sysObjectID 前綴與設備 sysObjectID 相符且前綴最長的範本；都不符合時為「HOST-RESOURCES 通用」。

#### Scenario: 自動套用
- **WHEN** 一台設備的 sysObjectID 為 `1.3.6.1.4.1.9.1.1208`，且未手動指定範本
- **THEN** 該設備套用「Cisco IOS」

#### Scenario: 較長前綴優先
- **WHEN** 管理者建立前綴為 `1.3.6.1.4.1.9.1.1208` 的範本，設備 sysObjectID 為 `1.3.6.1.4.1.9.1.1208`
- **THEN** 該設備改為套用新範本，而非「Cisco IOS」

#### Scenario: 手動指定優先
- **WHEN** 管理者將上述設備手動指定為「HOST-RESOURCES 通用」
- **THEN** 不論 sysObjectID 為何，該設備都套用「HOST-RESOURCES 通用」

#### Scenario: 刪除被手動指定的範本
- **WHEN** 管理者刪除一份被設備手動指定的範本
- **THEN** 該設備改回自動套用規則

### Requirement: 範本預覽
管理者 SHALL 可對任一設備以任一範本（包含尚未儲存的範本內容）試算一次，取得每個指標的計算結果或失敗原因；預覽 SHALL NOT 儲存數值或觸發告警。

#### Scenario: 預覽成功
- **WHEN** 管理者以「Cisco IOS」預覽一台 Cisco 設備
- **THEN** 回應列出 `cpu`、`memory` 的計算值與單位

#### Scenario: OID 不存在
- **WHEN** 預覽的指標 OID 在設備上回應 noSuchObject
- **THEN** 該指標的結果為失敗並說明 OID 不存在，其他指標照常計算

### Requirement: 從掃描結果加入範本
「OID 探索」的 OID 表每一列 SHALL 提供「加入範本」操作，預先帶入該 OID 並讓管理者選擇範本、指標名稱與計算類型。

#### Scenario: 加入自訂指標
- **WHEN** 管理者在掃描結果中選擇溫度 OID，加入範本「Switch-X」，指標名稱 `temperature`、類型 `VALUE`
- **THEN** 範本「Switch-X」新增 `temperature` 指標

### Requirement: 監控範本網頁
網頁介面 SHALL 提供「監控範本」分頁，列出所有範本（標示內建）、套用的設備數，可新增、複製、編輯、刪除範本與指標，並執行預覽。設備對話框 SHALL 可選擇範本，預設為「自動」並顯示目前實際套用的範本。

#### Scenario: 設備對話框
- **WHEN** 管理者開啟一台自動套用「Cisco IOS」的設備
- **THEN** 範本選單為「自動」，並顯示「目前：Cisco IOS」
