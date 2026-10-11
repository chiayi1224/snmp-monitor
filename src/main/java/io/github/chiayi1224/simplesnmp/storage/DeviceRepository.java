package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.model.DeviceStatus;
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
        try {
            return db.query("SELECT * FROM devices WHERE enabled=1 ORDER BY name", DeviceRepository::toConfig);
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

    /** 依名稱載入單台設備的完整設定（含 v3 密碼，僅供內部連線使用），查無資料回傳 null */
    DeviceConfig loadByName(String name) {
        try {
            return db.queryOne("SELECT * FROM devices WHERE name=?", DeviceRepository::toConfig, name);
        } catch (SQLException ex) {
            log.error("loadDeviceByName 失敗: {}", ex.getMessage());
            return null;
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

    /** 新增設備（含 SNMP 版本與 v3 設定；null 密碼視為未設定） */
    void add(DeviceConfig d) throws SQLException {
        String now = Db.now();
        db.update("""
            INSERT INTO devices(name, host, port, community, retries, timeout_ms,
                                snmp_version, v3_user, v3_auth_protocol, v3_auth_password,
                                v3_priv_protocol, v3_priv_password, profile_id, disk_filter, enabled, created, updated)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
            """,
            d.getName(), d.getHost(), d.getPort(), d.getCommunity(), d.getRetries(), d.getTimeoutMs(),
            d.getSnmpVersion().label(), nz(d.getV3User()), d.getV3AuthProtocol().name(), nz(d.getV3AuthPassword()),
            d.getV3PrivProtocol().name(), nz(d.getV3PrivPassword()), d.getProfileId(), d.getDiskFilter(), now, now);
    }

    /** 依名稱更新設備；community 與 v3 密碼為 null 時沿用資料庫中的舊值 */
    void updateByName(DeviceConfig d, boolean enabled) throws SQLException {
        db.update("""
            UPDATE devices SET host=?, port=?, community=COALESCE(?, community), enabled=?, retries=?, timeout_ms=?,
                   snmp_version=?, v3_user=?, v3_auth_protocol=?,
                   v3_auth_password=COALESCE(?, v3_auth_password),
                   v3_priv_protocol=?,
                   v3_priv_password=COALESCE(?, v3_priv_password),
                   profile_id=?,
                   disk_filter=?,
                   updated=?
            WHERE name=?
            """,
            d.getHost(), d.getPort(), d.getCommunity(), Db.flag(enabled), d.getRetries(), d.getTimeoutMs(),
            d.getSnmpVersion().label(), nz(d.getV3User()), d.getV3AuthProtocol().name(),
            d.getV3AuthPassword(), d.getV3PrivProtocol().name(), d.getV3PrivPassword(),
            d.getProfileId(), d.getDiskFilter(), Db.now(), d.getName());
    }

    /** 輪詢時更新設備的 sysObjectID（未變動時不寫入） */
    void updateSysObjectId(String name, String sysObjectId) {
        try {
            db.update("UPDATE devices SET sys_object_id=? WHERE name=? AND sys_object_id != ?",
                sysObjectId, name, sysObjectId);
        } catch (SQLException ex) {
            log.error("updateSysObjectId 失敗: {}", ex.getMessage());
        }
    }

    /** 範本被刪除時，原本手動指定該範本的設備改回自動套用 */
    void clearProfile(long profileId) throws SQLException {
        db.update("UPDATE devices SET profile_id=NULL WHERE profile_id=?", profileId);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
        String lastSeen = DeviceStatus.OFFLINE.name().equals(status) ? "" : Db.now();
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
        row.put("enabled",   Db.bool(rs, "enabled"));
        row.put("retries",   rs.getInt("retries"));
        row.put("timeoutMs", rs.getLong("timeout_ms"));
        row.put("snmpVersion",      rs.getString("snmp_version"));
        row.put("v3User",           rs.getString("v3_user"));
        row.put("v3AuthProtocol",   rs.getString("v3_auth_protocol"));
        row.put("v3PrivProtocol",   rs.getString("v3_priv_protocol"));
        // 不回傳 community 與密碼，只回傳是否已設定
        row.put("communityConfigured", !rs.getString("community").isEmpty());
        row.put("v3AuthConfigured", !rs.getString("v3_auth_password").isEmpty());
        row.put("v3PrivConfigured", !rs.getString("v3_priv_password").isEmpty());
        row.put("profileId",        nullableLong(rs, "profile_id"));
        row.put("sysObjectId",      rs.getString("sys_object_id"));
        row.put("diskFilter",       rs.getString("disk_filter"));
        row.put("status",    rs.getString("status"));
        row.put("lastSeen",  rs.getString("last_seen"));
        row.put("created",   rs.getString("created"));
        row.put("updated",   rs.getString("updated"));
        return row;
    }

    private static DeviceConfig toConfig(ResultSet rs) throws SQLException {
        return new DeviceConfig.Builder(rs.getString("name"), rs.getString("host"))
            .port(rs.getInt("port"))
            .community(rs.getString("community"))
            .retries(rs.getInt("retries"))
            .timeoutMs(rs.getLong("timeout_ms"))
            .snmpVersion(parseVersion(rs.getString("snmp_version")))
            .v3User(rs.getString("v3_user"))
            .v3AuthProtocol(V3UserConfig.AuthProtocol.parse(rs.getString("v3_auth_protocol")))
            .v3AuthPassword(rs.getString("v3_auth_password"))
            .v3PrivProtocol(V3UserConfig.PrivProtocol.parse(rs.getString("v3_priv_protocol")))
            .v3PrivPassword(rs.getString("v3_priv_password"))
            .profileId(nullableLong(rs, "profile_id"))
            .sysObjectId(rs.getString("sys_object_id"))
            .diskFilter(rs.getString("disk_filter"))
            .build();
    }

    /** 資料庫中的版本值異常時以 v2c 處理並記錄警告，避免整台設備無法載入 */
    private static DeviceConfig.SnmpVersion parseVersion(String s) {
        DeviceConfig.SnmpVersion v = DeviceConfig.SnmpVersion.parse(s);
        if (v == null) {
            log.warn("設備 SNMP 版本 '{}' 不合法，以 2c 處理", s);
            return DeviceConfig.SnmpVersion.V2C;
        }
        return v;
    }
}
