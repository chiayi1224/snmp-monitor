#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# SNMP Monitor 一鍵測試腳本
#
# 用法：
#   ./test.sh               執行所有測試（含建置）
#   ./test.sh --skip-build  跳過 mvn package（JAR 已存在時）
#   ./test.sh --keep        測試完不關閉伺服器（方便手動驗證）
# ─────────────────────────────────────────────────────────────────

# ── 參數處理 ──────────────────────────────────────────────────────
SKIP_BUILD=false
KEEP_SERVER=false
for arg in "$@"; do
  case $arg in
    --skip-build) SKIP_BUILD=true ;;
    --keep)       KEEP_SERVER=true ;;
  esac
done

# ── 顏色輸出 ──────────────────────────────────────────────────────
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m';  BOLD='\033[1m';   RESET='\033[0m'

PASS=0; FAIL=0; SERVER_PID=""; BODY=""; STATUS=""

log()     { echo -e "${CYAN}[INFO]${RESET} $*"; }
ok()      { echo -e "${GREEN}[PASS]${RESET} $*"; PASS=$((PASS+1)); }
fail()    { echo -e "${RED}[FAIL]${RESET} $*"; FAIL=$((FAIL+1)); }
warn()    { echo -e "${YELLOW}[WARN]${RESET} $*"; }
section() { echo -e "\n${BOLD}══ $* ══${RESET}"; }

BASE="http://localhost:8080"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${SCRIPT_DIR}/target/simplesnmp-1.0.0.jar"
DB_FILE="${SCRIPT_DIR}/target/snmp_monitor.db"
COOKIE_JAR="/tmp/snmp-test-cookies.txt"
cd "${SCRIPT_DIR}"

# ── 清理函式 ──────────────────────────────────────────────────────
cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    if [[ "$KEEP_SERVER" == "true" ]]; then
      warn "伺服器保持運行中 PID=$SERVER_PID（kill $SERVER_PID 停止）"
    else
      log "關閉伺服器 PID=$SERVER_PID"
      kill "$SERVER_PID" 2>/dev/null || true
      wait "$SERVER_PID" 2>/dev/null || true
      rm -f "$DB_FILE"
    fi
  fi
  rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

# ── API 呼叫工具 ──────────────────────────────────────────────────
# 呼叫後 STATUS=HTTP狀態碼，BODY=回應本文
call() {
  local method="$1" path="$2"; shift 2
  local raw
  raw=$(curl -s -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -w "\n__STATUS__%{http_code}" \
    -X "$method" \
    -H "Content-Type: application/json" \
    "$@" "${BASE}${path}" 2>/dev/null) || true
  STATUS=$(echo "$raw" | grep '__STATUS__' | sed 's/__STATUS__//')
  BODY=$(echo "$raw" | grep -v '__STATUS__')
}

assert_status() {
  local label="$1" expected="$2"
  if [[ "$STATUS" == "$expected" ]]; then
    ok "$label (HTTP $STATUS)"
  else
    fail "$label — 預期 HTTP $expected，實際 HTTP $STATUS | $BODY"
  fi
}

assert_contains() {
  local label="$1" needle="$2"
  if echo "$BODY" | grep -q "$needle"; then
    ok "$label"
  else
    fail "$label — 回應中找不到 \"$needle\" | $BODY"
  fi
}

assert_not_contains() {
  local label="$1" needle="$2"
  if ! echo "$BODY" | grep -q "$needle"; then
    ok "$label"
  else
    fail "$label — 回應中不應含有 \"$needle\" | $BODY"
  fi
}

# jq 輔助：找不到時回傳預設值
jq_field() {
  echo "$BODY" | jq -r "${1}" 2>/dev/null | grep -v '^null$' | head -1
}

# ═════════════════════════════════════════════════════════════════
section "1. 建置"
# ═════════════════════════════════════════════════════════════════
if [[ "$SKIP_BUILD" == "false" ]]; then
  log "執行 mvn clean package -q -DskipTests ..."
  if mvn clean package -q -DskipTests; then
    ok "建置完成"
  else
    fail "建置失敗"
    exit 1
  fi
else
  warn "跳過建置"
fi

if [[ ! -f "$JAR" ]]; then
  fail "找不到 $JAR"
  exit 1
fi

# ═════════════════════════════════════════════════════════════════
section "2. 啟動伺服器"
# ═════════════════════════════════════════════════════════════════
rm -f "$DB_FILE" "$COOKIE_JAR"
log "啟動伺服器（DB=$DB_FILE）..."

java -jar "$JAR" > /tmp/snmp-monitor-test.log 2>&1 &
SERVER_PID=$!
log "PID=$SERVER_PID，等待就緒（最多 20 秒）..."

READY=false
for i in $(seq 1 20); do
  if curl -s -o /dev/null "${BASE}/login.html"; then
    ok "伺服器已就緒（${i}s）"
    READY=true
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    fail "伺服器已崩潰，查看 /tmp/snmp-monitor-test.log"
    tail -20 /tmp/snmp-monitor-test.log
    exit 1
  fi
  sleep 1
done

if [[ "$READY" == "false" ]]; then
  fail "伺服器未在 20 秒內就緒"
  exit 1
fi

# ═════════════════════════════════════════════════════════════════
section "3. 版本資訊 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/info
assert_status "GET /api/info 不需登入" 200
assert_contains "含 version 欄位" '"version"'
assert_contains "含 buildTime 欄位" '"buildTime"'
assert_contains "含 title 欄位" '"title"'

# ═════════════════════════════════════════════════════════════════
section "4. 認證 API"
# ═════════════════════════════════════════════════════════════════
# ═════════════════════════════════════════════════════════════════

# 3.1 錯誤密碼
call POST /api/auth/login -d '{"username":"admin","password":"wrong"}'
assert_status "登入失敗（錯誤密碼）" 401

# 3.2 未登入呼叫受保護 API 回 401
call POST /api/v1/status -d '{}'
assert_status "未登入呼叫 API 回 401" 401

# 3.3 預設 admin 首次登入回 202（mustChange）
call POST /api/auth/login -d '{"username":"admin","password":"admin"}'
assert_status "首次登入回 202 mustChange" 202
assert_contains "回應含 mustChange:true" '"mustChange" : true'

# 3.4 取得 admin id 並改密碼
call GET /api/auth/me
ADMIN_ID=$(jq_field '.id')
ADMIN_ID=${ADMIN_ID:-1}
log "admin id=$ADMIN_ID"

call PUT "/api/admin/users/${ADMIN_ID}/password" -d '{"password":"Admin@123"}'
assert_status "修改 admin 密碼" 200

# 3.5 用新密碼重新登入
call POST /api/auth/login -d '{"username":"admin","password":"Admin@123"}'
assert_status "新密碼登入成功" 200
assert_contains "登入成功訊息" "登入成功"

# 3.6 /api/auth/me
call GET /api/auth/me
assert_status "GET /api/auth/me" 200
assert_contains "me 含 username" '"username"'

# ═════════════════════════════════════════════════════════════════
section "5. 帳號管理 API"
# ═════════════════════════════════════════════════════════════════

call POST /api/admin/users -d '{"username":"operator","password":"Oper@123","role":"admin"}'
assert_status "新增帳號" 201

call GET /api/admin/users
assert_status "列出帳號" 200
assert_contains "帳號清單含 operator" "operator"

call POST /api/admin/users -d '{"username":"op2","password":"short"}'
assert_status "密碼太短回 400" 400

call POST /api/admin/users -d '{"username":"","password":"password123"}'
assert_status "帳號空白回 400" 400

call POST /api/admin/users -d '{"username":"operator","password":"Oper@123"}'
assert_status "帳號重複回 500" 500

# ═════════════════════════════════════════════════════════════════
section "6. 設備管理 API"
# ═════════════════════════════════════════════════════════════════

call POST /api/admin/devices -d '{"name":"Router-01","host":"192.168.1.1","port":161,"community":"public"}'
assert_status "新增設備" 201

call POST /api/admin/devices -d '{"name":"Switch-01","host":"192.168.1.2"}'
assert_status "新增設備（省略 port/community）" 201

call POST /api/admin/devices -d '{"name":"","host":"192.168.1.3"}'
assert_status "設備名稱空白回 400" 400

call POST /api/admin/devices -d '{"name":"Router-01","host":"192.168.1.99"}'
assert_status "設備名稱重複回 500" 500

call POST /api/v1/devices -d '{}'
assert_status "列出設備" 200
assert_contains "設備清單含 Router-01" "Router-01"
assert_contains "設備清單含 Switch-01" "Switch-01"

call POST /api/v1/devices/Router-01 -d '{}'
assert_status "POST 單台設備 Router-01" 200
assert_contains "單台設備含 host" "192.168.1.1"

call POST /api/v1/devices/NotExists -d '{}'
assert_status "POST 不存在設備回 404" 404

call POST "/api/admin/devices/Router-01" \
  -d '{"host":"10.0.0.1","port":161,"community":"private","enabled":true}'
assert_status "更新設備" 200

call POST /api/v1/devices -d '{}'
assert_contains "更新後 host 為 10.0.0.1" "10.0.0.1"

call POST "/api/admin/devices/NoSuchDevice" -d '{"action":"delete"}'
assert_status "刪除不存在設備回 200（SQLite 刪除不報錯）" 200

# ── SNMP 版本與 v3 ────────────────────────────────────────────────
call POST /api/admin/devices -d '{"name":"Core-V3","host":"192.168.1.50","snmpVersion":"3","v3User":"monitor","v3AuthProtocol":"SHA256","v3AuthPassword":"authPass123","v3PrivProtocol":"AES256","v3PrivPassword":"privPass123"}'
assert_status "新增 v3 設備" 201

call POST /api/v1/devices/Core-V3 -d '{}'
assert_contains "v3 設備版本為 3" '"snmpVersion" : "3"'
assert_contains "v3 認證已設定" '"v3AuthConfigured" : true'
assert_not_contains "回應不含認證密碼" "authPass123"
assert_not_contains "回應不含加密密碼" "privPass123"

call POST "/api/admin/devices/Core-V3" -d '{"host":"192.168.1.51"}'
assert_status "更新 v3 設備（不帶密碼）" 200
call POST /api/v1/devices/Core-V3 -d '{}'
assert_contains "未帶密碼時沿用原密碼" '"v3PrivConfigured" : true'
assert_contains "未帶版本時沿用 v3" '"snmpVersion" : "3"'

call POST /api/admin/devices -d '{"name":"Bad-Ver","host":"192.168.1.60","snmpVersion":"4"}'
assert_status "SNMP 版本不合法回 400" 400

call POST /api/admin/devices -d '{"name":"Bad-V3","host":"192.168.1.61","snmpVersion":"3","v3User":"u","v3PrivPassword":"privPass123"}'
assert_status "v3 只有加密沒有認證回 400" 400

call POST /api/admin/devices -d '{"name":"Bad-V3","host":"192.168.1.61","snmpVersion":"3","v3User":"u","v3AuthPassword":"short"}'
assert_status "v3 密碼少於 8 字元回 400" 400

call POST "/api/admin/devices/Core-V3" -d '{"action":"delete"}'
assert_status "刪除 v3 設備" 200

# ═════════════════════════════════════════════════════════════════
section "7. Trap OID 對應管理 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/trap-mappings
assert_status "列出 Trap OID 對應" 200
assert_contains "預設含 cpu" "cpu"
assert_contains "預設含 memory" "memory"
assert_contains "預設含 description" "description"

call POST /api/admin/trap-mappings \
  -d '{"metricName":"temperature","triggeredOid":"1.3.6.1.4.1.9999.4.1","resolvedOid":"1.3.6.1.4.1.9999.4.2","description":"溫度過高"}'
assert_status "新增自定 Trap 對應" 201

call GET /api/admin/trap-mappings
assert_contains "新增後含 temperature" "temperature"

TRAP_ID=$(jq_field '.[] | select(.metricName=="temperature") | .id')
TRAP_ID=${TRAP_ID:-4}
log "temperature trap-mapping id=$TRAP_ID"

call PUT "/api/admin/trap-mappings/${TRAP_ID}" \
  -d '{"metricName":"temperature","triggeredOid":"1.3.6.1.4.1.9999.4.1","resolvedOid":"1.3.6.1.4.1.9999.4.2","description":"溫度過高（更新）","enabled":true}'
assert_status "更新 Trap 對應" 200

call POST /api/admin/trap-mappings -d '{"metricName":"","triggeredOid":"1.3.6.1.4.1.9999.5.1"}'
assert_status "metricName 空白回 400" 400

call POST /api/admin/trap-mappings -d '{"metricName":"dup","triggeredOid":"1.3.6.1.4.1.9999.4.1"}'
assert_status "觸發 OID 重複回 409" 409

call DELETE "/api/admin/trap-mappings/${TRAP_ID}"
assert_status "刪除 Trap 對應" 200

call GET /api/admin/trap-mappings
assert_not_contains "刪除後 temperature 消失" "temperature"

# 新增無 resolvedOid（只有 triggered）
call POST /api/admin/trap-mappings \
  -d '{"metricName":"link-down","triggeredOid":"1.3.6.1.6.3.1.1.5.3","description":"連線中斷（無 resolved）"}'
assert_status "新增無 resolvedOid 的對應" 201

# 未定義 Trap / 未登錄來源的刪除
call DELETE /api/admin/unknown-traps/oids/999999
assert_status "刪除不存在的未定義 Trap 回 404" 404

call DELETE /api/admin/unknown-traps/sources/999999
assert_status "刪除不存在的未登錄來源回 404" 404

call DELETE /api/admin/unknown-traps/other/1
assert_status "未知的刪除路徑回 404" 404

call GET /api/admin/unknown-traps
assert_status "列出未定義 Trap" 200
assert_contains "回應含 unknownOids" "unknownOids"

# ═════════════════════════════════════════════════════════════════
section "8. 系統設定 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/settings
assert_status "列出系統設定" 200
assert_contains "含 web_port" "web_port"
assert_contains "含 trap_port" "trap_port"
assert_contains "含 poll_interval_seconds" "poll_interval_seconds"

call PUT /api/admin/settings/snmp_retries -d '{"value":"3"}'
assert_status "更新 snmp_retries" 200

call GET /api/admin/settings
assert_contains "snmp_retries 已更新為 3" '"3"'

call PUT /api/admin/settings/snmp_retries -d '{"value":""}'
assert_status "value 空白回 400" 400

call PUT /api/admin/settings/retention_metrics_days -d '{"value":"30"}'
assert_status "設定指標保留 30 天" 200
call GET /api/admin/settings
assert_contains "保留天數已更新" '"value" : "30"'

call PUT /api/admin/settings/retention_metrics_days -d '{"value":"-5"}'
assert_status "保留天數為負數回 400" 400

call PUT /api/admin/settings/retention_metrics_days -d '{"value":"abc"}'
assert_status "保留天數非整數回 400" 400

call PUT /api/admin/settings/no_such_setting -d '{"value":"1"}'
assert_status "不存在的設定回 404" 404

# ═════════════════════════════════════════════════════════════════
section "9. 閾值設定 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/thresholds
assert_status "列出閾值設定" 200
assert_contains "含 cpu" "cpu"
assert_contains "含 memory" "memory"
assert_contains "含 disk" "disk"

THRESHOLD_ID=$(jq_field '.[] | select(.metricName=="cpu") | .id')
THRESHOLD_ID=${THRESHOLD_ID:-1}
log "cpu threshold id=$THRESHOLD_ID"

call PUT "/api/admin/thresholds/${THRESHOLD_ID}" \
  -d '{"threshold":85,"rearm":75,"triggerCount":2,"rearmCount":2}'
assert_status "更新 cpu 閾值" 200

call GET /api/admin/thresholds
assert_contains "cpu threshold 更新為 85" '"threshold" : 85'

call PUT "/api/admin/thresholds/${THRESHOLD_ID}" \
  -d '{"threshold":110,"rearm":75,"triggerCount":2,"rearmCount":2}'
assert_status "threshold > 100 回 400" 400

call PUT "/api/admin/thresholds/${THRESHOLD_ID}" \
  -d '{"threshold":85,"rearm":90,"triggerCount":2,"rearmCount":2}'
assert_status "rearm >= threshold 回 400" 400

# ── 自訂指標閾值 ──────────────────────────────────────────────────
call POST /api/admin/thresholds -d '{"metricName":"free_ports","direction":"LOW","threshold":5,"rearm":8,"triggerCount":2,"rearmCount":2,"description":"可用埠數"}'
assert_status "新增自訂 LOW 閾值" 201
CUSTOM_THRESHOLD_ID=$(jq_field '.id')

call GET /api/admin/thresholds
assert_contains "清單含自訂閾值方向 LOW" '"direction" : "LOW"'

call POST /api/admin/thresholds -d '{"metricName":"free_ports","threshold":5,"rearm":8}'
assert_status "自訂閾值名稱重複回 409" 409

call POST /api/admin/thresholds -d '{"metricName":"cpu","threshold":90,"rearm":80}'
assert_status "以內建名稱新增回 409" 409

call POST /api/admin/thresholds -d '{"metricName":"temperature","direction":"LOW","threshold":5,"rearm":3}'
assert_status "LOW 方向 rearm <= threshold 回 400" 400

call PUT "/api/admin/thresholds/${CUSTOM_THRESHOLD_ID}" -d '{"direction":"HIGH","threshold":5000,"rearm":1000,"triggerCount":1,"rearmCount":1}'
assert_status "自訂閾值可改方向且不限百分比" 200

call DELETE "/api/admin/thresholds/${THRESHOLD_ID}"
assert_status "刪除內建閾值回 403" 403

call DELETE "/api/admin/thresholds/${CUSTOM_THRESHOLD_ID}"
assert_status "刪除自訂閾值" 200

# ═════════════════════════════════════════════════════════════════
section "10. SNMPv3 使用者 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/v3-users
assert_status "列出 v3 使用者" 200
assert_contains "預設含 trapUser" "trapUser"

call POST /api/admin/v3-users \
  -d '{"username":"testUser","authPassword":"authPass123","authProtocol":"SHA","privPassword":"privPass123","privProtocol":"AES128"}'
assert_status "新增 v3 使用者" 201

call GET /api/admin/v3-users
assert_contains "新增後含 testUser" "testUser"

V3_ID=$(jq_field '.[] | select(.username=="testUser") | .id')
V3_ID=${V3_ID:-2}

call PUT "/api/admin/v3-users/${V3_ID}" \
  -d '{"username":"testUser","authPassword":"newAuth123","authProtocol":"SHA","privPassword":"newPriv123","privProtocol":"AES128","enabled":true}'
assert_status "更新 v3 使用者" 200

call DELETE "/api/admin/v3-users/${V3_ID}"
assert_status "刪除 v3 使用者" 200

call GET /api/admin/v3-users
assert_not_contains "刪除後 testUser 消失" "testUser"

call POST /api/admin/v3-users -d '{"username":"","authPassword":"pass"}'
assert_status "username 空白回 400" 400

# ═════════════════════════════════════════════════════════════════
section "11. Poll OID 管理 API（已移除，改用監控範本）"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/poll-oids
assert_status "GET poll-oids 回 404" 404

call POST /api/admin/poll-oids -d '{"oid":"1.3.6.1.4.1.9999.1.1","name":"customMetric","groupName":"custom"}'
assert_status "POST poll-oids 回 404" 404

# ═════════════════════════════════════════════════════════════════
section "12. 未定義 Trap 查詢 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/unknown-traps
assert_status "GET /api/admin/unknown-traps" 200
assert_contains "回應含 unknownOids" '"unknownOids"'
assert_contains "回應含 unknownSources" '"unknownSources"'

# ═════════════════════════════════════════════════════════════════
section "13. 告警查詢 API"
# ═════════════════════════════════════════════════════════════════

call POST /api/v1/alerts -d '{}'
assert_status "POST /api/v1/alerts（無過濾）" 200

call POST /api/v1/alerts -d '{"device":"Router-01","limit":10}'
assert_status "POST /api/v1/alerts 帶 device/limit 參數" 200

call POST /api/v1/alerts -d '{"limit":"abc"}'
assert_status "limit 非數字靜默忽略回 200" 200

# ═════════════════════════════════════════════════════════════════
section "14. 狀態查詢 API"
# ═════════════════════════════════════════════════════════════════

call POST /api/v1/status -d '{}'
assert_status "POST /api/v1/status" 200

# ═════════════════════════════════════════════════════════════════
section "15. API Token 管理"
# ═════════════════════════════════════════════════════════════════

call POST /api/admin/tokens -d '{"description":"Test Token"}'
assert_status "產生 API Token" 201
assert_contains "回應含 token" '"token"'

TOKEN_VALUE=$(echo "$BODY" | jq -r '.token' 2>/dev/null || echo "")
log "token=$TOKEN_VALUE"

call GET /api/admin/tokens
assert_status "列出 Token" 200
assert_contains "含 Test Token" "Test Token"

TOKEN_ID=$(echo "$BODY" | jq -r '.[] | select(.description=="Test Token") | .id' 2>/dev/null | head -1)
TOKEN_ID=${TOKEN_ID:-1}
log "token id=$TOKEN_ID"

# 用 Bearer token 呼叫 /api/v1/status
if [[ -n "$TOKEN_VALUE" ]]; then
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST \
    -H "Authorization: Bearer $TOKEN_VALUE" \
    -H "Content-Type: application/json" \
    -d '{}' \
    "${BASE}/api/v1/status")
  if [[ "$STATUS" == "200" ]]; then
    ok "Bearer Token 呼叫 /api/v1/status 回 200"
  else
    fail "Bearer Token 呼叫 /api/v1/status 失敗 HTTP $STATUS"
  fi

  # Bearer token 不可存取 /api/admin/
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET \
    -H "Authorization: Bearer $TOKEN_VALUE" \
    "${BASE}/api/admin/tokens")
  if [[ "$STATUS" == "403" ]]; then
    ok "Bearer Token 存取 /api/admin/ 回 403"
  else
    fail "Bearer Token 存取 /api/admin/ 應回 403，實際 HTTP $STATUS"
  fi
