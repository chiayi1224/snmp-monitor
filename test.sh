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
# 測試用 JAR 複製到暫存目錄執行：DB 與 logs 都在暫存目錄，不會動到 run.sh 使用的 target/snmp_monitor.db
WORK_DIR=""
DB_FILE=""
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
      [[ -n "$WORK_DIR" ]] && rm -rf "$WORK_DIR"
    fi
  elif [[ -n "$WORK_DIR" && "$KEEP_SERVER" != "true" ]]; then
    rm -rf "$WORK_DIR"
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
rm -f "$COOKIE_JAR"

# 8080 已有服務在執行（例如 run.sh 啟動的實例）時不可繼續，否則測試會打到正式資料
if curl -s -o /dev/null --max-time 2 "${BASE}/api/info"; then
  fail "${BASE} 已有服務在執行，請先停止後再測試（測試不會動到它的資料庫，但會與它搶用埠口）"
  exit 1
fi

WORK_DIR="$(mktemp -d /tmp/snmp-monitor-test.XXXXXX)"
cp "$JAR" "$WORK_DIR/simplesnmp.jar"
DB_FILE="${WORK_DIR}/snmp_monitor.db"
log "啟動伺服器（DB=$DB_FILE）..."

( cd "$WORK_DIR" && exec java -jar simplesnmp.jar ) > /tmp/snmp-monitor-test.log 2>&1 &
SERVER_PID=$!
log "PID=$SERVER_PID，等待就緒（最多 20 秒）..."

READY=false
for i in $(seq 1 20); do
  if curl -s -o /dev/null "${BASE}/api/info"; then
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
section "2-1. 網頁介面（前端外殼與快取）"
# ═════════════════════════════════════════════════════════════════

HOME_HEADERS=$(curl -s -D - -o /tmp/snmp-test-home.html "${BASE}/")
if echo "$HOME_HEADERS" | head -1 | grep -q " 200"; then ok "未登入可取得首頁"; else fail "首頁回應不是 200：$(echo "$HOME_HEADERS" | head -1)"; fi
if grep -q '<div id="app">' /tmp/snmp-test-home.html; then ok "首頁為前端單頁應用"; else fail "首頁內容不是前端單頁應用"; fi
if echo "$HOME_HEADERS" | grep -qi '^Cache-Control: no-cache'; then ok "首頁 Cache-Control: no-cache"; else fail "首頁缺少 no-cache"; fi
ASSET=$(grep -oE '/assets/[^"]+\.js' /tmp/snmp-test-home.html | head -1)
ASSET_HEADERS=$(curl -s -D - -o /dev/null "${BASE}${ASSET}")
if echo "$ASSET_HEADERS" | head -1 | grep -q " 200" && echo "$ASSET_HEADERS" | grep -qi 'immutable'; then
  ok "靜態資源可取得且長期快取（${ASSET}）"
else
  fail "靜態資源 ${ASSET} 回應或快取標頭不正確"
fi
rm -f /tmp/snmp-test-home.html
OLD_LOGIN=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "${BASE}/login.html")
if [[ "$OLD_LOGIN" == "302 ${BASE}/" ]]; then ok "舊的 /login.html 導向首頁"; else fail "舊登入頁未導向首頁：$OLD_LOGIN"; fi

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

# 3.1b 錯誤訊息依 Accept-Language
EN_BODY=$(curl -s -X POST -H "Content-Type: application/json" -H "Accept-Language: en" \
  -d '{"username":"admin","password":"wrong"}' "${BASE}/api/auth/login")
if echo "$EN_BODY" | grep -q "Incorrect username or password"; then ok "Accept-Language: en 回傳英文錯誤訊息"; else fail "英文錯誤訊息不正確：$EN_BODY"; fi
CN_BODY=$(curl -s -X POST -H "Content-Type: application/json" -H "Accept-Language: zh-CN" \
  -d '{"username":"admin","password":"wrong"}' "${BASE}/api/auth/login")
if echo "$CN_BODY" | grep -q "账号或密码错误"; then ok "Accept-Language: zh-CN 回傳簡體中文錯誤訊息"; else fail "簡體中文錯誤訊息不正確：$CN_BODY"; fi
UNAUTH_EN=$(curl -s -H "Accept-Language: en" "${BASE}/api/admin/devices")
if echo "$UNAUTH_EN" | grep -q "Not logged in"; then ok "未登入的 401 訊息依語系（英文）"; else fail "401 英文訊息不正確：$UNAUTH_EN"; fi

# 3.2 未登入呼叫受保護 API 回 401
call POST /api/v1/status -d '{}'
assert_status "未登入呼叫 API 回 401" 401

# 3.3 預設 admin 首次登入回 202（mustChange）
call POST /api/auth/login -d '{"username":"admin","password":"admin"}'
assert_status "首次登入回 202 mustChange" 202
assert_contains "回應含 mustChange:true" '"mustChange" : true'

# 3.3b must_change session 伺服器端把關：未改密碼前不得呼叫其他管理 API
call POST /api/admin/users -d '{"username":"evil","password":"Evil@123"}'
assert_status "mustChange 未改密碼前新增帳號回 403" 403

