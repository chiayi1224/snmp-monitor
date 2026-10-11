# Design

## Context

動機見 proposal.md，需求見 `specs/device-thresholds` 與 `specs/alert-severity`。和設計有關的現況如下：

- `threshold_settings(metric_name UNIQUE, threshold, rearm, trigger_count, rearm_count, direction, …)` 是全域設定。
  - `ThresholdRepository.seedDefaults()` 在資料表為空時種入 cpu 90/80、memory 85/75、disk 80/70。
  - `load()` 把 `disk:<掛載點>` 一律對應到 `disk`，查不到時再退回 `ThresholdConfig.cpu()/memory()/disk()` 的程式預設。
  - 所以目前即使資料表是空的，CPU 等內建指標也會告警。
- `ThresholdEngine` 由 `SnmpPoller` 建立。
  - 以 `device::metric` 為鍵，把 `ThresholdState`（NORMAL／TRIGGERING／TRIGGERED／REARMING）存在記憶體中，沒有從資料庫還原的機制。
- `alert_events(ts, device_name, metric_name, event_type, value, threshold_val, message)` 只有 TRIGGERED／RESOLVED 兩種事件。
  - `AlertRepository.hasOpen()` 與 `purgeBefore()` 都以 `event_type IN ('TRIGGERED','RESOLVED')` 判斷最後一筆。
- `TrapReceiver` 不經過 `ThresholdEngine`，直接寫入 `alert_events`，以 `hasOpenAlert` 避免重複觸發或重複解除。
- `SnmpPoller.updateStatus()` 每一輪都寫入狀態（ONLINE／OFFLINE／SNMP_ERROR），不比較前後，也不產生告警。
- 前端：
  - `/api/alerts`（`AlertServlet`）沒有註冊路由，實際使用 `/api/v1/alerts`（`V1AlertServlet`）。
  - 告警頁只以 TRIGGERED 紅、RESOLVED 綠區分。
  - 狀態頁沒有告警資訊。
- 設備在 API 與資料表中以名稱識別，名稱建立後不可修改。

## Goals / Non-Goals

**Goals:**
- 閾值判定、等級升降與重啟還原，都能在不連設備、不起伺服器的情況下用單元測試驗證。
- 升級後既有告警的行為不中斷：轉換後的 Critical 閾值，與原本的觸發／恢復結果完全相同。
- 所有告警來源（閾值、連線中斷、Trap）都寫入同一張 `alert_events`，用同一套「未解除」判定。

**Non-Goals:**
- 不做通知（Email、Webhook 等）；目前系統沒有通知機制。
- 不做告警確認（acknowledge）與靜音（maintenance window）。
- 不做閾值範本或設備群組；跨設備重複設定由「複製到其他設備」解決。
- Trap 不套用連續次數；Trap 本身就是事件，收到即觸發，和現在相同。

## Decisions

### D1. 資料模型
- 新增資料表：
  ```
  device_thresholds(id, device_name, metric_name, direction,
                    warning REAL NULL, critical REAL NULL, rearm_margin REAL NOT NULL,
                    trigger_count, rearm_count, updated,
                    UNIQUE(device_name, metric_name))
  ```
- 欄位遷移（`SchemaManager.MIGRATIONS`）：
  - `alert_events.severity TEXT NOT NULL DEFAULT 'CRITICAL'`
  - `trap_oid_mappings.severity TEXT NOT NULL DEFAULT 'CRITICAL'`
  - `devices.unreachable_severity TEXT NOT NULL DEFAULT ''`（`''` 代表關閉）
  - `devices.unreachable_count INTEGER NOT NULL DEFAULT 1`
- 預設值用 `DEFAULT 'CRITICAL'`，既有事件與 Trap 對應在 `ALTER TABLE` 時就自動成為 Critical，不需要另外寫轉換。
- `device_name` 而不是 `device_id`：
  - 與 `alert_events`、指標表一致，設備名稱也不可修改。
  - 刪除設備時，在同一個操作中刪除其閾值。
