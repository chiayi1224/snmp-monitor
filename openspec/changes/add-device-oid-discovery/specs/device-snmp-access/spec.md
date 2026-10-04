# Spec Delta

## Purpose

定義每台受監控設備的 SNMP 連線設定（SNMP 版本、v1/v2c community、SNMPv3 使用者與認證加密參數），以及系統對設備發出 SNMP 查詢時如何依這些設定連線、如何保護其中的機密欄位。

## ADDED Requirements

### Requirement: 設備可設定 SNMP 版本
系統 SHALL 允許每台設備設定 SNMP 版本為 `1`、`2c` 或 `3`。未指定版本的設備（包含升級前既有的設備）SHALL 視為 `2c`。

#### Scenario: 既有設備升級後維持 v2c
- **WHEN** 系統升級後啟動，資料庫中已有未設定版本的設備
- **THEN** 這些設備的版本為 `2c`，輪詢行為與升級前相同

#### Scenario: 新增 v1 設備
- **WHEN** 管理者新增設備並指定版本 `1` 與 community
- **THEN** 系統儲存該設備，之後以 SNMPv1 搭配該 community 對它發出查詢

#### Scenario: 版本值不合法
- **WHEN** 管理者新增或更新設備時指定版本 `4`
- **THEN** API 回應 HTTP 400，設備資料不變

### Requirement: v3 設備的認證與加密設定
版本為 `3` 的設備 SHALL 可設定使用者名稱、認證協定與密碼、加密協定與密碼。認證協定 SHALL 支援 MD5、SHA、SHA-224、SHA-256、SHA-384、SHA-512；加密協定 SHALL 支援 DES、AES-128、AES-192、AES-256。系統 SHALL 依是否設定認證、加密密碼決定安全等級（noAuthNoPriv／authNoPriv／authPriv）。

#### Scenario: authPriv 設備
- **WHEN** 管理者新增 v3 設備，設定使用者、SHA-256 認證密碼與 AES-256 加密密碼
- **THEN** 系統以 authPriv 安全等級、指定的協定與密碼對該設備發出查詢

#### Scenario: 只設定加密密碼而沒有認證密碼
- **WHEN** 管理者新增 v3 設備，只設定加密密碼、沒有認證密碼
- **THEN** API 回應 HTTP 400，說明加密需要先設定認證

#### Scenario: v3 密碼長度不足
- **WHEN** 管理者設定的 v3 認證或加密密碼少於 8 個字元
- **THEN** API 回應 HTTP 400（SNMPv3 規定密碼至少 8 個字元）

### Requirement: 輪詢與掃描依設備設定連線
系統對設備發出的所有 SNMP 查詢（定期輪詢與 OID 掃描）SHALL 使用該設備設定的版本與認證資訊，並沿用設備的 port、重試次數與逾時設定。

#### Scenario: v3 設備可被輪詢
- **WHEN** 一台 v3 authPriv 設備已啟用，輪詢週期到達
- **THEN** 系統以該設備的 v3 設定查詢成功，並依查詢結果更新設備狀態

#### Scenario: v3 認證失敗
- **WHEN** 一台 v3 設備設定的密碼與設備實際密碼不符，輪詢週期到達
- **THEN** 設備狀態標記為 `SNMP_ERROR`，系統記錄認證失敗的 log，不影響其他設備的輪詢

### Requirement: API 不回傳機密欄位
設備相關 API 的回應 SHALL NOT 包含 v3 認證密碼與加密密碼，只回傳是否已設定。更新設備時未提供密碼欄位 SHALL 保留原本的密碼。

#### Scenario: 查詢 v3 設備
- **WHEN** 管理者以 GET 查詢一台已設定 v3 密碼的設備
- **THEN** 回應包含使用者名稱、協定名稱與「已設定密碼」的旗標，不包含任何密碼內容

#### Scenario: 更新設備但不改密碼
- **WHEN** 管理者更新 v3 設備的 host，請求中沒有密碼欄位
- **THEN** 設備的 host 被更新，原本的 v3 密碼維持不變
