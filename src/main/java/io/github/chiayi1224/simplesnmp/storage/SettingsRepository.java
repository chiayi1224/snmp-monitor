package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.i18n.Messages;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
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

    /** 設定說明的訊息代碼 */
    static Msg description(String key) {
        return Msg.of("setting." + key + ".description");
    }

    /** 寫入資料庫的說明：正體中文（顯示時由 API 依請求語系另行翻譯） */
    static String seedDescription(String key) {
        return Messages.get(description(key), Messages.ZH_TW);
    }

    /** 首次啟動寫入預設設定，並補上版本升級新增的 key */
    void seedDefaults() {
        try {
            if (db.queryLong("SELECT COUNT(*) FROM app_settings") == 0) {
                upsert("web_port",              "8080",  seedDescription("web_port"));
                upsert("trap_port",             "1162",  seedDescription("trap_port"));
                upsert("poll_interval_seconds", "60",    seedDescription("poll_interval_seconds"));
                upsert("snmp_retries",          "2",     seedDescription("snmp_retries"));
                upsert("snmp_timeout_ms",       "3000",  seedDescription("snmp_timeout_ms"));
                upsert("ping_enabled",          "true",  seedDescription("ping_enabled"));
                upsert("ping_timeout_ms",       "1000",  seedDescription("ping_timeout_ms"));
                log.info("Seeded default settings");
            }
        } catch (Exception ex) {
            log.error("Failed to seed default settings: {}", ex.getMessage());
        }

        seedIfAbsent("https_enabled",     "false",        seedDescription("https_enabled"));
        seedIfAbsent("https_port",        "8443",         seedDescription("https_port"));
        seedIfAbsent("keystore_path",     "keystore.jks", seedDescription("keystore_path"));
        seedIfAbsent("keystore_password", "changeit",     seedDescription("keystore_password"));
        seedIfAbsent("trap_communities",  "*",            seedDescription("trap_communities"));
        seedIfAbsent("retention_metrics_days", "90",  seedDescription("retention_metrics_days"));
        seedIfAbsent("retention_alerts_days",  "365", seedDescription("retention_alerts_days"));
        seedIfAbsent("retention_audit_days",   "365", seedDescription("retention_audit_days"));
    }

    /** 確保特定 setting key 存在（若已存在則不覆蓋），用於版本升級時新增設定 */
    private void seedIfAbsent(String key, String value, String description) {
        try {
            db.update("INSERT OR IGNORE INTO app_settings(key, value, description, updated) VALUES(?,?,?,?)",
                key, value, description, Db.now());
        } catch (Exception ex) {
            log.error("Failed to seed setting: {}", ex.getMessage());
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
        } catch (SQLException ex) { log.error("Failed to query settings: {}", ex.getMessage()); }
        return result;
    }

    String get(String key, String defaultValue) {
        try {
            String value = db.queryOne("SELECT value FROM app_settings WHERE key=?", rs -> rs.getString("value"), key);
            if (value != null) return value;
        } catch (SQLException ex) { log.error("Failed to read setting: {}", ex.getMessage()); }
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
