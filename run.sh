#!/usr/bin/env bash
# 編譯並啟動 SNMP Monitor
#
# 資料庫（snmp_monitor.db）與 HTTPS keystore 放在 JAR 同一目錄（target/），
# mvn clean 會清空 target/，因此編譯前先暫存這些檔案，編譯後（不論成功或失敗）再放回。
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${SCRIPT_DIR}/target"
JAR="${TARGET_DIR}/simplesnmp-1.0.0.jar"
KEEP_PATTERNS=("snmp_monitor.db" "snmp_monitor.db-*" "*.jks" "*.p12")

cd "${SCRIPT_DIR}"

# 服務仍在執行時編譯會刪掉它正在使用的資料庫（不論以絕對或相對路徑啟動，都以「開著這個資料庫檔」判斷）
for pid in $(pgrep -f "simplesnmp-1.0.0.jar" || true); do
  if ls -l "/proc/${pid}/fd" 2>/dev/null | grep -q "${TARGET_DIR}/snmp_monitor.db"; then
    echo "==> SNMP Monitor 仍在執行中（PID ${pid}），請先停止後再執行 run.sh" >&2
    exit 1
  fi
done

BACKUP_DIR="$(mktemp -d)"
restore() {
  shopt -s nullglob dotglob
  local files=("${BACKUP_DIR}"/*)
  if (( ${#files[@]} )); then
    mkdir -p "${TARGET_DIR}"
    mv "${files[@]}" "${TARGET_DIR}/"
  fi
  rmdir "${BACKUP_DIR}"
}
trap restore EXIT

shopt -s nullglob
for pattern in "${KEEP_PATTERNS[@]}"; do
  for f in "${TARGET_DIR}"/${pattern}; do
    [[ -e "$f" ]] || continue   # 沒有萬用字元的樣式即使檔案不存在也會原樣回傳
    mv "$f" "${BACKUP_DIR}/"
    echo "==> 暫存 $(basename "$f")"
  done
done
shopt -u nullglob

echo "==> 編譯中..."
mvn clean package -q -DskipTests

restore
trap - EXIT

echo "==> 啟動伺服器..."
exec java -jar "${JAR}"