- **恢復用「恢復寬度」而不是每個等級各一個恢復值**：
  - 兩段閾值若各有恢復值，就要四個數字，還得驗證彼此的大小關係，設定頁也難懂。
  - 一個寬度同時套用到兩個等級（Critical 95、寬度 5 → 90 以下才算自 Critical 恢復），升級轉換也只要「觸發值 − 恢復值」。
  - 替代方案「每級各自恢復值」較有彈性，但目前的使用情境不需要。

### D2. 多等級狀態機（取代 `ThresholdState`）
狀態：
- `level`：NONE、WARNING、CRITICAL 三者之一。
- 升級候選計數 `escalateCount`，以及這段期間每次都達到的最低等級 `escalateLevel`。
- 恢復計數 `recoverCount`。

每次輸入數值時：
1. 先算出原始等級 `raw`：超過 Critical 為 CRITICAL，超過 Warning 為 WARNING，否則為 NONE。未設定的觸發值視為不會超過。
2. **升級路徑**（`raw > level`）：
   - `escalateCount++`，`escalateLevel = min(escalateLevel, raw)`。
   - 達到 `triggerCount` 時移到 `escalateLevel`。原本是 NONE 就產生 TRIGGERED，否則產生 CHANGED。
   - 在 `raw <= level` 的輪詢中，`escalateCount` 歸零。
3. **恢復路徑**（`level` 不是 NONE）：
   - 以「自目前等級恢復」判定，也就是低於或等於「目前等級的觸發值 − 寬度」。
   - 連續 `rearmCount` 次就依最後一次的數值重新定位：有設定 Warning 且數值尚未「自 Warning 恢復」（HIGH 時仍高於 Warning − 寬度），就是 CHANGED → WARNING；否則是 RESOLVED。用同一套恢復寬度判定，避免降為 Warning 後下一輪又立刻解除。
   - 判定不成立時，`recoverCount` 歸零。
4. 升級與恢復計數互斥：任何一條路徑推進時，另一條歸零。

設定變更後，目前等級對應的觸發值可能已被移除（spec「閾值變更後立即生效」）。這種情況在下一次評估時直接重新定位，不套用連續次數：
- 數值超過剩下的那個觸發值，就是 CHANGED。
- 否則是 RESOLVED。

理由：這時已經沒有可以比較的觸發值，維持舊等級沒有意義。

- 狀態機是純邏輯類別，不碰資料庫，所以 spec 的每個 Scenario 都能直接寫成單元測試。
- 替代方案：保留舊的四狀態機，並為每個等級各跑一個實例。
  - 兩個實例會各自觸發，產生 WARNING 與 CRITICAL 兩筆同時開啟的告警。
  - 這和「同一個告警在等級間升降」的需求不符。

### D3. 閾值查找與 disk 覆寫
`ThresholdRepository.load(device, metric)` 的查找順序：
- `metric` 是 `disk:<掛載點>` 時，先找該掛載點的列，找不到再找 `disk`。
- 回傳的設定保留原本的 `disk:<掛載點>` 名稱，所以告警與狀態仍以掛載點區分。
- 找不到就回傳 `null`，不告警，也不再有程式內建的預設值。
- 移除 `seedDefaults()` 與 `ThresholdConfig.cpu()/memory()/disk()` 這些預設工廠方法。

`ThresholdEngine` 每次評估都重新讀取設定，和現在一樣，修改後下一輪即生效。

評估時若 `config == null`，但記憶體中該指標的等級不是 NONE，例如閾值剛被刪除：
- 記錄 RESOLVED（訊息「閾值已移除」），再丟棄狀態。
- 刪除 `disk` 預設閾值時也一樣：沒有覆寫的掛載點在下一輪會走到這個分支並解除。

### D4. 重啟後還原狀態
`ThresholdEngine` 第一次為某個 `device::metric` 建立狀態時：
- 呼叫 `store.openAlertSeverity(device, metric)`，依最後一筆 TRIGGERED／CHANGED／RESOLVED 取得未解除的等級（最後一筆是 RESOLVED 或沒有事件時為 null）。
- 以該等級作為初始 `level`，計數為 0。
- 記憶體中的等級不是 null 時，每次評估都再查一次資料庫；告警在別處被解除（刪除設備、磁碟過濾變更）時重新同步，之後可以正常再次觸發。只有「目前有告警」的指標才多一次查詢，成本很低。
- 用延遲載入，不在啟動時一次載入：
  - 設備與指標只在被輪詢時才需要狀態。
  - 已刪除的設備與已停用的指標不會被載入。

