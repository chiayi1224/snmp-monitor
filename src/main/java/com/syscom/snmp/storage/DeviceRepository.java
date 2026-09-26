package com.syscom.snmp.storage;

import com.syscom.snmp.config.DeviceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受監控設備清單（devices）
 */
final class DeviceRepository {

    private static final Logger log = LoggerFactory.getLogger(DeviceRepository.class);

    private final Db db;

    DeviceRepository(Db db) {
        this.db = db;
    }

    List<DeviceConfig> loadEnabled() {
        String sql = "SELECT name, host, port, community, retries, timeout_ms FROM devices WHERE enabled=1 ORDER BY name";
        try {
            return db.query(sql, rs -> new DeviceConfig(
                rs.getString("name"), rs.getString("host"),
                rs.getInt("port"), rs.getString("community"),
                rs.getInt("retries"), rs.getLong("timeout_ms")));
        } catch (SQLException ex) {
            log.error("loadEnabledDevices 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    List<Map<String, Object>> queryAll() {
        try {
            return db.query("SELECT * FROM devices ORDER BY name", DeviceRepository::toRow);
        } catch (SQLException ex) {
            log.error("queryAllDevices 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 依名稱查詢單台設備，查無資料回傳 null */
    Map<String, Object> queryByName(String name) {
        try {
            return db.queryOne("SELECT * FROM devices WHERE name=?", DeviceRepository::toRow, name);
        } catch (SQLException ex) {
            log.error("queryDeviceByName 失敗: {}", ex.getMessage());
            return null;
        }
    }

    void add(String name, String host, int port, String community,
             int retries, long timeoutMs) throws SQLException {
        String now = Db.now();
        db.update("INSERT INTO devices(name, host, port, community, retries, timeout_ms, enabled, created, updated) VALUES(?,?,?,?,?,?,1,?,?)",
            name, host, port, community, retries, timeoutMs, now, now);
    }

    /** 保留舊的呼叫方式，retries/timeout_ms 維持原值 */
    void update(long id, String name, String host, int port, String community, boolean enabled) throws SQLException {
        db.update("UPDATE devices SET name=?, host=?, port=?, community=?, enabled=?, updated=? WHERE id=?",
            name, host, port, community, Db.flag(enabled), Db.now(), id);
    }

    void update(long id, String name, String host, int port, String community,
                boolean enabled, int retries, long timeoutMs) throws SQLException {
        db.update("UPDATE devices SET name=?, host=?, port=?, community=?, enabled=?, retries=?, timeout_ms=?, updated=? WHERE id=?",
            name, host, port, community, Db.flag(enabled), retries, timeoutMs, Db.now(), id);
    }

    void updateByName(String name, String host, int port, String community,
                      boolean enabled, int retries, long timeoutMs) throws SQLException {
        db.update("UPDATE devices SET host=?, port=?, community=?, enabled=?, retries=?, timeout_ms=?, updated=? WHERE name=?",
            host, port, community, Db.flag(enabled), retries, timeoutMs, Db.now(), name);
    }

    void delete(long id) throws SQLException {
        db.update("DELETE FROM devices WHERE id=?", id);
    }

    void deleteByName(String name) throws SQLException {
        db.update("DELETE FROM devices WHERE name=?", name);
    }

    /** 更新設備連線狀態（ONLINE / OFFLINE / UNKNOWN） */
    void updateStatus(String deviceName, String status) {
        String lastSeen = "OFFLINE".equals(status) ? "" : Db.now();
        try {
            db.update("UPDATE devices SET status=?, last_seen=? WHERE name=?", status, lastSeen, deviceName);
        } catch (SQLException ex) {
            log.error("updateDeviceStatus 失敗: {}", ex.getMessage());
        }
    }

    private static Map<String, Object> toRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",        rs.getLong("id"));
        row.put("name",      rs.getString("name"));
        row.put("host",      rs.getString("host"));
        row.put("port",      rs.getInt("port"));
        row.put("community", rs.getString("community"));
        row.put("enabled",   Db.bool(rs, "enabled"));
        row.put("retries",   rs.getInt("retries"));
        row.put("timeoutMs", rs.getLong("timeout_ms"));
        row.put("status",    rs.getString("status"));
        row.put("lastSeen",  rs.getString("last_seen"));
        row.put("created",   rs.getString("created"));
        row.put("updated",   rs.getString("updated"));
        return row;
    }
}
