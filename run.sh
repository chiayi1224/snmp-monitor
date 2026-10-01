#!/usr/bin/env bash
# 編譯並啟動 SNMP Monitor
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${SCRIPT_DIR}/target/simplesnmp-1.0.0.jar"

cd "${SCRIPT_DIR}"

echo "==> 編譯中..."
mvn clean package -q -DskipTests

echo "==> 啟動伺服器..."
exec java -jar "${JAR}"
