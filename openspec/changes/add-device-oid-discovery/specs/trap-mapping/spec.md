# Spec Delta

## Purpose

管理 Trap OID 與告警指標之間的對應規則，並讓管理者能檢視系統收到但尚未定義的 Trap 與未登錄的來源，直接把它們轉成告警對應或受監控設備。

## ADDED Requirements

### Requirement: Trap 對應管理網頁
網頁介面 SHALL 提供「Trap 對應」分頁，列出所有 Trap OID 對應（指標名稱、觸發 OID、恢復 OID、說明、啟用狀態），並可新增、編輯、刪除。變更 SHALL 立即套用到 Trap 接收，不需重啟。

#### Scenario: 新增對應
- **WHEN** 管理者在「Trap 對應」分頁新增指標 `fan`、觸發 OID `1.3.6.1.4.1.9999.0.7`
- **THEN** 對應被儲存，之後收到該 OID 的 Trap 會產生 `fan` 告警

### Requirement: 檢視未定義 Trap 與未登錄來源
「Trap 對應」分頁 SHALL 列出未定義 Trap（Trap OID、最後來源設備、出現次數、首次與最後時間、最後一次的 VarBind）與未登錄來源（來源 IP、最後 Trap OID、出現次數），依最後出現時間由新到舊排序。

#### Scenario: 收到未定義 Trap
- **WHEN** 系統收到一個沒有對應規則的 Trap OID
- **THEN** 該 OID 出現在未定義 Trap 清單中，重複收到時次數累加

### Requirement: 未定義 Trap 轉成告警對應
管理者 SHALL 可從未定義 Trap 清單直接建立告警對應，觸發 OID 預先帶入該筆 Trap OID。對應建立成功後，該筆未定義 Trap 記錄 SHALL 自動移除。

#### Scenario: 一鍵建立對應
- **WHEN** 管理者在未定義 Trap `1.3.6.1.4.1.9999.0.7` 按下「建立對應」，填入指標名稱 `fan` 後儲存
- **THEN** 新對應生效，`1.3.6.1.4.1.9999.0.7` 從未定義 Trap 清單中消失

#### Scenario: OID 已有對應
- **WHEN** 管理者建立的觸發 OID 已經存在於另一筆對應
- **THEN** API 回應 HTTP 409，未定義 Trap 記錄保留不變

### Requirement: 刪除未定義記錄
管理者 SHALL 可刪除單筆未定義 Trap 或未登錄來源記錄（例如已確認不需處理的 Trap）。

#### Scenario: 刪除未定義 Trap
- **WHEN** 管理者刪除一筆未定義 Trap 記錄
- **THEN** 記錄從清單中移除；之後若再收到同一 OID，會重新建立記錄並從 1 開始計次

### Requirement: 未登錄來源轉成設備
管理者 SHALL 可從未登錄來源清單直接新增設備，host 預先帶入來源 IP。設備建立後，該來源 IP 的 Trap SHALL 立即以新設備名稱記錄，該筆未登錄來源記錄 SHALL 自動移除。

#### Scenario: 新增為設備
- **WHEN** 管理者在未登錄來源 `10.0.0.99` 按下「新增為設備」並以名稱 `Router-02` 儲存
- **THEN** 之後來自 `10.0.0.99` 的 Trap 以 `Router-02` 記錄，`10.0.0.99` 從未登錄來源清單中消失
