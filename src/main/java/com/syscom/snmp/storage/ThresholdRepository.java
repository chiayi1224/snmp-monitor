package com.syscom.snmp.storage;

import com.syscom.snmp.threshold.ThresholdConfig;
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

    /** 載入指定指標的閾值；disk:/ 等掛載點統一查 "disk"，查無資料時回傳程式內建預設值 */
    ThresholdConfig load(String metricName) {
        boolean isDisk = metricName.startsWith(DISK_PREFIX);
        String key = isDisk ? "disk" : metricName;
        String sql = "SELECT threshold, rearm, trigger_count, rearm_count FROM threshold_settings WHERE metric_name=?";
        try {
            ThresholdConfig cfg = db.queryOne(sql, rs -> new ThresholdConfig.Builder(metricName)
                .threshold(rs.getDouble("threshold"))
                .rearmValue(rs.getDouble("rearm"))
                .triggerCount(rs.getInt("trigger_count"))
                .rearmCount(rs.getInt("rearm_count"))
                .build(), key);
            if (cfg != null) return cfg;
        } catch (SQLException ex) { log.error("loadThresholdConfig 失敗: {}", ex.getMessage()); }

        if ("cpu".equals(key)) return ThresholdConfig.cpu();
        if ("memory".equals(key)) return ThresholdConfig.memory();
        return ThresholdConfig.disk(isDisk ? metricName.substring(DISK_PREFIX.length()) : metricName);
    }

    List<Map<String, Object>> queryAll() {
        try {
            return db.query("SELECT * FROM threshold_settings ORDER BY metric_name", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("metricName",   rs.getString("metric_name"));
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

    void update(long id, double threshold, double rearm,
                int triggerCount, int rearmCount) throws SQLException {
        db.update("UPDATE threshold_settings SET threshold=?, rearm=?, trigger_count=?, rearm_count=?, updated=? WHERE id=?",
            threshold, rearm, triggerCount, rearmCount, Db.now(), id);
    }
}