`hasOpen()`、`purgeBefore()` 與新增的「未解除告警」查詢一律納入 `CHANGED`。判定規則改為「每組設備＋指標最後一筆是 TRIGGERED 或 CHANGED」。這樣保存期限清理後，最後一筆仍會保留，D4 的還原依然正確。

### D5. 設備連線中斷告警
- 在 `SnmpPoller` 每輪決定狀態後，呼叫 `ThresholdEngine.evaluateReachability(device, status)`：
  - 使用 `device.unreachableSeverity`／`unreachableCount`。
  - 以固定指標名 `device_unreachable` 交給同一套狀態機，設定為「Critical 或 Warning 觸發值 0.5、寬度 0、恢復次數 1」。
  - 輸入值：OFFLINE／SNMP_ERROR 為 1，ONLINE 為 0。
- 好處：
  - 重啟還原、未解除查詢、保存期限都和閾值告警共用，不必另寫一套。
  - 訊息依 OFFLINE（Ping 失敗）或 SNMP_ERROR（SNMP 無回應）填入。
- 設定為關閉（`''`）時，比照 D3 的 `config == null`，有未解除的就解除。
- `device_unreachable` 列為保留名稱：閾值 API 與範本的自訂指標名稱驗證都拒絕它。

### D6. Trap 等級
- `TrapOidMapping.Mapping` 增加 `severity`。
- `handleTriggered` 以對應的等級寫入。
- `handleResolved` 改用 `openAlertSeverity` 取得解除前的等級，再寫入。
- Trap 仍不經過 `ThresholdEngine`，因為 Trap 不需要連續次數與恢復寬度。
- Trap 對應 API 的 `severity` 省略時為 CRITICAL，不合法時回 400。

### D7. API
- **移除** `/api/admin/thresholds`（`ThresholdServlet`）。
- 新增 `DeviceThresholdServlet`，掛在 `/api/admin/device-thresholds/*`：

  | 方法與路徑 | 說明 |
  |---|---|
  | `GET` | 每台設備的閾值筆數 `{ "counts": {...} }` |
  | `GET ?device=NAME` | `{ device, thresholds, availableMetrics }`：該設備所有閾值（含 `id`），以及可設定的指標（cpu、memory、disk、最近回報的掛載點、套用範本的自訂指標） |
  | `PUT` | 以 `(device, metricName)` upsert。body：`device, metricName, direction, warning, critical, rearmMargin, triggerCount, rearmCount`；`warning`／`critical` 可為 `null` |
  | `DELETE /{id}` | 刪除一筆 |
  | `POST /copy` | body `{ "from": "A", "to": ["B","C"] }`。先確認所有目標存在（任一不存在就整批回 400、不寫入），再對每個目標以單一 `INSERT … SELECT … ON CONFLICT` 敘述複製。專案共用單一 SQLite 連線且沒有交易輔助，單一敘述本身即為原子操作 |

  - 刪除設備時，`DeviceServlet` 呼叫 `store.deleteDeviceThresholds(name)` 與 `store.resolveOpenAlerts(name, "設備已刪除")`。
  - 不放在 `/api/admin/devices/{name}/thresholds`：設備名稱可能含 `/`，而 `DeviceServlet` 以 `/{name}` 為路由，巢狀路徑會產生歧義。
- `/api/admin/devices` 新增 `unreachableSeverity`（`""`／`WARNING`／`CRITICAL`）與 `unreachableCount`；更新時省略代表沿用。
- `/api/v1/alerts` body 新增 `severity`、`active`（true 時只回未解除）；回應每筆增加 `severity`。
  - `active` 查詢用子查詢取每組設備＋指標的 `MAX(id)`。用 id 而不是 ts，避免同一毫秒的事件無法排序。
- 未註冊路由的 `AlertServlet` 與 `AlertServletTest` 一併刪除，避免兩套告警 API 不一致。