fi

call POST /api/admin/tokens -d '{"description":""}'
assert_status "description 空白回 400" 400

call POST "/api/admin/tokens/${TOKEN_ID}" -d '{"action":"delete"}'
assert_status "撤銷 Token" 200

# ═════════════════════════════════════════════════════════════════
section "16. 稽核日誌 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/audit-logs
assert_status "GET /api/admin/audit-logs" 200

call POST /api/admin/audit-logs -d '{"eventType":"LOGIN_SUCCESS","limit":50}'
assert_status "POST /api/admin/audit-logs 帶過濾" 200
assert_contains "含 LOGIN_SUCCESS" "LOGIN_SUCCESS"

call POST /api/admin/audit-logs -d '{"eventType":"LOGIN_FAIL","limit":10}'
assert_status "查詢 LOGIN_FAIL" 200

# ═════════════════════════════════════════════════════════════════
section "16-0. 監控範本 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/profiles
assert_status "列出範本" 200
assert_contains "含內建 Net-SNMP 範本" "Net-SNMP"
assert_contains "含內建 Cisco IOS 範本" "Cisco IOS"

call POST /api/admin/profiles -d '{"name":"Switch-X","matchPrefix":"1.3.6.1.4.1.99999.1","metrics":[{"metricKey":"cpu","kind":"VALUE","params":{"oid":"1.3.6.1.4.1.99999.2.1.0"},"unit":"%"},{"metricKey":"temperature","kind":"VALUE","params":{"oid":"1.3.6.1.4.1.99999.3.1.0","scale":"0.1"},"unit":"°C"}]}'
assert_status "新增範本" 201
PROFILE_ID=$(jq_field '.id')

