package io.github.chiayi1224.simplesnmp.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 資料目錄：存放資料庫（snmp_monitor.db 及其 -wal／-shm）與 HTTPS keystore。
 *
 * 決定順序：系統屬性 {@code -Dsnmp.data.dir} → 環境變數 {@code SNMP_MONITOR_DATA_DIR} → JAR 所在目錄（預設，與舊版相同）。
 * 把資料放在程式目錄以外（例如 run.sh 使用的 data/），升級時替換 JAR 或執行 mvn clean 都不會影響資料。
 */
public final class DataDirectory {

    public static final String PROPERTY = "snmp.data.dir";
    public static final String ENV      = "SNMP_MONITOR_DATA_DIR";

    private DataDirectory() {}

    /** 依系統屬性與環境變數決定資料目錄 */
    public static Path resolve(Path jarDir) {
        return resolve(System.getProperty(PROPERTY), System.getenv(ENV), jarDir);
    }

    static Path resolve(String property, String env, Path jarDir) {
        String configured = !isBlank(property) ? property : !isBlank(env) ? env : null;
        Path dir = configured == null ? jarDir : Paths.get(configured.strip());
        return dir.toAbsolutePath().normalize();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
