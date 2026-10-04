package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警閾值設定（threshold_settings）
 */
final class ThresholdRepository {

    private static final Logger log = LoggerFactory.getLogger(ThresholdRepository.class);

    private static final String DISK_PREFIX = "disk:";

    /** 內建三項閾值：不可刪除，值限定為百分比 */
    static final java.util.Set<String> BUILTIN = java.util.Set.of("cpu", "memory", "disk");

    private final Db db;

    ThresholdRepository(Db db) {
        this.db = db;
    }

    void seedDefaults() {
        try {
            if (db.queryLong("SELECT COUNT(*) FROM threshold_settings") == 0) {
                add("cpu",    90.0, 80.0, 3, 2, "CPU 使用率 (%)");
                add("memory", 85.0, 75.0, 3, 2, "記憶體使用率 (%)");
                add("disk",   80.0, 70.0, 2, 2, "磁碟使用率 (%)");
                log.info("已寫入預設閾值設定");
            }
        } catch (Exception ex) {
            log.error("seedDefaultThresholds 失敗: {}", ex.getMessage());
        }
    }

    /**
     * 載入指定指標的閾值；disk:/ 等掛載點統一查 "disk"。
     * cpu／memory／disk 查無資料時回傳程式內建預設值；自訂指標查無資料回傳 null（只儲存、不告警）。
     */
    ThresholdConfig load(String metricName) {
        boolean isDisk = metricName.startsWith(DISK_PREFIX);
        String key = isDisk ? "disk" : metricName;
        String sql = "SELECT threshold, rearm, trigger_count, rearm_count, direction FROM threshold_settings WHERE metric_name=?";
        try {
            ThresholdConfig cfg = db.queryOne(sql, rs -> new ThresholdConfig.Builder(metricName)
                .direction(directionOf(rs.getString("direction")))
                .threshold(rs.getDouble("threshold"))
                .rearmValue(rs.getDouble("rearm"))
                .triggerCount(rs.getInt("trigger_count"))
                .rearmCount(rs.getInt("rearm_count"))
                .build(), key);
            if (cfg != null) return cfg;
        } catch (SQLException ex) { log.error("loadThresholdConfig 失敗: {}", ex.getMessage()); }

        if ("cpu".equals(key)) return ThresholdConfig.cpu();
        if ("memory".equals(key)) return ThresholdConfig.memory();
        if (isDisk || "disk".equals(key)) return ThresholdConfig.disk(isDisk ? metricName.substring(DISK_PREFIX.length()) : metricName);
        return null;
    }

    private static ThresholdConfig.Direction directionOf(String s) {
        ThresholdConfig.Direction d = ThresholdConfig.Direction.parse(s);
        return d == null ? ThresholdConfig.Direction.HIGH : d;
    }

    List<Map<String, Object>> queryAll() {
        try {
            return db.query("SELECT * FROM threshold_settings ORDER BY metric_name", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("metricName",   rs.getString("metric_name"));
                row.put("direction",    directionOf(rs.getString("direction")).name());
                row.put("builtin",      BUILTIN.contains(rs.getString("metric_name")));
                row.put("threshold",    rs.getDouble("threshold"));
                row.put("rearm",        rs.getDouble("rearm"));
                row.put("triggerCount", rs.getInt("trigger_count"));
                row.put("rearmCount",   rs.getInt("rearm_count"));
                row.put("description",  rs.getString("description"));
                row.put("updated",      rs.getString("updated"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("queryAllThresholds 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    private void add(String metricName, double threshold, double rearm,
                     int triggerCount, int rearmCount, String description) throws SQLException {
        db.update("INSERT INTO threshold_settings(metric_name, threshold, rearm, trigger_count, rearm_count, description, updated) VALUES(?,?,?,?,?,?,?)",
            metricName, threshold, rearm, triggerCount, rearmCount, description, Db.now());
    }

    /** 指定編號的指標名稱；不存在回傳 null */
    String metricNameOf(long id) throws SQLException {
        return db.queryOne("SELECT metric_name FROM threshold_settings WHERE id=?", rs -> rs.getString(1), id);
    }

    boolean exists(String metricName) throws SQLException {
        return db.queryLong("SELECT COUNT(*) FROM threshold_settings WHERE metric_name=?", metricName) > 0;
    }

    /** 新增自訂指標閾值，回傳編號 */
    long create(String metricName, ThresholdConfig.Direction direction, double threshold, double rearm,
                int triggerCount, int rearmCount, String description) throws SQLException {
        db.update("INSERT INTO threshold_settings(metric_name, direction, threshold, rearm, trigger_count, rearm_count, description, updated) VALUES(?,?,?,?,?,?,?,?)",
            metricName, direction.name(), threshold, rearm, triggerCount, rearmCount, description, Db.now());
        return db.queryLong("SELECT id FROM threshold_settings WHERE metric_name=?", metricName);
    }

    void update(long id, ThresholdConfig.Direction direction, double threshold, double rearm,
                int triggerCount, int rearmCount) throws SQLException {
        db.update("UPDATE threshold_settings SET direction=?, threshold=?, rearm=?, trigger_count=?, rearm_count=?, updated=? WHERE id=?",
            direction.name(), threshold, rearm, triggerCount, rearmCount, Db.now(), id);
    }

    void delete(long id) throws SQLException {
        db.update("DELETE FROM threshold_settings WHERE id=?", id);
    }
}