### D8. 升級轉換
仿照 `PollOidMigration`，新增 `ThresholdMigration`，以 `app_settings.thresholds_migrated` 標記只執行一次：
- `threshold_settings` 有資料時，在同一個交易中為每台既有設備，把每一筆轉成 `device_thresholds`：
  - 觸發值 → `critical`，`warning = NULL`。
  - `rearm_margin = |threshold − rearm|`。
  - 方向與次數沿用。
- 寫入標記。
- 全新安裝時 `threshold_settings` 為空（不再種入），只會寫入標記。
- 資料表保留不刪，以利回滾。

轉換後的行為與原本相同（spec「從全域閾值升級」）。原本「超過 90 觸發、80 以下恢復」，等同於「Critical 90、寬度 10、無 Warning」：
- D2 中沒有 Warning 時，自 Critical 恢復即解除。

### D9. 網頁
- **「閾值設定」分頁**：
  - 頂端選擇設備，下方列出該設備的閾值，每列可編輯 Warning／Critical／寬度／次數，也可刪除。
  - 「新增閾值」的指標選單列出：`cpu`、`memory`、`disk`、該設備最近一次回報的掛載點（`disk:<掛載點>`），以及該設備套用範本中的自訂指標名稱。
  - 「套用到其他設備」打開對話框，勾選目標設備後呼叫 `/copy`。
- **設備對話框**：新增「連線中斷告警」（關閉／Warning／Critical）與連續失敗次數。
- **Trap 對應對話框與清單**：新增等級欄位。
- **告警記錄頁**：
  - 增加等級欄（徽章：Critical 紅、Warning 橘）、描述文字欄。
  - 篩選列：等級、設備、只看未解除。
  - 事件欄：TRIGGERED／CHANGED 以等級顏色顯示，RESOLVED 綠色。
  - 所有欄位經 `esc()`。
- **狀態頁**：載入時同時呼叫 `/api/v1/alerts {active:true}`，在設備名稱旁顯示最高等級徽章。

## Risks / Trade-offs

- **升級後新增的設備不再自動告警。**
  - 這是需求本身，但使用者可能沒有意識到。
  - 緩解：
    - 閾值設定頁沒有閾值的設備顯示「尚未設定閾值，不會告警」。
    - 設備清單的「監控範本」旁顯示閾值數量。
- **Trap 與輪詢使用相同指標名稱時會共用未解除狀態。** 例如預設 Trap 對應的 `cpu`。
  - 輪詢引擎還原狀態時，可能讀到 Trap 寫入的事件。
  - 這是既有行為；緩解：
    - Trap 對應頁提示「指標名稱不要與輪詢指標相同」。
    - 預設 Trap 對應的 OID 本來就是 `TODO` 佔位，實際上不會觸發。
- **BREAKING：移除 `/api/admin/thresholds`。** 有外部腳本呼叫時會失效。
  - 緩解：README 與 PR 說明列出新 API。
  - 舊 API 只供網頁使用，`/api/v1/*` 外部 API 不受影響。
- **連線中斷時不評估指標閾值**（和現在相同）。例如 CPU 告警在設備斷線期間維持原等級，不會被解除。
  - 這符合「沒有新數據就不改變判斷」。若要在斷線時一併處理，屬於後續需求。
- **磁碟過濾與閾值的互動。** 掛載點被過濾掉後不再評估，它的未解除告警不會自動解除。
  - 緩解：儲存設備時，若磁碟過濾有變更，解除不再符合過濾條件的 `disk:<掛載點>` 未解除告警。

## Migration Plan

1. 部署新版並啟動：
   - schema 遷移補欄位，既有事件與 Trap 對應自動成為 Critical。
   - `ThresholdMigration` 把全域閾值轉到每台既有設備。
2. 驗證：
   - 閾值設定頁每台既有設備都有與原本相同數值的 Critical 閾值。
   - 原本未解除的告警在下一輪不會重複觸發（D4）。
3. **回滾**：
   - 舊版忽略新增的欄位與 `device_thresholds`，`threshold_settings` 仍在，可以直接執行舊版。
   - 回滾期間的告警事件 `event_type='CHANGED'` 不會被舊版當成開啟或解除，最差的情況是舊版重新觸發一次。
