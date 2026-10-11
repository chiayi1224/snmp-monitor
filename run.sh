#!/usr/bin/env bash
# 編譯並啟動 SNMP Monitor
#
# 資料（snmp_monitor.db 與 HTTPS keystore）放在資料目錄 data/（可用環境變數 SNMP_MONITOR_DATA_DIR 指定），
# 與編譯輸出 target/ 分開，因此 mvn clean 不會影響資料。每次啟動前先在 data/backups/ 備份資料庫，
# 預設保留最近 5 份（可用環境變數 SNMP_MONITOR_BACKUP_KEEP 調整，0 代表不備份）。
#
# 舊版 run.sh 把資料放在 target/；編譯前會先把 target/ 中的資料搬到資料目錄（已有同名檔案時改放備份目錄），
# 不會被 mvn clean 刪除。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${SCRIPT_DIR}/target"
DATA_DIR="${SNMP_MONITOR_DATA_DIR:-${SCRIPT_DIR}/data}"
BACKUP_KEEP="${SNMP_MONITOR_BACKUP_KEEP:-5}"
DB_NAME="snmp_monitor.db"

cd "${SCRIPT_DIR}"
mkdir -p "${DATA_DIR}"
DATA_DIR="$(cd "${DATA_DIR}" && pwd)"

# 服務仍在執行時不可搬移、備份或覆蓋它正在使用的資料庫（以「開著這個資料庫檔」判斷）
for pid in $(pgrep -x java || true); do
  for db in "${DATA_DIR}/${DB_NAME}" "${TARGET_DIR}/${DB_NAME}"; do
    if ls -l "/proc/${pid}/fd" 2>/dev/null | grep -qF -- "-> ${db}"; then
      echo "==> SNMP Monitor 仍在執行中（PID ${pid}，資料庫 ${db}），請先停止後再執行 run.sh" >&2
      exit 1
    fi
  done
done

# 保護 target/ 中的資料（舊版 run.sh 或直接執行 target/ 下的 JAR 時會留在這裡），避免被下面的 mvn clean 刪除：
#   資料目錄還沒有同名檔案 → 搬進資料目錄；已經有 → 搬到 data/backups/from-target-<時間>/，不覆蓋也不丟棄
STAMP="$(date +%Y%m%d-%H%M%S)"
LEFTOVER_DIR="${DATA_DIR}/backups/from-target-${STAMP}"

move_files() {   # move_files <目的目錄> <檔案…>（不存在的檔案略過）
  local dest="$1"; shift
  local f
  mkdir -p "${dest}"
  for f in "$@"; do
    if [[ -e "$f" ]]; then
      mv -n "$f" "${dest}/"
      echo "    $(basename "$f") → ${dest}"
    fi
  done
}

# 資料庫的主檔與 -wal／-shm 必須放在一起
if [[ -e "${TARGET_DIR}/${DB_NAME}" ]]; then
  if [[ -e "${DATA_DIR}/${DB_NAME}" ]]; then
    echo "==> 資料目錄已有資料庫，target/ 中的另一份資料庫保留到備份目錄"
    dest="${LEFTOVER_DIR}"
  else
    echo "==> 將 target/ 中的資料庫搬移到資料目錄"
    dest="${DATA_DIR}"
  fi
  move_files "${dest}" "${TARGET_DIR}/${DB_NAME}" "${TARGET_DIR}/${DB_NAME}-wal" "${TARGET_DIR}/${DB_NAME}-shm"
fi

shopt -s nullglob
for f in "${TARGET_DIR}"/*.jks "${TARGET_DIR}"/*.p12; do
  if [[ -e "${DATA_DIR}/$(basename "$f")" ]]; then
    echo "==> 資料目錄已有 $(basename "$f")，target/ 中的這份保留到備份目錄"
    move_files "${LEFTOVER_DIR}" "$f"
  else
    echo "==> 將 target/ 中的 $(basename "$f") 搬移到資料目錄"
    move_files "${DATA_DIR}" "$f"
  fi
done
shopt -u nullglob

echo "==> 編譯中..."
mvn clean package -q -DskipTests

JAR="$(ls "${TARGET_DIR}"/simplesnmp-*.jar 2>/dev/null | grep -v '/original-' | head -1 || true)"
if [[ -z "${JAR}" ]]; then
  echo "==> 找不到編譯產生的 JAR（${TARGET_DIR}/simplesnmp-*.jar）" >&2
  exit 1
fi

# 啟動前備份：程式已停止，直接複製資料庫檔（連同可能殘留的 -wal／-shm，確保內容完整）
if (( BACKUP_KEEP > 0 )) && [[ -e "${DATA_DIR}/${DB_NAME}" ]]; then
  BACKUP_ROOT="${DATA_DIR}/backups"
  BACKUP_DIR="${BACKUP_ROOT}/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "${BACKUP_DIR}"
  for f in "${DATA_DIR}/${DB_NAME}" "${DATA_DIR}/${DB_NAME}-wal" "${DATA_DIR}/${DB_NAME}-shm"; do
    if [[ -e "$f" ]]; then cp -p "$f" "${BACKUP_DIR}/"; fi
  done
  echo "==> 已備份資料庫到 ${BACKUP_DIR}"
  # 只保留最近 BACKUP_KEEP 份（目錄名稱為時間，依名稱排序即依時間排序）
  mapfile -t old < <(ls -1d "${BACKUP_ROOT}"/[0-9]*-[0-9]* 2>/dev/null | sort | head -n -"${BACKUP_KEEP}")
  for d in "${old[@]}"; do
    rm -rf -- "$d"
  done
fi

echo "==> 啟動伺服器（資料目錄：${DATA_DIR}）..."
exec java -Dsnmp.data.dir="${DATA_DIR}" -jar "${JAR}"
