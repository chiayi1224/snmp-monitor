package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每台設備的告警閾值（device_thresholds）
 *
 * 沒有預設值：查無設定代表該設備該指標只儲存、不告警。
 * 磁碟：disk:&lt;掛載點&gt; 有設定時使用它，否則使用設備的 disk 設定。
 */
final class ThresholdRepository {

    private static final Logger log = LoggerFactory.getLogger(ThresholdRepository.class);

    private static final String DISK = "disk";
    private static final String DISK_PREFIX = "disk:";

    private static final String COLUMNS =
        "device_name, metric_name, direction, warning, critical, rearm_margin, trigger_count, rearm_count, updated";

    private final Db db;

    ThresholdRepository(Db db) {
        this.db = db;
    }

    /** 載入設備指定指標的閾值；查無設定回傳 null。回傳設定的指標名稱維持呼叫端傳入的名稱（例如 disk:/data） */
    ThresholdConfig load(String deviceName, String metricName) {
        try {
            ThresholdConfig cfg = loadExact(deviceName, metricName, metricName);
            if (cfg == null && metricName.startsWith(DISK_PREFIX)) cfg = loadExact(deviceName, DISK, metricName);
            return cfg;
        } catch (SQLException ex) {
            log.error("Failed to load threshold {} / {}: {}", deviceName, metricName, ex.getMessage());
            return null;
        }
    }

    private ThresholdConfig loadExact(String deviceName, String key, String reportedName) throws SQLException {
        return db.queryOne("SELECT * FROM device_thresholds WHERE device_name=? AND metric_name=?",
            rs -> new ThresholdConfig.Builder(reportedName)
                .direction(directionOf(rs.getString("direction")))
                .warning(nullableDouble(rs, "warning"))
                .critical(nullableDouble(rs, "critical"))
                .rearmMargin(rs.getDouble("rearm_margin"))
                .triggerCount(rs.getInt("trigger_count"))
                .rearmCount(rs.getInt("rearm_count"))
                .build(),
            deviceName, key);
    }

    /** 設備的所有閾值（依指標名稱排序） */
    List<Map<String, Object>> queryByDevice(String deviceName) {
        try {
            return db.query("SELECT * FROM device_thresholds WHERE device_name=? ORDER BY metric_name",
                ThresholdRepository::toRow, deviceName);
        } catch (SQLException ex) {
            log.error("Failed to query thresholds of {}: {}", deviceName, ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 指定編號的閾值（含 deviceName、metricName）；不存在回傳 null */
    Map<String, Object> queryById(long id) throws SQLException {
        return db.queryOne("SELECT * FROM device_thresholds WHERE id=?", ThresholdRepository::toRow, id);
    }

    /** 每台設備的閾值筆數（設備名稱 → 筆數） */
    Map<String, Long> countByDevice() {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            db.query("SELECT device_name, COUNT(*) AS n FROM device_thresholds GROUP BY device_name",
                rs -> out.put(rs.getString("device_name"), rs.getLong("n")));
        } catch (SQLException ex) {
            log.error("Failed to count thresholds: {}", ex.getMessage());
        }
        return out;
    }

    /** 新增或取代設備的指標閾值，回傳編號 */
    long upsert(String deviceName, ThresholdConfig c) throws SQLException {
        db.update("INSERT INTO device_thresholds(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(device_name, metric_name) DO UPDATE SET direction=excluded.direction, "
                + "warning=excluded.warning, critical=excluded.critical, rearm_margin=excluded.rearm_margin, "
                + "trigger_count=excluded.trigger_count, rearm_count=excluded.rearm_count, updated=excluded.updated",
            deviceName, c.getMetricName(), c.getDirection().name(), c.getWarning(), c.getCritical(),
            c.getRearmMargin(), c.getTriggerCount(), c.getRearmCount(), Db.now());
        return db.queryLong("SELECT id FROM device_thresholds WHERE device_name=? AND metric_name=?",
            deviceName, c.getMetricName());
    }

    void delete(long id) throws SQLException {
        db.update("DELETE FROM device_thresholds WHERE id=?", id);
    }

    void deleteByDevice(String deviceName) throws SQLException {
        db.update("DELETE FROM device_thresholds WHERE device_name=?", deviceName);
    }

    /**
     * 把來源設備的全部閾值複製到目標設備：同名指標取代，目標設備有、來源沒有的保留。
     * 每個目標以單一 SQL 敘述完成（呼叫端須先確認所有目標設備存在）。
     */
    void copy(String fromDevice, List<String> toDevices) throws SQLException {
        for (String to : toDevices) {
            if (to.equals(fromDevice)) continue;
            db.update("INSERT INTO device_thresholds(" + COLUMNS + ") "
                    + "SELECT ?, metric_name, direction, warning, critical, rearm_margin, trigger_count, rearm_count, ? "
                    + "FROM device_thresholds WHERE device_name=? "
                    + "ON CONFLICT(device_name, metric_name) DO UPDATE SET direction=excluded.direction, "
                    + "warning=excluded.warning, critical=excluded.critical, rearm_margin=excluded.rearm_margin, "
                    + "trigger_count=excluded.trigger_count, rearm_count=excluded.rearm_count, updated=excluded.updated",
                to, Db.now(), fromDevice);
        }
    }

    private static Map<String, Object> toRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",           rs.getLong("id"));
        row.put("deviceName",   rs.getString("device_name"));
        row.put("metricName",   rs.getString("metric_name"));
        row.put("direction",    directionOf(rs.getString("direction")).name());
        row.put("warning",      nullableDouble(rs, "warning"));
        row.put("critical",     nullableDouble(rs, "critical"));
        row.put("rearmMargin",  rs.getDouble("rearm_margin"));
        row.put("triggerCount", rs.getInt("trigger_count"));
        row.put("rearmCount",   rs.getInt("rearm_count"));
        row.put("updated",      rs.getString("updated"));
        return row;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static ThresholdConfig.Direction directionOf(String s) {
        ThresholdConfig.Direction d = ThresholdConfig.Direction.parse(s);
        return d == null ? ThresholdConfig.Direction.HIGH : d;
    }
}