# 3.4 取得 admin id 並改密碼
call GET /api/auth/me
ADMIN_ID=$(jq_field '.id')
ADMIN_ID=${ADMIN_ID:-1}
log "admin id=$ADMIN_ID"

call PUT "/api/admin/users/${ADMIN_ID}/password" -d '{"password":"Admin@123"}'
assert_status "修改 admin 密碼" 200

# 3.4b 成功改自己密碼後，同一個 session（未重新登入）must_change 旗標已清除
call GET /api/admin/users
assert_status "改密碼後同一 session 列出帳號回 200" 200

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

# 5.1 admin 重設他人密碼 → 踢出該帳號現有 session，且下次登入須強制修改密碼
call GET /api/admin/users
OPER_ID=$(echo "$BODY" | jq -r '.[] | select(.username=="operator") | .id' 2>/dev/null)

# operator 先在另一個 cookie jar 登入（模擬另一台電腦上已登入的 session）
OPER_JAR="/tmp/snmp-test-oper-cookies.txt"
rm -f "$OPER_JAR"
oper_status() {
  curl -s -o /dev/null -w '%{http_code}' -b "$OPER_JAR" -c "$OPER_JAR" \
    -H "Content-Type: application/json" "$@"
}
S=$(oper_status -X POST -d '{"username":"operator","password":"Oper@123"}' "${BASE}/api/auth/login")
[[ "$S" == "200" ]] && ok "operator 另一個 session 登入 (HTTP $S)" || fail "operator 另一個 session 登入 — 實際 HTTP $S"

call PUT "/api/admin/users/${OPER_ID}/password" -d '{"password":"Reset@123"}'
assert_status "admin 重設 operator 密碼" 200
assert_contains "重設訊息提示須改密碼" "下次登入須修改密碼"

S=$(oper_status "${BASE}/api/auth/me")
[[ "$S" == "401" ]] && ok "重設後 operator 原 session 被踢出 (HTTP $S)" || fail "重設後 operator 原 session 被踢出 — 預期 HTTP 401，實際 HTTP $S"
rm -f "$OPER_JAR"

call POST /api/auth/login -d '{"username":"operator","password":"Reset@123"}'
assert_status "被重設的帳號登入回 202 mustChange" 202

call PUT "/api/admin/users/${OPER_ID}/password" -d '{"password":"Oper@456"}'
assert_status "operator 自己改密碼" 200

call GET /api/admin/users
assert_status "自己改密碼後同一 session 未被踢出" 200

# 5.2 不能刪除自己（目前是 operator 的 session）
call DELETE "/api/admin/users/${OPER_ID}"
assert_status "不能刪除自己回 400" 400

call POST /api/auth/login -d '{"username":"operator","password":"Oper@456"}'
assert_status "自己改密碼後登入不再強制改密碼（200）" 200

# 換回 admin session，後續測試沿用
call POST /api/auth/login -d '{"username":"admin","password":"Admin@123"}'
assert_status "切回 admin 登入" 200

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
assert_contains "單台設備標示 community 已設定" '"communityConfigured" : true'
assert_not_contains "回應不含 community 欄位" '"community" :'

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

# ── 磁碟過濾 ──────────────────────────────────────────────────────
call POST /api/admin/devices -d '{"name":"Disk-Filter","host":"192.168.1.70","diskFilter":" / , /data,, / "}'
assert_status "新增設備並設定磁碟過濾" 201
call POST /api/v1/devices/Disk-Filter -d '{}'
assert_contains "磁碟過濾條件已整理並儲存" '"diskFilter" : "/,/data"'

call POST "/api/admin/devices/Disk-Filter" -d '{"host":"192.168.1.71"}'
assert_status "更新設備（不帶 diskFilter）" 200
call POST /api/v1/devices/Disk-Filter -d '{}'
assert_contains "未帶 diskFilter 時沿用原值" '"diskFilter" : "/,/data"'

call POST "/api/admin/devices/Disk-Filter" -d '{"host":"192.168.1.71","diskFilter":""}'
assert_status "清除磁碟過濾" 200
call POST /api/v1/devices/Disk-Filter -d '{}'
assert_contains "磁碟過濾已清除（全部）" '"diskFilter" : ""'

LONG_FILTER=$(printf '/%.0s' $(seq 1 600))
call POST "/api/admin/devices/Disk-Filter" -d "{\"host\":\"192.168.1.71\",\"diskFilter\":\"${LONG_FILTER}a\"}"
assert_status "磁碟過濾條件過長回 400" 400

call POST "/api/admin/devices/Disk-Filter" -d '{"action":"delete"}'
assert_status "刪除磁碟過濾測試設備" 200

call POST /api/admin/devices -d '{"action":"probe-disks"}'
assert_status "探測磁碟缺少 host 回 400" 400
call POST /api/admin/devices/NoSuchDevice -d '{"action":"probe-disks","host":"127.0.0.1"}'
assert_status "探測不存在的設備回 404" 404
call POST /api/admin/devices -d '{"action":"probe-disks","host":"127.0.0.1","snmpVersion":"3"}'
assert_status "探測 v3 缺使用者回 400" 400

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