call POST /api/admin/profiles -d '{"name":"Switch-X","metrics":[]}'
assert_status "範本名稱重複回 409" 409

call POST /api/admin/profiles -d '{"name":"Bad","metrics":[{"metricKey":"CPU Temp","kind":"VALUE","params":{"oid":"1.1"}}]}'
assert_status "指標名稱不合法回 400" 400

call POST /api/admin/profiles -d '{"name":"Bad","metrics":[{"metricKey":"temp","kind":"EXPRESSION"}]}'
assert_status "不支援的計算類型回 400" 400

BUILTIN_ID=$(curl -s -b "$COOKIE_JAR" "${BASE}/api/admin/profiles" | jq -r '.[] | select(.builtin) | .id' | head -1)
call PUT "/api/admin/profiles/${BUILTIN_ID}" -d '{"name":"Hack","metrics":[]}'
assert_status "修改內建範本回 403" 403

call DELETE "/api/admin/profiles/${BUILTIN_ID}"
assert_status "刪除內建範本回 403" 403

call POST /api/admin/profiles/preview -d "{\"profileId\":${PROFILE_ID}}"
assert_status "預覽未指定設備回 400" 400

call POST /api/admin/profiles/preview -d "{\"deviceName\":\"NoSuchDevice\",\"profileId\":${PROFILE_ID}}"
assert_status "預覽不存在的設備回 404" 404

