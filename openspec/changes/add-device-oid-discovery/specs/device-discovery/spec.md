# Spec Delta

## Purpose

讓管理者能對任一台設備執行 SNMP 唯讀掃描，辨識設備廠牌、探測它支援哪些常見 MIB，並保存可搜尋的 OID 清單，作為之後設定監控指標的依據。

## ADDED Requirements

### Requirement: 對設備啟動非同步掃描
系統 SHALL 允許管理者對已登錄的設備啟動 OID 掃描。掃描 SHALL 在背景執行，啟動請求 SHALL 立即回應掃描編號，管理者可再以編號查詢進度與結果。同一台設備同時間 SHALL 只能有一個進行中的掃描。

#### Scenario: 啟動掃描
- **WHEN** 管理者對設備 `Switch-01` 啟動掃描
- **THEN** API 回應 HTTP 202 與掃描編號，掃描狀態為 `RUNNING`

#### Scenario: 重複啟動
- **WHEN** `Switch-01` 已有進行中的掃描，管理者再次啟動
- **THEN** API 回應 HTTP 409 與進行中掃描的編號，不建立新的掃描

#### Scenario: 設備不存在
- **WHEN** 管理者對不存在的設備名稱啟動掃描
- **THEN** API 回應 HTTP 404

### Requirement: 讀取系統資訊並辨識廠牌
掃描 SHALL 讀取設備的 sysDescr、sysObjectID、sysName 與 sysUpTime，並依 sysObjectID 中 `1.3.6.1.4.1.<企業編號>` 的企業編號辨識廠牌；無法辨識時廠牌 SHALL 顯示為企業編號本身。

#### Scenario: Cisco 設備
- **WHEN** 掃描一台 sysObjectID 為 `1.3.6.1.4.1.9.1.1208` 的設備
- **THEN** 掃描結果的廠牌為 Cisco，並包含 sysDescr、sysName 與 sysUpTime

#### Scenario: 未知廠牌
- **WHEN** 掃描一台 sysObjectID 為 `1.3.6.1.4.1.99999.1` 的設備
- **THEN** 掃描結果的廠牌顯示為企業編號 `99999`

### Requirement: 探測常見 MIB 支援
掃描 SHALL 探測設備是否支援下列 MIB，並對每一項回報「支援／不支援」與取樣數值：HOST-RESOURCES-MIB 處理器負載與儲存空間、UCD-SNMP-MIB 的 CPU 與記憶體、IF-MIB 介面表、Cisco CPU 與記憶體池。

#### Scenario: Linux 主機
- **WHEN** 掃描一台啟用 Net-SNMP 的 Linux 主機
- **THEN** 結果顯示支援 UCD-SNMP-MIB 與 HOST-RESOURCES-MIB，並列出各處理器負載、各儲存空間的名稱與用量、CPU idle 百分比等取樣值

#### Scenario: 不支援的 MIB
- **WHEN** 被掃描的設備對 Cisco CPU 的 OID 回應 noSuchObject 或沒有任何子節點
- **THEN** 該項目標記為不支援，不影響其他項目的探測

### Requirement: 完整 walk 指定子樹
管理者啟動掃描時 SHALL 可選擇同時 walk 一個或多個 OID 子樹；未指定時預設為 MIB-2（`1.3.6.1.2.1`）與該設備的廠牌子樹。單次掃描 SHALL 最多收集 10,000 筆 OID 且最長執行 120 秒，超過時停止並標記結果為「已截斷」。

#### Scenario: 預設子樹
- **WHEN** 管理者啟動完整 walk 但未指定子樹，設備的 sysObjectID 為 `1.3.6.1.4.1.9.1.1208`
- **THEN** 系統 walk `1.3.6.1.2.1` 與 `1.3.6.1.4.1.9` 兩個子樹，結果包含每筆 OID 的資料型別與值

#### Scenario: 超過筆數上限
- **WHEN** walk 的結果超過 10,000 筆
- **THEN** 掃描在第 10,000 筆停止，狀態為 `COMPLETED`，並標記為已截斷

### Requirement: 掃描失敗處理
設備無回應、認證失敗或連線錯誤時，掃描 SHALL 以 `FAILED` 狀態結束並記錄錯誤原因；已取得的部分結果 SHALL 保留。

#### Scenario: 設備無回應
- **WHEN** 掃描的設備在逾時時間內完全沒有回應
- **THEN** 掃描狀態為 `FAILED`，錯誤原因說明設備無回應

### Requirement: 保存與查詢掃描結果
系統 SHALL 保存每次掃描的系統資訊、MIB 探測結果與 OID 清單，每台設備 SHALL 只保留最近 3 次掃描。OID 清單查詢 SHALL 支援以 OID 前綴或值的關鍵字篩選，並支援分頁。

#### Scenario: 保留最近 3 次
- **WHEN** 某台設備完成第 4 次掃描
- **THEN** 最舊的一次掃描及其結果被刪除，保留最近 3 次

#### Scenario: 以前綴篩選
- **WHEN** 管理者查詢某次掃描、篩選前綴 `1.3.6.1.2.1.2.2`
- **THEN** 只回傳 IF-MIB ifTable 底下的 OID

### Requirement: 掃描只讀不寫
掃描 SHALL 只發出 GET、GETNEXT、GETBULK 請求，SHALL NOT 對設備發出任何 SET 請求。

#### Scenario: 掃描過程
- **WHEN** 系統對任一設備執行掃描
- **THEN** 設備收到的請求中不包含任何 SET 請求

### Requirement: OID 探索網頁
網頁介面 SHALL 提供「OID 探索」分頁，可選擇設備、啟動掃描、查看進度、系統資訊、MIB 支援清單，以及可篩選的 OID 表。

#### Scenario: 從網頁掃描
- **WHEN** 管理者在「OID 探索」分頁選擇設備並按下掃描
- **THEN** 頁面顯示掃描進行中，完成後自動顯示系統資訊、MIB 支援清單與 OID 表
