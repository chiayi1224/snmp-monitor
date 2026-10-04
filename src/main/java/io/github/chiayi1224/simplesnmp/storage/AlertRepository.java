package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 閾值狀態變化事件（alert_events）
 */
final class AlertRepository {

    private static final Logger log = LoggerFactory.getLogger(AlertRepository.class);

    private final Db db;

    AlertRepository(Db db) {
        this.db = db;
    }

    void save(AlertEvent event) {
        try {
            db.update("INSERT INTO alert_events(ts, device_name, metric_name, event_type, value, threshold_val, message) VALUES(?,?,?,?,?,?,?)",
                event.getTs().toString(), event.getDeviceName(),
                event.getMetricName(), event.getType().name(),
                event.getValue(), event.getThresholdVal(),
                event.getMessage());
        } catch (SQLException ex) { log.error("saveAlertEvent: {}", ex.getMessage()); }
    }

    /** 最近一筆 TRIGGERED / RESOLVED 事件是否為 TRIGGERED */
    boolean hasOpen(String deviceName, String metricName) {
        String sql = "SELECT event_type FROM alert_events WHERE device_name=? AND metric_name=? AND event_type IN ('TRIGGERED','RESOLVED') ORDER BY ts DESC LIMIT 1";
        try {
            String last = db.queryOne(sql, rs -> rs.getString("event_type"), deviceName, metricName);
            return "TRIGGERED".equals(last);
        } catch (SQLException ex) {
            log.error("hasOpenAlert: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 刪除 cutoff 之前的告警記錄，回傳刪除筆數。
     * 每個「設備＋指標」最後一筆 TRIGGERED / RESOLVED 一律保留：
     * hasOpen() 依它判斷告警是否仍開啟，刪掉會讓長期未恢復的告警被誤認為已結束。
     */
    int purgeBefore(String cutoff) throws SQLException {
        return db.update("""
            DELETE FROM alert_events
            WHERE ts < ?
              AND id NOT IN (
                  SELECT id FROM alert_events a
                  WHERE event_type IN ('TRIGGERED','RESOLVED')
                    AND ts = (SELECT MAX(ts) FROM alert_events b
                              WHERE b.device_name = a.device_name AND b.metric_name = a.metric_name
                                AND b.event_type IN ('TRIGGERED','RESOLVED')))
            """, cutoff);
    }

    List<Map<String, Object>> query(String deviceName, int limit) {
        try {
            if (deviceName != null) {
                return db.query("SELECT * FROM alert_events WHERE device_name=? ORDER BY ts DESC LIMIT ?",
                    AlertRepository::toRow, deviceName, limit);
            }
            return db.query("SELECT * FROM alert_events ORDER BY ts DESC LIMIT ?", AlertRepository::toRow, limit);
        } catch (SQLException ex) {
            log.error("queryAlerts: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    private static Map<String, Object> toRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           rs.getLong("id"));
        m.put("ts",           rs.getString("ts"));
        m.put("deviceName",   rs.getString("device_name"));
        m.put("metricName",   rs.getString("metric_name"));
        m.put("eventType",    rs.getString("event_type"));
        m.put("value",        rs.getDouble("value"));
        m.put("thresholdVal", rs.getDouble("threshold_val"));
        m.put("message",      rs.getString("message"));
        return m;
    }
}