call POST "/api/admin/profiles/${BUILTIN_ID}/copy"
assert_status "複製內建範本" 201
assert_contains "複本名稱" "複本"

call POST /api/admin/devices -d "{\"name\":\"Profiled\",\"host\":\"192.168.1.70\",\"profileId\":${PROFILE_ID}}"
assert_status "新增設備並指定範本" 201
call POST /api/v1/devices/Profiled -d '{}'
assert_contains "設備套用指定範本" '"appliedProfileName" : "Switch-X"'

call POST /api/admin/devices -d '{"name":"BadProfile","host":"192.168.1.71","profileId":999999}'
assert_status "指定不存在的範本回 400" 400

call DELETE "/api/admin/profiles/${PROFILE_ID}"
assert_status "刪除自訂範本" 200
call POST /api/v1/devices/Profiled -d '{}'
assert_contains "範本刪除後設備改回自動（HOST-RESOURCES）" "HOST-RESOURCES"
call POST "/api/admin/devices/Profiled" -d '{"action":"delete"}'

# ═════════════════════════════════════════════════════════════════
section "16-1. OID 掃描 API"
# ═════════════════════════════════════════════════════════════════

call POST /api/admin/discovery/scans -d '{"deviceName":"NoSuchDevice"}'
assert_status "掃描不存在的設備回 404" 404

