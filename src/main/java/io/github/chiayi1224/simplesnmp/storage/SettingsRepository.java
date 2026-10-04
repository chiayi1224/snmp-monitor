package io.github.chiayi1224.simplesnmp.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系統設定（app_settings，key-value）
 */
final class SettingsRepository {

    private static final Logger log = LoggerFactory.getLogger(SettingsRepository.class);

    private final Db db;

    SettingsRepository(Db db) {
        this.db = db;
    }

    /** 首次啟動寫入預設設定，並補上版本升級新增的 key */
    void seedDefaults() {
        try {
            if (db.queryLong("SELECT COUNT(*) FROM app_settings") == 0) {
                upsert("web_port",              "8080",  "Web 管理介面 Port（修改後需重啟）");
                upsert("trap_port",             "1162",  "SNMP Trap 接收 Port（修改後需重啟）");
                upsert("poll_interval_seconds", "60",    "SNMP Poll 間隔秒數（修改後需重啟）");
                upsert("snmp_retries",          "2",     "SNMP 重試次數");
                upsert("snmp_timeout_ms",       "3000",  "SNMP 逾時毫秒");
                upsert("ping_enabled",          "true",  "Poll 前是否先 Ping 確認主機可達");
                upsert("ping_timeout_ms",       "1000",  "Ping 逾時毫秒");
                log.info("已寫入預設系統設定");
            }
        } catch (Exception ex) {
            log.error("seedDefaultSettings 失敗: {}", ex.getMessage());
        }

        seedIfAbsent("https_enabled",     "false",        "HTTPS 是否啟用（修改後需重啟）");
        seedIfAbsent("https_port",        "8443",         "HTTPS Port（修改後需重啟）");
        seedIfAbsent("keystore_path",     "keystore.jks", "KeyStore 路徑（相對 JAR 目錄，修改後需重啟）");
        seedIfAbsent("keystore_password", "changeit",     "KeyStore 密碼（修改後需重啟）");
        seedIfAbsent("trap_communities",  "*",            "允許的 v1/v2c Trap community（逗號分隔，* 代表全部接受）");
    }

    /** 確保特定 setting key 存在（若已存在則不覆蓋），用於版本升級時新增設定 */
    private void seedIfAbsent(String key, String value, String description) {
        try {
            db.update("INSERT OR IGNORE INTO app_settings(key, value, description, updated) VALUES(?,?,?,?)",
                key, value, description, Db.now());
        } catch (Exception ex) {
            log.error("seedSettingIfAbsent 失敗: {}", ex.getMessage());
        }
    }

    /** 回傳 Map&lt;key, {key, value, description, updated}&gt; */
    Map<String, Object> queryAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = db.query(
                "SELECT key, value, description, updated FROM app_settings ORDER BY key", rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key",         rs.getString("key"));
                    row.put("value",       rs.getString("value"));
                    row.put("description", rs.getString("description"));
                    row.put("updated",     rs.getString("updated"));
                    return row;
                });
            for (Map<String, Object> row : rows) result.put((String) row.get("key"), row);
        } catch (SQLException ex) { log.error("queryAllSettings 失敗: {}", ex.getMessage()); }
        return result;
    }

    String get(String key, String defaultValue) {
        try {
            String value = db.queryOne("SELECT value FROM app_settings WHERE key=?", rs -> rs.getString("value"), key);
            if (value != null) return value;
        } catch (SQLException ex) { log.error("getSetting 失敗: {}", ex.getMessage()); }
        return defaultValue;
    }

    int getInt(String key, int defaultValue) {
        try { return Integer.parseInt(get(key, String.valueOf(defaultValue))); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    void upsert(String key, String value, String description) throws SQLException {
        String sql = """
            INSERT INTO app_settings(key, value, description, updated) VALUES(?,?,?,?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated=excluded.updated
            """;
        db.update(sql, key, value, description, Db.now());
    }
}
