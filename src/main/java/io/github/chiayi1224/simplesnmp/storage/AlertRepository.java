package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 告警事件（alert_events）
 *
 * 「未解除」的判定：每組「設備＋指標」最後一筆事件（以 id 排序）不是 RESOLVED。
 */
final class AlertRepository {

    private static final Logger log = LoggerFactory.getLogger(AlertRepository.class);

    /** 每組設備＋指標的最後一筆事件 id */
    private static final String LAST_IDS =
        "SELECT MAX(id) FROM alert_events WHERE event_type IN ('TRIGGERED','CHANGED','RESOLVED') "
            + "GROUP BY device_name, metric_name";

    private final Db db;

    AlertRepository(Db db) {
        this.db = db;
    }

    void save(AlertEvent event) {
        try {
            db.update("INSERT INTO alert_events(ts, device_name, metric_name, event_type, severity, value, threshold_val, message) "
                    + "VALUES(?,?,?,?,?,?,?,?)",
                event.getTs().toString(), event.getDeviceName(),
                event.getMetricName(), event.getType().name(), event.getSeverity().name(),
                event.getValue(), event.getThresholdVal(),
                event.getMessage());
        } catch (SQLException ex) { log.error("Failed to save alert event: {}", ex.getMessage()); }
    }

    /** 設備指標目前未解除告警的等級；沒有未解除告警回傳 null */
    Severity openSeverity(String deviceName, String metricName) {
        String sql = "SELECT event_type, severity FROM alert_events "
            + "WHERE device_name=? AND metric_name=? AND event_type IN ('TRIGGERED','CHANGED','RESOLVED') "
            + "ORDER BY id DESC LIMIT 1";
        try {
            String[] last = db.queryOne(sql, rs -> new String[]{ rs.getString("event_type"), rs.getString("severity") },
                deviceName, metricName);
            if (last == null || "RESOLVED".equals(last[0])) return null;
            Severity s = Severity.parse(last[1]);
            return s == null ? Severity.CRITICAL : s;
        } catch (SQLException ex) {
            log.error("Failed to query open alert {} / {}: {}", deviceName, metricName, ex.getMessage());
            return null;
        }
    }

    boolean hasOpen(String deviceName, String metricName) {
        return openSeverity(deviceName, metricName) != null;
    }

    /**
     * 刪除 cutoff 之前的告警記錄，回傳刪除筆數。
     * 每組「設備＋指標」最後一筆事件一律保留：未解除判定與重啟還原都依它判斷，
     * 刪掉會讓長期未恢復的告警被誤認為已結束。
     */
    int purgeBefore(String cutoff) throws SQLException {
        return db.update("DELETE FROM alert_events WHERE ts < ? AND id NOT IN (" + LAST_IDS + ")", cutoff);
    }

    /**
     * 查詢告警記錄（新到舊）。
     *
     * @param deviceName 設備名稱；null 代表全部
     * @param severity   等級；null 代表全部
     * @param activeOnly true 時只回傳未解除的告警（每組設備＋指標的最後一筆，且不是 RESOLVED）
     */
    List<Map<String, Object>> query(String deviceName, Severity severity, boolean activeOnly, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM alert_events WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (deviceName != null) { sql.append(" AND device_name=?"); params.add(deviceName); }
        if (severity != null)   { sql.append(" AND severity=?");    params.add(severity.name()); }
        if (activeOnly)         { sql.append(" AND event_type != 'RESOLVED' AND id IN (").append(LAST_IDS).append(")"); }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(limit);
        try {
            return db.query(sql.toString(), AlertRepository::toRow, params.toArray());
        } catch (SQLException ex) {
            log.error("Failed to query alerts: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 為設備中指標名稱符合條件的每個未解除告警記錄 RESOLVED（等級沿用解除前），回傳解除筆數。
     */
    int resolveOpen(String deviceName, Predicate<String> metricFilter, String message) throws SQLException {
        List<String[]> open = db.query(
            "SELECT metric_name, severity FROM alert_events WHERE device_name=? AND event_type != 'RESOLVED' "
                + "AND id IN (" + LAST_IDS + ")",
            rs -> new String[]{ rs.getString("metric_name"), rs.getString("severity") }, deviceName);
        int n = 0;
        for (String[] o : open) {
            if (!metricFilter.test(o[0])) continue;
            Severity s = Severity.parse(o[1]);
            save(AlertEvent.resolved(deviceName, o[0], s == null ? Severity.CRITICAL : s, 0, 0, message));
            n++;
        }
        return n;
    }

    private static Map<String, Object> toRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           rs.getLong("id"));
        m.put("ts",           rs.getString("ts"));
        m.put("deviceName",   rs.getString("device_name"));
        m.put("metricName",   rs.getString("metric_name"));
        m.put("eventType",    rs.getString("event_type"));
        m.put("severity",     rs.getString("severity"));
        m.put("value",        rs.getDouble("value"));
        m.put("thresholdVal", rs.getDouble("threshold_val"));
        m.put("message",      rs.getString("message"));
        return m;
    }
}