call POST /api/admin/discovery/scans -d '{"deviceName":"Router-01","walk":true,"subtrees":["1.3.6.1; drop"]}'
assert_status "子樹格式不正確回 400" 400

call GET /api/admin/discovery/scans
assert_status "列出掃描" 200

call GET /api/admin/discovery/scans/999999
assert_status "不存在的掃描回 404" 404

# ═════════════════════════════════════════════════════════════════
section "17. 登出後保護"
# ═════════════════════════════════════════════════════════════════

call POST /api/auth/logout
assert_status "登出" 200
assert_contains "登出訊息" "已登出"

call POST /api/v1/status -d '{}'
assert_status "登出後 POST /api/v1/status 回 401" 401

call GET /api/admin/users
assert_status "登出後 GET /api/admin/users 回 401" 401

call GET /api/admin/trap-mappings
assert_status "登出後 GET /api/admin/trap-mappings 回 401" 401

# ─────────────────────────────────────────────────────────────────
# 結果統計
# ─────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}══════════════════════════════${RESET}"
TOTAL=$((PASS + FAIL))
if [[ $FAIL -eq 0 ]]; then
  echo -e "${GREEN}${BOLD} 全部通過 $PASS / $TOTAL ${RESET}"
else
  echo -e "${RED}${BOLD} 失敗 $FAIL / $TOTAL，通過 $PASS / $TOTAL ${RESET}"
  echo ""
  warn "伺服器 log：/tmp/snmp-monitor-test.log"
fi
echo -e "${BOLD}══════════════════════════════${RESET}"
echo ""

[[ $FAIL -eq 0 ]]
