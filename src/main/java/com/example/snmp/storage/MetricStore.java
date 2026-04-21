package com.example.snmp.storage;

import com.example.snmp.config.DeviceConfig;
import com.example.snmp.model.DiskInfo;
import com.example.snmp.threshold.AlertEvent;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 儲存
 *
 * 資料表：
 *   users         — 管理帳號（密碼 BCrypt hash）
 *   devices       — 受監控設備清單
 *   cpu_metrics   — CPU 使用率歷史
 *   mem_metrics   — Memory 使用率歷史
 *   disk_metrics  — Disk 各掛載點歷史
 *   alert_events  — 閾值狀態變化
 */
public class MetricStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricStore.class);

    private final Connection conn;

    public MetricStore(String dbPath) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.setAutoCommit(true);
        initSchema();
        seedDefaultAdmin();
        log.info("MetricStore 開啟: {}", dbPath);
    }

    // ─── Schema ────────────────────────────────────────────────────

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {

            // 管理帳號
            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    username     TEXT    NOT NULL UNIQUE,
                    password_hash TEXT   NOT NULL,
                    role         TEXT    NOT NULL DEFAULT 'admin',
                    enabled      INTEGER NOT NULL DEFAULT 1,
                    must_change  INTEGER NOT NULL DEFAULT 0,
                    created      TEXT    NOT NULL,
                    updated      TEXT    NOT NULL
                )
            """);

            // 設備清單
            st.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    name      TEXT    NOT NULL UNIQUE,
                    host      TEXT    NOT NULL,
                    port      INTEGER NOT NULL DEFAULT 161,
                    community TEXT    NOT NULL DEFAULT 'public',
                    enabled   INTEGER NOT NULL DEFAULT 1,
                    created   TEXT    NOT NULL,
                    updated   TEXT    NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS cpu_metrics (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts          TEXT    NOT NULL,
                    device_name TEXT    NOT NULL,
                    used_pct    REAL    NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_cpu ON cpu_metrics(device_name, ts)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS mem_metrics (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts          TEXT    NOT NULL,
                    device_name TEXT    NOT NULL,
                    used_pct    REAL    NOT NULL,
                    total_kb    INTEGER NOT NULL,
                    used_kb     INTEGER NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_mem ON mem_metrics(device_name, ts)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS disk_metrics (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts          TEXT    NOT NULL,
                    device_name TEXT    NOT NULL,
                    mount_point TEXT    NOT NULL,
                    total_bytes INTEGER NOT NULL,
                    used_bytes  INTEGER NOT NULL,
                    used_pct    REAL    NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_disk ON disk_metrics(device_name, ts)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS alert_events (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts            TEXT    NOT NULL,
                    device_name   TEXT    NOT NULL,
                    metric_name   TEXT    NOT NULL,
                    event_type    TEXT    NOT NULL,
                    value         REAL    NOT NULL,
                    threshold_val REAL    NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_alert ON alert_events(device_name, ts)");
        }
    }

    /**
     * 第一次啟動時若沒有任何帳號，自動建立預設管理員
     * 帳號：admin  密碼：admin（請登入後立即修改）
     */
    private void seedDefaultAdmin() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) == 0) {
                addUser("admin", "admin", "admin", true);
                log.warn("已建立預設帳號 admin/admin，請登入後立即修改密碼！");
            }
        } catch (SQLException ex) {
            log.error("seedDefaultAdmin 失敗: {}", ex.getMessage());
        }
    }

    // ─── 帳號 CRUD ─────────────────────────────────────────────────

    /**
     * 驗證帳號密碼，成功回傳帳號資訊 Map，失敗回傳 null
     */
    public Map<String, Object> authenticate(String username, String password) {
        String sql = "SELECT id, username, password_hash, role FROM users WHERE username=? AND enabled=1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String hash = rs.getString("password_hash");
                if (BCrypt.checkpw(password, hash)) {
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("id",       rs.getLong("id"));
                    user.put("username", rs.getString("username"));
                    user.put("role",     rs.getString("role"));
                    user.put("mustChange", rs.getInt("must_change") == 1);
                    return user;
                }
            }
        } catch (SQLException ex) {
            log.error("authenticate 失敗: {}", ex.getMessage());
        }
        return null;
    }

    /** 取得所有帳號（不含密碼 hash） */
    public List<Map<String, Object>> queryAllUsers() {
        String sql = "SELECT id, username, role, enabled, must_change, created, updated FROM users ORDER BY username";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",       rs.getLong("id"));
                row.put("username", rs.getString("username"));
                row.put("role",     rs.getString("role"));
                row.put("enabled",    rs.getInt("enabled") == 1);
                row.put("mustChange", rs.getInt("must_change") == 1);
                row.put("created",  rs.getString("created"));
                row.put("updated",  rs.getString("updated"));
                list.add(row);
            }
        } catch (SQLException ex) {
            log.error("queryAllUsers 失敗: {}", ex.getMessage());
        }
        return list;
    }

    /** 新增帳號 */
    public void addUser(String username, String password, String role) throws SQLException {
        addUser(username, password, role, false);
    }

    /** 新增帳號（mustChange=true 代表第一次登入必須改密碼） */
    public void addUser(String username, String password, String role, boolean mustChange) throws SQLException {
        String now  = Instant.now().toString();
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        String sql  = "INSERT INTO users(username, password_hash, role, enabled, must_change, created, updated) VALUES(?,?,?,1,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username); ps.setString(2, hash); ps.setString(3, role);
            ps.setInt   (4, mustChange ? 1 : 0);
            ps.setString(5, now);      ps.setString(6, now);
            ps.executeUpdate();
            log.info("新增帳號: {} ({}) mustChange={}", username, role, mustChange);
        }
    }

    /** 修改密碼，並清除強制改密碼旗標 */
    public void changePassword(long id, String newPassword) throws SQLException {
        String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        String sql  = "UPDATE users SET password_hash=?, must_change=0, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash); ps.setString(2, Instant.now().toString()); ps.setLong(3, id);
            ps.executeUpdate();
            log.info("帳號 id={} 密碼已更新，must_change 已清除", id);
        }
    }

    /** 更新帳號（不含密碼） */
    public void updateUser(long id, String username, String role, boolean enabled) throws SQLException {
        String sql = "UPDATE users SET username=?, role=?, enabled=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString (1, username);
            ps.setString (2, role);
            ps.setInt    (3, enabled ? 1 : 0);
            ps.setString (4, Instant.now().toString());
            ps.setLong   (5, id);
            ps.executeUpdate();
            log.info("更新帳號 id={}: {} enabled={}", id, username, enabled);
        }
    }

    /** 刪除帳號（保護：至少留一個啟用帳號）*/
    public void deleteUser(long id) throws SQLException {
        // 檢查刪除後是否還有啟用帳號
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE enabled=1 AND id != ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                throw new SQLException("至少需要保留一個啟用的帳號");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
            log.info("刪除帳號 id={}", id);
        }
    }

    // ─── 設備 CRUD ─────────────────────────────────────────────────

    public List<DeviceConfig> loadEnabledDevices() {
        String sql = "SELECT name, host, port, community FROM devices WHERE enabled=1 ORDER BY name";
        List<DeviceConfig> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new DeviceConfig(
                    rs.getString("name"), rs.getString("host"),
                    rs.getInt("port"), rs.getString("community"), 2, 3000L));
            }
        } catch (SQLException ex) {
            log.error("loadEnabledDevices 失敗: {}", ex.getMessage());
        }
        return list;
    }

    public List<Map<String, Object>> queryAllDevices() {
        String sql = "SELECT * FROM devices ORDER BY name";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",        rs.getLong("id"));
                row.put("name",      rs.getString("name"));
                row.put("host",      rs.getString("host"));
                row.put("port",      rs.getInt("port"));
                row.put("community", rs.getString("community"));
                row.put("enabled",   rs.getInt("enabled") == 1);
                row.put("created",   rs.getString("created"));
                row.put("updated",   rs.getString("updated"));
                list.add(row);
            }
        } catch (SQLException ex) {
            log.error("queryAllDevices 失敗: {}", ex.getMessage());
        }
        return list;
    }

    public void addDevice(String name, String host, int port, String community) throws SQLException {
        String now = Instant.now().toString();
        String sql = "INSERT INTO devices(name, host, port, community, enabled, created, updated) VALUES(?,?,?,?,1,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name); ps.setString(2, host); ps.setInt(3, port);
            ps.setString(4, community); ps.setString(5, now); ps.setString(6, now);
            ps.executeUpdate();
        }
    }

    public void updateDevice(long id, String name, String host, int port, String community, boolean enabled) throws SQLException {
        String sql = "UPDATE devices SET name=?, host=?, port=?, community=?, enabled=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name); ps.setString(2, host); ps.setInt(3, port);
            ps.setString(4, community); ps.setInt(5, enabled ? 1 : 0);
            ps.setString(6, Instant.now().toString()); ps.setLong(7, id);
            ps.executeUpdate();
        }
    }

    public void deleteDevice(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM devices WHERE id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        }
    }

    // ─── 寫入 metrics ──────────────────────────────────────────────

    public void saveCpu(String deviceName, double usedPct) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cpu_metrics(ts, device_name, used_pct) VALUES(?,?,?)")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, deviceName); ps.setDouble(3, usedPct);
            ps.executeUpdate();
        } catch (SQLException ex) { log.error("saveCpu: {}", ex.getMessage()); }
    }

    public void saveMemory(String deviceName, double usedPct, long totalKb, long usedKb) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mem_metrics(ts, device_name, used_pct, total_kb, used_kb) VALUES(?,?,?,?,?)")) {
            ps.setString(1, Instant.now().toString()); ps.setString(2, deviceName);
            ps.setDouble(3, usedPct); ps.setLong(4, totalKb); ps.setLong(5, usedKb);
            ps.executeUpdate();
        } catch (SQLException ex) { log.error("saveMemory: {}", ex.getMessage()); }
    }

    public void saveDisk(String deviceName, List<DiskInfo> disks) {
        String sql = "INSERT INTO disk_metrics(ts, device_name, mount_point, total_bytes, used_bytes, used_pct) VALUES(?,?,?,?,?,?)";
        String ts = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DiskInfo d : disks) {
                ps.setString(1, ts); ps.setString(2, deviceName); ps.setString(3, d.getMountPoint());
                ps.setLong(4, d.getTotalBytes()); ps.setLong(5, d.getUsedBytes());
                ps.setDouble(6, d.getUsedPercent()); ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) { log.error("saveDisk: {}", ex.getMessage()); }
    }

    public void saveAlertEvent(AlertEvent event) {
        String sql = "INSERT INTO alert_events(ts, device_name, metric_name, event_type, value, threshold_val) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.getTs().toString()); ps.setString(2, event.getDeviceName());
            ps.setString(3, event.getMetricName()); ps.setString(4, event.getType().name());
            ps.setDouble(5, event.getValue()); ps.setDouble(6, event.getThresholdVal());
            ps.executeUpdate();
        } catch (SQLException ex) { log.error("saveAlertEvent: {}", ex.getMessage()); }
    }

    public boolean hasOpenAlert(String deviceName, String metricName) {
        String sql = "SELECT event_type FROM alert_events WHERE device_name=? AND metric_name=? AND event_type IN ('TRIGGERED','RESOLVED') ORDER BY ts DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceName); ps.setString(2, metricName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return "TRIGGERED".equals(rs.getString("event_type"));
        } catch (SQLException ex) { log.error("hasOpenAlert: {}", ex.getMessage()); }
        return false;
    }

    // ─── 查詢（Web API）───────────────────────────────────────────

    public List<String> queryDeviceNames() {
        List<String> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT device_name FROM cpu_metrics ORDER BY device_name")) {
            while (rs.next()) list.add(rs.getString("device_name"));
        } catch (SQLException ex) { log.error("queryDeviceNames: {}", ex.getMessage()); }
        return list;
    }

    public List<Map<String, Object>> queryLatestStatus() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String device : queryDeviceNames()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceName", device);
            row.put("cpu",    queryLatestCpu(device));
            row.put("memory", queryLatestMemory(device));
            row.put("disks",  queryLatestDisks(device));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> queryLatestCpu(String device) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ts, used_pct FROM cpu_metrics WHERE device_name=? ORDER BY ts DESC LIMIT 1")) {
            ps.setString(1, device); ResultSet rs = ps.executeQuery();
            if (rs.next()) return Map.of("usedPct", rs.getDouble("used_pct"), "ts", rs.getString("ts"));
        } catch (SQLException ex) { log.error("queryLatestCpu: {}", ex.getMessage()); }
        return null;
    }

    private Map<String, Object> queryLatestMemory(String device) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ts, used_pct, total_kb, used_kb FROM mem_metrics WHERE device_name=? ORDER BY ts DESC LIMIT 1")) {
            ps.setString(1, device); ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("usedPct", rs.getDouble("used_pct")); m.put("totalKb", rs.getLong("total_kb"));
                m.put("usedKb", rs.getLong("used_kb")); m.put("ts", rs.getString("ts"));
                return m;
            }
        } catch (SQLException ex) { log.error("queryLatestMemory: {}", ex.getMessage()); }
        return null;
    }

    private List<Map<String, Object>> queryLatestDisks(String device) {
        String sql = "SELECT mount_point, used_pct, total_bytes, used_bytes, ts FROM disk_metrics WHERE device_name=? AND ts=(SELECT MAX(ts) FROM disk_metrics WHERE device_name=?) ORDER BY mount_point";
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, device); ps.setString(2, device);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mountPoint", rs.getString("mount_point")); m.put("usedPct", rs.getDouble("used_pct"));
                m.put("totalBytes", rs.getLong("total_bytes")); m.put("usedBytes", rs.getLong("used_bytes"));
                m.put("ts", rs.getString("ts")); list.add(m);
            }
        } catch (SQLException ex) { log.error("queryLatestDisks: {}", ex.getMessage()); }
        return list;
    }

    public List<Map<String, Object>> queryAlerts(String deviceName, int limit) {
        String sql = deviceName != null
            ? "SELECT * FROM alert_events WHERE device_name=? ORDER BY ts DESC LIMIT ?"
            : "SELECT * FROM alert_events ORDER BY ts DESC LIMIT ?";
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (deviceName != null) { ps.setString(1, deviceName); ps.setInt(2, limit); }
            else { ps.setInt(1, limit); }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id")); m.put("ts", rs.getString("ts"));
                m.put("deviceName", rs.getString("device_name")); m.put("metricName", rs.getString("metric_name"));
                m.put("eventType", rs.getString("event_type")); m.put("value", rs.getDouble("value"));
                m.put("thresholdVal", rs.getDouble("threshold_val")); list.add(m);
            }
        } catch (SQLException ex) { log.error("queryAlerts: {}", ex.getMessage()); }
        return list;
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