call POST /api/admin/trap-mappings -d '{"metricName":"fan","triggeredOid":"1.3.6.1.4.1.9999.6.1","severity":"WARNING"}'
assert_status "新增 WARNING 等級的 Trap 對應" 201
call GET /api/admin/trap-mappings
assert_contains "對應含等級 WARNING" '"severity" : "WARNING"'
assert_contains "未指定等級為 CRITICAL" '"severity" : "CRITICAL"'
call POST /api/admin/trap-mappings -d '{"metricName":"fan2","triggeredOid":"1.3.6.1.4.1.9999.6.2","severity":"INFO"}'
assert_status "Trap 對應等級不合法回 400" 400


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
section "9. 每台設備閾值 API"
# ═════════════════════════════════════════════════════════════════

call GET /api/admin/thresholds
assert_status "已移除的全域閾值 API 回 404" 404

call GET "/api/admin/device-thresholds?device=Router-01"
assert_status "列出 Router-01 閾值" 200
assert_contains "新設備沒有預設閾值" '"thresholds" : \[ \]'
assert_contains "可選指標含 disk" '"disk"'

call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"cpu","warning":80,"critical":95,"rearmMargin":5,"triggerCount":3,"rearmCount":2}'
assert_status "新增 Router-01 cpu 兩段閾值" 200
CPU_TH_ID=$(jq_field '.id')

call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"cpu","warning":null,"critical":90,"rearmMargin":10,"triggerCount":3,"rearmCount":2}'
assert_status "同名指標以新值取代" 200
call GET "/api/admin/device-thresholds?device=Router-01"
assert_contains "取代後 critical 為 90" '"critical" : 90'
assert_contains "取代後 warning 為 null" '"warning" : null'

call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"disk","critical":80,"rearmMargin":10,"triggerCount":2,"rearmCount":2}'
assert_status "新增磁碟設備預設" 200
call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"disk:/data","critical":95,"rearmMargin":5,"triggerCount":2,"rearmCount":2}'
assert_status "新增掛載點覆寫" 200
call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"free_ports","direction":"LOW","warning":10,"critical":3,"rearmMargin":2,"triggerCount":2,"rearmCount":2}'
assert_status "新增自訂 LOW 閾值" 200

call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"cpu"}'
assert_status "沒有任何觸發值回 400" 400
call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"cpu","warning":95,"critical":90}'
assert_status "Warning 高於 Critical 回 400" 400
call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"cpu","direction":"LOW","warning":20,"critical":10}'
assert_status "內建指標改 LOW 回 400" 400
call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"disk","critical":120}'
assert_status "百分比超過 100 回 400" 400
call PUT /api/admin/device-thresholds -d '{"device":"Router-01","metricName":"device_unreachable","critical":1}'
assert_status "保留名稱回 400" 400
call PUT /api/admin/device-thresholds -d '{"device":"NoSuchDevice","metricName":"cpu","critical":90}'
assert_status "設備不存在回 404" 404

call GET "/api/admin/device-thresholds?device=Switch-01"
assert_contains "Router-01 的閾值不影響 Switch-01" '"thresholds" : \[ \]'

call POST /api/admin/device-thresholds/copy -d '{"from":"Router-01","to":["Switch-01"]}'
assert_status "複製閾值到 Switch-01" 200
call GET "/api/admin/device-thresholds?device=Switch-01"
assert_contains "Switch-01 取得 disk:/data 覆寫" 'disk:/data'
assert_contains "Switch-01 取得 free_ports" 'free_ports'

call POST /api/admin/device-thresholds/copy -d '{"from":"Router-01","to":["Switch-01","NoSuchDevice"]}'
assert_status "目標不存在回 400" 400

call GET /api/admin/device-thresholds
assert_contains "閾值筆數統計含 Router-01" '"Router-01" : 4'

call DELETE "/api/admin/device-thresholds/${CPU_TH_ID}"
assert_status "刪除閾值" 200
call DELETE "/api/admin/device-thresholds/${CPU_TH_ID}"
assert_status "刪除不存在的閾值回 404" 404

# 連線中斷告警設定
call POST "/api/admin/devices/Switch-01" -d '{"host":"192.168.1.2","unreachableSeverity":"CRITICAL","unreachableCount":2}'
assert_status "設定連線中斷告警" 200
call POST /api/v1/devices/Switch-01 -d '{}'
assert_contains "連線中斷告警等級已儲存" '"unreachableSeverity" : "CRITICAL"'
assert_contains "連續次數已儲存" '"unreachableCount" : 2'
call POST "/api/admin/devices/Switch-01" -d '{"host":"192.168.1.2","unreachableSeverity":"INFO"}'
assert_status "連線中斷告警等級不合法回 400" 400

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

call POST /api/v1/alerts -d '{"severity":"CRITICAL","active":true}'
assert_status "依等級與未解除篩選" 200

call POST /api/v1/alerts -d '{"severity":"MAJOR"}'
assert_status "不合法的等級回 400" 400

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
