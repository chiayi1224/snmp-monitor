package com.syscom.snmp.storage;

import com.syscom.snmp.config.DeviceConfig;
import com.syscom.snmp.config.OidConstants;
import com.syscom.snmp.config.TrapOidMapping;
import com.syscom.snmp.config.V3UserConfig;
import com.syscom.snmp.model.DiskInfo;
import com.syscom.snmp.threshold.AlertEvent;
import com.syscom.snmp.threshold.ThresholdConfig;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 *   users               — 管理帳號（密碼 BCrypt hash）
 *   devices             — 受監控設備清單
 *   trap_oid_mappings   — Trap OID ↔ 指標名稱對應
 *   cpu_metrics         — CPU 使用率歷史
 *   mem_metrics         — Memory 使用率歷史
 *   disk_metrics        — Disk 各掛載點歷史
 *   alert_events        — 閾值狀態變化
 *   app_settings        — 系統設定（key-value）
 *   threshold_settings  — 告警閾值設定
 *   snmp_v3_users       — SNMPv3 Trap 使用者
 */
public class MetricStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricStore.class);

    private final Connection conn;

    public MetricStore(String dbPath) throws Exception {
        // 確保父目錄存在（目錄不存在時自動建立，DB 檔由 SQLite JDBC 自動建立）
        if (!":memory:".equals(dbPath)) {
            Path parent = Paths.get(dbPath).toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.setAutoCommit(true);
        initSchema();
        migrateSchema();
        seedDefaultAdmin();
        seedDefaultTrapMappings();
        seedDefaultPollOids();
        seedDefaultSettings();
        seedHttpsSettings();
        seedDefaultThresholds();
        seedDefaultV3Users();
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
                    threshold_val REAL    NOT NULL,
                    message       TEXT    NOT NULL DEFAULT ''
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_alert ON alert_events(device_name, ts)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS trap_oid_mappings (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    metric_name   TEXT    NOT NULL,
                    triggered_oid TEXT    NOT NULL UNIQUE,
                    resolved_oid  TEXT,
                    description   TEXT    NOT NULL DEFAULT '',
                    enabled       INTEGER NOT NULL DEFAULT 1,
                    created       TEXT    NOT NULL,
                    updated       TEXT    NOT NULL
                )
            """);

            // 未定義 Trap 探索表：同一 trap_oid 只保留一筆，計次並記錄最後來源
            st.execute("""
                CREATE TABLE IF NOT EXISTS unknown_traps (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    trap_oid    TEXT    NOT NULL UNIQUE,
                    first_seen  TEXT    NOT NULL,
                    last_seen   TEXT    NOT NULL,
                    device_name TEXT    NOT NULL,
                    source_ip   TEXT    NOT NULL,
                    varbinds    TEXT    NOT NULL DEFAULT '',
                    count       INTEGER NOT NULL DEFAULT 1
                )
            """);

            // 未登錄來源 IP 探索表：同一 source_ip 只保留一筆，計次
            st.execute("""
                CREATE TABLE IF NOT EXISTS unknown_sources (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_ip     TEXT    NOT NULL UNIQUE,
                    first_seen    TEXT    NOT NULL,
                    last_seen     TEXT    NOT NULL,
                    last_trap_oid TEXT    NOT NULL DEFAULT '',
                    varbinds      TEXT    NOT NULL DEFAULT '',
                    count         INTEGER NOT NULL DEFAULT 1
                )
            """);

            // Poll OID 管理：scalar GET + disk WALK 的 OID 設定
            // group_name: system / cpu / memory / disk
            // poll_type:  SCALAR（單次 GET）/ WALK（SNMP Table Walk）
            st.execute("""
                CREATE TABLE IF NOT EXISTS poll_oids (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    oid         TEXT    NOT NULL UNIQUE,
                    name        TEXT    NOT NULL,
                    description TEXT    NOT NULL DEFAULT '',
                    group_name  TEXT    NOT NULL,
                    poll_type   TEXT    NOT NULL DEFAULT 'SCALAR',
                    enabled     INTEGER NOT NULL DEFAULT 1,
                    created     TEXT    NOT NULL,
                    updated     TEXT    NOT NULL
                )
            """);

            // 系統設定（key-value）
            st.execute("""
                CREATE TABLE IF NOT EXISTS app_settings (
                    key         TEXT NOT NULL PRIMARY KEY,
                    value       TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    updated     TEXT NOT NULL
                )
            """);

            // 告警閾值設定
            st.execute("""
                CREATE TABLE IF NOT EXISTS threshold_settings (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    metric_name   TEXT    NOT NULL UNIQUE,
                    threshold     REAL    NOT NULL,
                    rearm         REAL    NOT NULL,
                    trigger_count INTEGER NOT NULL,
                    rearm_count   INTEGER NOT NULL,
                    description   TEXT    NOT NULL DEFAULT '',
                    updated       TEXT    NOT NULL
                )
            """);

            // SNMPv3 Trap 使用者
            st.execute("""
                CREATE TABLE IF NOT EXISTS snmp_v3_users (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    username      TEXT    NOT NULL UNIQUE,
                    auth_password TEXT    NOT NULL DEFAULT '',
                    auth_protocol TEXT    NOT NULL DEFAULT 'SHA',
                    priv_password TEXT    NOT NULL DEFAULT '',
                    priv_protocol TEXT    NOT NULL DEFAULT 'AES128',
                    enabled       INTEGER NOT NULL DEFAULT 1,
                    created       TEXT    NOT NULL,
                    updated       TEXT    NOT NULL
                )
            """);

            // API Token
            st.execute("""
                CREATE TABLE IF NOT EXISTS api_tokens (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    token        TEXT    NOT NULL UNIQUE,
                    description  TEXT    NOT NULL,
                    enabled      INTEGER NOT NULL DEFAULT 1,
                    expires_at   TEXT,
                    created_at   TEXT    NOT NULL,
                    last_used_at TEXT
                )
            """);

            // 稽核日誌
            st.execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts         TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    actor      TEXT,
                    source_ip  TEXT,
                    method     TEXT,
                    path       TEXT,
                    status     INTEGER,
                    detail     TEXT
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_logs(ts DESC)");
        }
    }

    /** 既有 DB 的欄位補丁（向下相容） */
    private void migrateSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE alert_events ADD COLUMN message TEXT NOT NULL DEFAULT ''");
            log.info("schema 遷移：alert_events 新增 message 欄位");
        } catch (SQLException ignored) {
            // 欄位已存在時會拋例外，直接忽略
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE devices ADD COLUMN retries INTEGER NOT NULL DEFAULT 2");
        } catch (SQLException ignored) {}
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE devices ADD COLUMN timeout_ms INTEGER NOT NULL DEFAULT 3000");
        } catch (SQLException ignored) {}
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE devices ADD COLUMN status TEXT NOT NULL DEFAULT 'UNKNOWN'");
        } catch (SQLException ignored) {}
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE devices ADD COLUMN last_seen TEXT NOT NULL DEFAULT ''");
        } catch (SQLException ignored) {}
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

    /** 首次啟動時寫入預設 Trap OID 對應 */
    private void seedDefaultTrapMappings() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM trap_oid_mappings")) {
            if (rs.next() && rs.getInt(1) == 0) {
                for (TrapOidMapping.Mapping m : TrapOidMapping.defaults()) {
                    addTrapMapping(m.metricName(), m.triggeredOid(), m.resolvedOid(), m.description());
                }
                log.info("已寫入預設 Trap OID 對應 {} 筆", TrapOidMapping.defaults().size());
            }
        } catch (SQLException ex) {
            log.error("seedDefaultTrapMappings 失敗: {}", ex.getMessage());
        }
    }

    /** 首次啟動時寫入標準 Poll OID 預設值 */
    private void seedDefaultPollOids() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM poll_oids")) {
            if (rs.next() && rs.getInt(1) == 0) {
                addPollOid(OidConstants.SYS_UPTIME,  "sysUpTime",       "系統運行時間",       "system", "SCALAR");
                addPollOid(OidConstants.SYS_NAME,    "sysName",         "設備名稱",           "system", "SCALAR");
                addPollOid(OidConstants.CPU_USER,    "cpuUser",         "CPU 使用者態百分比", "cpu",    "SCALAR");
                addPollOid(OidConstants.CPU_SYSTEM,  "cpuSystem",       "CPU 核心態百分比",   "cpu",    "SCALAR");
                addPollOid(OidConstants.CPU_IDLE,    "cpuIdle",         "CPU 閒置百分比",     "cpu",    "SCALAR");
                addPollOid(OidConstants.MEM_TOTAL,   "memTotalSwap",    "記憶體總量 (KB)",    "memory", "SCALAR");
                addPollOid(OidConstants.MEM_FREE,    "memAvailSwap",    "可用記憶體 (KB)",    "memory", "SCALAR");
                addPollOid(OidConstants.MEM_BUFFER,  "memBuffer",       "Buffer 快取 (KB)",   "memory", "SCALAR");
                addPollOid(OidConstants.MEM_CACHE,   "memCached",       "Cached 快取 (KB)",   "memory", "SCALAR");
                addPollOid(OidConstants.HR_STORAGE_TYPE,  "hrStorageType",  "磁碟類型",       "disk", "WALK");
                addPollOid(OidConstants.HR_STORAGE_DESCR, "hrStorageDescr", "磁碟掛載點",     "disk", "WALK");
                addPollOid(OidConstants.HR_STORAGE_UNIT,  "hrStorageAllocationUnits", "分配單位 (bytes)", "disk", "WALK");
                addPollOid(OidConstants.HR_STORAGE_SIZE,  "hrStorageSize",  "磁碟總量 (units)", "disk", "WALK");
                addPollOid(OidConstants.HR_STORAGE_USED,  "hrStorageUsed",  "已用量 (units)",   "disk", "WALK");
                log.info("已寫入預設 Poll OID {} 筆", 14);
            }
        } catch (Exception ex) {
            log.error("seedDefaultPollOids 失敗: {}", ex.getMessage());
        }
    }

    // ─── 帳號 CRUD ─────────────────────────────────────────────────

    /**
     * 驗證帳號密碼，成功回傳帳號資訊 Map，失敗回傳 null
     */
    public Map<String, Object> authenticate(String username, String password) {
        String sql = "SELECT id, username, password_hash, role, must_change FROM users WHERE username=? AND enabled=1";
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
        String sql = "SELECT name, host, port, community, retries, timeout_ms FROM devices WHERE enabled=1 ORDER BY name";
        List<DeviceConfig> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new DeviceConfig(
                    rs.getString("name"), rs.getString("host"),
                    rs.getInt("port"), rs.getString("community"),
                    rs.getInt("retries"), rs.getLong("timeout_ms")));
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
                row.put("retries",   rs.getInt("retries"));
                row.put("timeoutMs", rs.getLong("timeout_ms"));
                row.put("status",    rs.getString("status"));
                row.put("lastSeen",  rs.getString("last_seen"));
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
        addDevice(name, host, port, community, 2, 3000L);
    }

    public void addDevice(String name, String host, int port, String community,
                           int retries, long timeoutMs) throws SQLException {
        String now = Instant.now().toString();
        String sql = "INSERT INTO devices(name, host, port, community, retries, timeout_ms, enabled, created, updated) VALUES(?,?,?,?,?,?,1,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name); ps.setString(2, host); ps.setInt(3, port);
            ps.setString(4, community); ps.setInt(5, retries); ps.setLong(6, timeoutMs);
            ps.setString(7, now); ps.setString(8, now);
            ps.executeUpdate();
        }
    }

    public void updateDevice(long id, String name, String host, int port, String community, boolean enabled) throws SQLException {
        // 保留舊的呼叫方式，retries/timeout_ms 維持原值
        String sql = "UPDATE devices SET name=?, host=?, port=?, community=?, enabled=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name); ps.setString(2, host); ps.setInt(3, port);
            ps.setString(4, community); ps.setInt(5, enabled ? 1 : 0);
            ps.setString(6, Instant.now().toString()); ps.setLong(7, id);
            ps.executeUpdate();
        }
    }

    public void updateDevice(long id, String name, String host, int port, String community,
                              boolean enabled, int retries, long timeoutMs) throws SQLException {
        String sql = "UPDATE devices SET name=?, host=?, port=?, community=?, enabled=?, retries=?, timeout_ms=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name); ps.setString(2, host); ps.setInt(3, port);
            ps.setString(4, community); ps.setInt(5, enabled ? 1 : 0);
            ps.setInt(6, retries); ps.setLong(7, timeoutMs);
            ps.setString(8, Instant.now().toString()); ps.setLong(9, id);
            ps.executeUpdate();
        }
    }

    public void deleteDevice(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM devices WHERE id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        }
    }

    /** 依名稱查詢單台設備 */
    public Map<String, Object> queryDeviceByName(String name) {
        String sql = "SELECT * FROM devices WHERE name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",        rs.getLong("id"));
                row.put("name",      rs.getString("name"));
                row.put("host",      rs.getString("host"));
                row.put("port",      rs.getInt("port"));
                row.put("community", rs.getString("community"));
                row.put("enabled",   rs.getInt("enabled") == 1);
                row.put("retries",   rs.getInt("retries"));
                row.put("timeoutMs", rs.getLong("timeout_ms"));
                row.put("status",    rs.getString("status"));
                row.put("lastSeen",  rs.getString("last_seen"));
                row.put("created",   rs.getString("created"));
                row.put("updated",   rs.getString("updated"));
                return row;
            }
        } catch (SQLException ex) { log.error("queryDeviceByName 失敗: {}", ex.getMessage()); }
        return null;
    }

    /** 依名稱更新設備 */
    public void updateDeviceByName(String name, String host, int port, String community,
                                    boolean enabled, int retries, long timeoutMs) throws SQLException {
        String sql = "UPDATE devices SET host=?, port=?, community=?, enabled=?, retries=?, timeout_ms=?, updated=? WHERE name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host); ps.setInt(2, port);
            ps.setString(3, community); ps.setInt(4, enabled ? 1 : 0);
            ps.setInt(5, retries); ps.setLong(6, timeoutMs);
            ps.setString(7, Instant.now().toString()); ps.setString(8, name);
            ps.executeUpdate();
        }
    }

    /** 依名稱刪除設備 */
    public void deleteDeviceByName(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM devices WHERE name=?")) {
            ps.setString(1, name); ps.executeUpdate();
        }
    }

    /** 更新設備連線狀態（ONLINE / OFFLINE / UNKNOWN） */
    public void updateDeviceStatus(String deviceName, String status) {
        String sql = "UPDATE devices SET status=?, last_seen=? WHERE name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, "OFFLINE".equals(status) ? "" : Instant.now().toString());
            ps.setString(3, deviceName);
            ps.executeUpdate();
        } catch (SQLException ex) { log.error("updateDeviceStatus 失敗: {}", ex.getMessage()); }
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
        String sql = "INSERT INTO alert_events(ts, device_name, metric_name, event_type, value, threshold_val, message) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.getTs().toString()); ps.setString(2, event.getDeviceName());
            ps.setString(3, event.getMetricName()); ps.setString(4, event.getType().name());
            ps.setDouble(5, event.getValue()); ps.setDouble(6, event.getThresholdVal());
            ps.setString(7, event.getMessage());
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
                m.put("thresholdVal", rs.getDouble("threshold_val"));
                m.put("message", rs.getString("message")); list.add(m);
            }
        } catch (SQLException ex) { log.error("queryAlerts: {}", ex.getMessage()); }
        return list;
    }

    // ─── Trap OID Mapping CRUD ────────────────────────────────────

    /** 載入所有啟用的 Trap OID 對應（供 TrapReceiver 使用） */
    public List<TrapOidMapping.Mapping> loadTrapMappings() {
        String sql = "SELECT metric_name, triggered_oid, resolved_oid, description FROM trap_oid_mappings WHERE enabled=1";
        List<TrapOidMapping.Mapping> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new TrapOidMapping.Mapping(
                    rs.getString("triggered_oid"),
                    rs.getString("resolved_oid"),
                    rs.getString("metric_name"),
                    rs.getString("description")
                ));
            }
        } catch (SQLException ex) { log.error("loadTrapMappings 失敗: {}", ex.getMessage()); }
        return list;
    }

    /** 查詢所有 Trap OID 對應（含 id，供 Web API 使用） */
    public List<Map<String, Object>> queryAllTrapMappings() {
        String sql = "SELECT * FROM trap_oid_mappings ORDER BY metric_name";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("metricName",   rs.getString("metric_name"));
                row.put("triggeredOid", rs.getString("triggered_oid"));
                row.put("resolvedOid",  rs.getString("resolved_oid"));
                row.put("description",  rs.getString("description"));
                row.put("enabled",      rs.getInt("enabled") == 1);
                row.put("created",      rs.getString("created"));
                row.put("updated",      rs.getString("updated"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryAllTrapMappings 失敗: {}", ex.getMessage()); }
        return list;
    }

    public void addTrapMapping(String metricName, String triggeredOid,
                                String resolvedOid, String description) throws SQLException {
        String now = Instant.now().toString();
        String sql = "INSERT INTO trap_oid_mappings(metric_name, triggered_oid, resolved_oid, description, enabled, created, updated) VALUES(?,?,?,?,1,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metricName); ps.setString(2, triggeredOid);
            ps.setString(3, resolvedOid == null || resolvedOid.isBlank() ? null : resolvedOid);
            ps.setString(4, description); ps.setString(5, now); ps.setString(6, now);
            ps.executeUpdate();
            log.info("新增 Trap OID 對應: {} triggered={}", metricName, triggeredOid);
        }
    }

    public void updateTrapMapping(long id, String metricName, String triggeredOid,
                                   String resolvedOid, String description, boolean enabled) throws SQLException {
        String sql = "UPDATE trap_oid_mappings SET metric_name=?, triggered_oid=?, resolved_oid=?, description=?, enabled=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metricName); ps.setString(2, triggeredOid);
            ps.setString(3, resolvedOid == null || resolvedOid.isBlank() ? null : resolvedOid);
            ps.setString(4, description); ps.setInt(5, enabled ? 1 : 0);
            ps.setString(6, Instant.now().toString()); ps.setLong(7, id);
            ps.executeUpdate();
        }
    }

    public void deleteTrapMapping(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM trap_oid_mappings WHERE id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        }
    }

    // ─── 未定義 Trap 探索 ─────────────────────────────────────────

    /**
     * 記錄未定義的 Trap OID（同一 OID 只保留一筆，重複出現則更新計次與最後來源）
     */
    public void saveUnknownTrap(String trapOid, String deviceName, String sourceIp, String varbinds) {
        String now = Instant.now().toString();
        // INSERT 若已存在則更新 last_seen / source_ip / varbinds / count
        String sql = """
            INSERT INTO unknown_traps(trap_oid, first_seen, last_seen, device_name, source_ip, varbinds, count)
            VALUES(?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(trap_oid) DO UPDATE SET
                last_seen   = excluded.last_seen,
                device_name = excluded.device_name,
                source_ip   = excluded.source_ip,
                varbinds    = excluded.varbinds,
                count       = count + 1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, trapOid); ps.setString(2, now); ps.setString(3, now);
            ps.setString(4, deviceName); ps.setString(5, sourceIp); ps.setString(6, varbinds);
            ps.executeUpdate();
        } catch (SQLException ex) { log.error("saveUnknownTrap 失敗: {}", ex.getMessage()); }
    }

    /** 查詢所有未定義 Trap 記錄，依最後出現時間降冪排列 */
    public List<Map<String, Object>> queryUnknownTraps() {
        String sql = "SELECT * FROM unknown_traps ORDER BY last_seen DESC";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",         rs.getLong("id"));
                row.put("trapOid",    rs.getString("trap_oid"));
                row.put("firstSeen",  rs.getString("first_seen"));
                row.put("lastSeen",   rs.getString("last_seen"));
                row.put("deviceName", rs.getString("device_name"));
                row.put("sourceIp",   rs.getString("source_ip"));
                row.put("varbinds",   rs.getString("varbinds"));
                row.put("count",      rs.getInt("count"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryUnknownTraps 失敗: {}", ex.getMessage()); }
        return list;
    }

    // ─── 未登錄來源 IP 探索 ───────────────────────────────────────

    /** 記錄未登錄來源 IP（同一 IP 只保留一筆，重複出現則更新計次） */
    public void saveUnknownSource(String sourceIp, String lastTrapOid, String varbinds) {
        String now = Instant.now().toString();
        String sql = """
            INSERT INTO unknown_sources(source_ip, first_seen, last_seen, last_trap_oid, varbinds, count)
            VALUES(?, ?, ?, ?, ?, 1)
            ON CONFLICT(source_ip) DO UPDATE SET
                last_seen     = excluded.last_seen,
                last_trap_oid = excluded.last_trap_oid,
                varbinds      = excluded.varbinds,
                count         = count + 1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceIp); ps.setString(2, now); ps.setString(3, now);
            ps.setString(4, lastTrapOid); ps.setString(5, varbinds);
            ps.executeUpdate();
        } catch (SQLException ex) { log.error("saveUnknownSource 失敗: {}", ex.getMessage()); }
    }

    /** 查詢所有未登錄來源記錄，依最後出現時間降冪 */
    public List<Map<String, Object>> queryUnknownSources() {
        String sql = "SELECT * FROM unknown_sources ORDER BY last_seen DESC";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("sourceIp",     rs.getString("source_ip"));
                row.put("firstSeen",    rs.getString("first_seen"));
                row.put("lastSeen",     rs.getString("last_seen"));
                row.put("lastTrapOid",  rs.getString("last_trap_oid"));
                row.put("varbinds",     rs.getString("varbinds"));
                row.put("count",        rs.getInt("count"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryUnknownSources 失敗: {}", ex.getMessage()); }
        return list;
    }

    // ─── Poll OID CRUD ────────────────────────────────────────────

    /** 載入所有啟用的 SCALAR OID 字串（供 SnmpPoller GET 使用） */
    public List<String> loadEnabledScalarOids() {
        String sql = "SELECT oid FROM poll_oids WHERE enabled=1 AND poll_type='SCALAR' ORDER BY group_name, name";
        List<String> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(rs.getString("oid"));
        } catch (SQLException ex) { log.error("loadEnabledScalarOids 失敗: {}", ex.getMessage()); }
        return list;
    }

    /** 磁碟 WALK 是否啟用（只要 disk group 中有任一筆啟用即視為啟用） */
    public boolean isDiskPollEnabled() {
        String sql = "SELECT COUNT(*) FROM poll_oids WHERE group_name='disk' AND enabled=1";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException ex) { log.error("isDiskPollEnabled 失敗: {}", ex.getMessage()); }
        return true;
    }

    /** 查詢所有 Poll OID（供 Web API 列表） */
    public List<Map<String, Object>> queryAllPollOids() {
        String sql = "SELECT * FROM poll_oids ORDER BY group_name, name";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",          rs.getLong("id"));
                row.put("oid",         rs.getString("oid"));
                row.put("name",        rs.getString("name"));
                row.put("description", rs.getString("description"));
                row.put("groupName",   rs.getString("group_name"));
                row.put("pollType",    rs.getString("poll_type"));
                row.put("enabled",     rs.getInt("enabled") == 1);
                row.put("created",     rs.getString("created"));
                row.put("updated",     rs.getString("updated"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryAllPollOids 失敗: {}", ex.getMessage()); }
        return list;
    }

    public void addPollOid(String oid, String name, String description,
                            String groupName, String pollType) throws SQLException {
        String now = Instant.now().toString();
        String sql = "INSERT INTO poll_oids(oid, name, description, group_name, poll_type, enabled, created, updated) VALUES(?,?,?,?,?,1,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, oid); ps.setString(2, name); ps.setString(3, description);
            ps.setString(4, groupName); ps.setString(5, pollType);
            ps.setString(6, now); ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    public void updatePollOid(long id, String oid, String name, String description,
                               String groupName, String pollType, boolean enabled) throws SQLException {
        String sql = "UPDATE poll_oids SET oid=?, name=?, description=?, group_name=?, poll_type=?, enabled=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, oid); ps.setString(2, name); ps.setString(3, description);
            ps.setString(4, groupName); ps.setString(5, pollType);
            ps.setInt   (6, enabled ? 1 : 0);
            ps.setString(7, Instant.now().toString()); ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    public void deletePollOid(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM poll_oids WHERE id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        }
    }

    // ─── app_settings ─────────────────────────────────────────────

    private void seedDefaultSettings() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM app_settings")) {
            if (rs.next() && rs.getInt(1) == 0) {
                upsertSetting("web_port",              "8080",  "Web 管理介面 Port（修改後需重啟）");
                upsertSetting("trap_port",             "1162",  "SNMP Trap 接收 Port（修改後需重啟）");
                upsertSetting("poll_interval_seconds", "60",    "SNMP Poll 間隔秒數（修改後需重啟）");
                upsertSetting("snmp_retries",          "2",     "SNMP 重試次數");
                upsertSetting("snmp_timeout_ms",       "3000",  "SNMP 逾時毫秒");
                upsertSetting("ping_enabled",          "true",  "Poll 前是否先 Ping 確認主機可達");
                upsertSetting("ping_timeout_ms",       "1000",  "Ping 逾時毫秒");
                log.info("已寫入預設系統設定");
            }
        } catch (Exception ex) {
            log.error("seedDefaultSettings 失敗: {}", ex.getMessage());
        }
    }

    /**
     * 確保特定 setting key 存在（若已存在則不覆蓋）。
     * 用於版本升級時新增設定。
     */
    private void seedSettingIfAbsent(String key, String value, String description) {
        try {
            String sql = "INSERT OR IGNORE INTO app_settings(key, value, description, updated) VALUES(?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key); ps.setString(2, value);
                ps.setString(3, description); ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            log.error("seedSettingIfAbsent 失敗: {}", ex.getMessage());
        }
    }

    private void seedHttpsSettings() {
        seedSettingIfAbsent("https_enabled",     "false",        "HTTPS 是否啟用（修改後需重啟）");
        seedSettingIfAbsent("https_port",        "8443",         "HTTPS Port（修改後需重啟）");
        seedSettingIfAbsent("keystore_path",     "keystore.jks", "KeyStore 路徑（相對 JAR 目錄，修改後需重啟）");
        seedSettingIfAbsent("keystore_password", "changeit",     "KeyStore 密碼（修改後需重啟）");
    }

    public Map<String, Object> queryAllSettings() {
        String sql = "SELECT key, value, description, updated FROM app_settings ORDER BY key";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key",         rs.getString("key"));
                row.put("value",       rs.getString("value"));
                row.put("description", rs.getString("description"));
                row.put("updated",     rs.getString("updated"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryAllSettings 失敗: {}", ex.getMessage()); }
        // 轉成 Map<key, {value, description, updated}>
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> row : list) {
            result.put((String)row.get("key"), row);
        }
        return result;
    }

    public String getSetting(String key, String defaultValue) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_settings WHERE key=?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException ex) { log.error("getSetting 失敗: {}", ex.getMessage()); }
        return defaultValue;
    }

    public int getSettingInt(String key, int defaultValue) {
        try { return Integer.parseInt(getSetting(key, String.valueOf(defaultValue))); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    public boolean getSettingBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(getSetting(key, String.valueOf(defaultValue)));
    }

    public void upsertSetting(String key, String value, String description) throws SQLException {
        String sql = """
            INSERT INTO app_settings(key, value, description, updated) VALUES(?,?,?,?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated=excluded.updated
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key); ps.setString(2, value);
            ps.setString(3, description); ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    // ─── threshold_settings ───────────────────────────────────────

    private void seedDefaultThresholds() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM threshold_settings")) {
            if (rs.next() && rs.getInt(1) == 0) {
                addThreshold("cpu",    90.0, 80.0, 3, 2, "CPU 使用率 (%)");
                addThreshold("memory", 85.0, 75.0, 3, 2, "記憶體使用率 (%)");
                addThreshold("disk",   80.0, 70.0, 2, 2, "磁碟使用率 (%)");
                log.info("已寫入預設閾值設定");
            }
        } catch (Exception ex) {
            log.error("seedDefaultThresholds 失敗: {}", ex.getMessage());
        }
    }

    public ThresholdConfig loadThresholdConfig(String metricName) {
        // disk:/ → 查 "disk"
        String key = metricName.startsWith("disk:") ? "disk" : metricName;
        String sql = "SELECT threshold, rearm, trigger_count, rearm_count FROM threshold_settings WHERE metric_name=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new ThresholdConfig.Builder(metricName)
                    .threshold(rs.getDouble("threshold"))
                    .rearmValue(rs.getDouble("rearm"))
                    .triggerCount(rs.getInt("trigger_count"))
                    .rearmCount(rs.getInt("rearm_count"))
                    .build();
            }
        } catch (SQLException ex) { log.error("loadThresholdConfig 失敗: {}", ex.getMessage()); }
        // fallback to default
        if ("cpu".equals(key)) return ThresholdConfig.cpu();
        if ("memory".equals(key)) return ThresholdConfig.memory();
        return ThresholdConfig.disk(metricName.startsWith("disk:") ? metricName.substring(5) : metricName);
    }

    public List<Map<String, Object>> queryAllThresholds() {
        String sql = "SELECT * FROM threshold_settings ORDER BY metric_name";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("metricName",   rs.getString("metric_name"));
                row.put("threshold",    rs.getDouble("threshold"));
                row.put("rearm",        rs.getDouble("rearm"));
                row.put("triggerCount", rs.getInt("trigger_count"));
                row.put("rearmCount",   rs.getInt("rearm_count"));
                row.put("description",  rs.getString("description"));
                row.put("updated",      rs.getString("updated"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryAllThresholds 失敗: {}", ex.getMessage()); }
        return list;
    }

    private void addThreshold(String metricName, double threshold, double rearm,
                               int triggerCount, int rearmCount, String description) throws SQLException {
        String sql = "INSERT INTO threshold_settings(metric_name, threshold, rearm, trigger_count, rearm_count, description, updated) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metricName); ps.setDouble(2, threshold); ps.setDouble(3, rearm);
            ps.setInt(4, triggerCount); ps.setInt(5, rearmCount);
            ps.setString(6, description); ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public void updateThreshold(long id, double threshold, double rearm,
                                 int triggerCount, int rearmCount) throws SQLException {
        String sql = "UPDATE threshold_settings SET threshold=?, rearm=?, trigger_count=?, rearm_count=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, threshold); ps.setDouble(2, rearm);
            ps.setInt(3, triggerCount); ps.setInt(4, rearmCount);
            ps.setString(5, Instant.now().toString()); ps.setLong(6, id);
            ps.executeUpdate();
        }
    }

    // ─── snmp_v3_users CRUD ───────────────────────────────────────

    private void seedDefaultV3Users() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM snmp_v3_users")) {
            if (rs.next() && rs.getInt(1) == 0) {
                addV3User("trapUser", "myAuthPass", "SHA", "myPrivPass", "AES128");
                log.info("已寫入預設 SNMPv3 使用者");
            }
        } catch (Exception ex) {
            log.error("seedDefaultV3Users 失敗: {}", ex.getMessage());
        }
    }

    public List<V3UserConfig> loadEnabledV3Users() {
        String sql = "SELECT username, auth_password, auth_protocol, priv_password, priv_protocol FROM snmp_v3_users WHERE enabled=1";
        List<V3UserConfig> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String user      = rs.getString("username");
                String authPass  = rs.getString("auth_password");
                String authProto = rs.getString("auth_protocol");
                String privPass  = rs.getString("priv_password");
                String privProto = rs.getString("priv_protocol");
                V3UserConfig.Builder b = new V3UserConfig.Builder(user);
                if (authPass != null && !authPass.isBlank()) {
                    b.authPassword(authPass);
                    b.authProtocol("MD5".equalsIgnoreCase(authProto)
                        ? V3UserConfig.AuthProtocol.MD5 : V3UserConfig.AuthProtocol.SHA);
                    if (privPass != null && !privPass.isBlank()) {
                        b.privPassword(privPass);
                        b.privProtocol("DES".equalsIgnoreCase(privProto)
                            ? V3UserConfig.PrivProtocol.DES : V3UserConfig.PrivProtocol.AES128);
                    }
                }
                list.add(b.build());
            }
        } catch (SQLException ex) { log.error("loadEnabledV3Users 失敗: {}", ex.getMessage()); }
        return list;
    }

    public List<Map<String, Object>> queryAllV3Users() {
        String sql = "SELECT id, username, auth_protocol, priv_protocol, enabled, created, updated FROM snmp_v3_users ORDER BY username";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("username",     rs.getString("username"));
                row.put("authProtocol", rs.getString("auth_protocol"));
                row.put("privProtocol", rs.getString("priv_protocol"));
                row.put("enabled",      rs.getInt("enabled") == 1);
                row.put("created",      rs.getString("created"));
                row.put("updated",      rs.getString("updated"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryAllV3Users 失敗: {}", ex.getMessage()); }
        return list;
    }

    public void addV3User(String username, String authPassword, String authProtocol,
                           String privPassword, String privProtocol) throws SQLException {
        String now = Instant.now().toString();
        String sql = "INSERT INTO snmp_v3_users(username, auth_password, auth_protocol, priv_password, priv_protocol, enabled, created, updated) VALUES(?,?,?,?,?,1,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username); ps.setString(2, authPassword); ps.setString(3, authProtocol);
            ps.setString(4, privPassword); ps.setString(5, privProtocol);
            ps.setString(6, now); ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    public void updateV3User(long id, String username, String authPassword, String authProtocol,
                              String privPassword, String privProtocol, boolean enabled) throws SQLException {
        String sql = "UPDATE snmp_v3_users SET username=?, auth_password=?, auth_protocol=?, priv_password=?, priv_protocol=?, enabled=?, updated=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username); ps.setString(2, authPassword); ps.setString(3, authProtocol);
            ps.setString(4, privPassword); ps.setString(5, privProtocol);
            ps.setInt(6, enabled ? 1 : 0); ps.setString(7, Instant.now().toString()); ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    public void deleteV3User(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM snmp_v3_users WHERE id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        }
    }

    // ─── api_tokens ───────────────────────────────────────────────

    /** 新增 Token，回傳 token 字串 */
    public String createApiToken(String description, String expiresAt) throws SQLException {
        String token = generateToken();
        String sql = """
            INSERT INTO api_tokens(token, description, enabled, expires_at, created_at)
            VALUES(?,?,1,?,?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, description);
            ps.setString(3, expiresAt);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
        return token;
    }

    /** 列出所有 token（不含 token 明文，只含 id/description/enabled/expires_at/created_at/last_used_at） */
    public List<Map<String, Object>> queryAllApiTokens() {
        String sql = "SELECT id, description, enabled, expires_at, created_at, last_used_at FROM api_tokens ORDER BY created_at DESC";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",          rs.getLong("id"));
                row.put("description", rs.getString("description"));
                row.put("enabled",     rs.getInt("enabled") == 1);
                row.put("expiresAt",   rs.getString("expires_at"));
                row.put("createdAt",   rs.getString("created_at"));
                row.put("lastUsedAt",  rs.getString("last_used_at"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryAllApiTokens 失敗: {}", ex.getMessage()); }
        return list;
    }

    /**
     * 驗證 token：token 存在、enabled=1、未過期 → 回傳 token 資訊 Map（含 description）
     * 否則回 null
     */
    public Map<String, Object> validateApiToken(String token) {
        String sql = """
            SELECT id, description, expires_at FROM api_tokens
            WHERE token=? AND enabled=1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            String expiresAt = rs.getString("expires_at");
            if (expiresAt != null && Instant.now().isAfter(Instant.parse(expiresAt))) return null;
            // 更新 last_used_at
            long id = rs.getLong("id");
            String desc = rs.getString("description");
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE api_tokens SET last_used_at=? WHERE id=?")) {
                upd.setString(1, Instant.now().toString());
                upd.setLong(2, id);
                upd.executeUpdate();
            }
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", id);
            info.put("description", desc);
            return info;
        } catch (SQLException ex) {
            log.error("validateApiToken 失敗: {}", ex.getMessage());
            return null;
        }
    }

    /** 更新 token 設定 */
    public void updateApiToken(long id, String description, boolean enabled, String expiresAt) throws SQLException {
        String sql = "UPDATE api_tokens SET description=?, enabled=?, expires_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, description);
            ps.setInt(2, enabled ? 1 : 0);
            ps.setString(3, expiresAt);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    /** 刪除 token */
    public void deleteApiToken(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM api_tokens WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return "snmp_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ─── audit_logs ───────────────────────────────────────────────

    /** 寫入稽核日誌（不拋例外，失敗只 log） */
    public void saveAuditLog(String eventType, String actor, String sourceIp,
                              String method, String path, int status, String detail) {
        String sql = """
            INSERT INTO audit_logs(ts, event_type, actor, source_ip, method, path, status, detail)
            VALUES(?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, eventType);
            ps.setString(3, actor);
            ps.setString(4, sourceIp);
            ps.setString(5, method);
            ps.setString(6, path);
            ps.setInt(7, status);
            ps.setString(8, detail);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("saveAuditLog 失敗: {}", ex.getMessage());
        }
    }

    /** 查詢稽核日誌 */
    public List<Map<String, Object>> queryAuditLogs(String eventType, String actor, int limit) {
        StringBuilder sb = new StringBuilder(
            "SELECT id, ts, event_type, actor, source_ip, method, path, status, detail FROM audit_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (eventType != null && !eventType.isBlank()) {
            sb.append(" AND event_type=?"); params.add(eventType);
        }
        if (actor != null && !actor.isBlank()) {
            sb.append(" AND actor LIKE ?"); params.add("%" + actor + "%");
        }
        sb.append(" ORDER BY ts DESC LIMIT ?");
        params.add(limit);

        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",        rs.getLong("id"));
                row.put("ts",        rs.getString("ts"));
                row.put("eventType", rs.getString("event_type"));
                row.put("actor",     rs.getString("actor"));
                row.put("sourceIp",  rs.getString("source_ip"));
                row.put("method",    rs.getString("method"));
                row.put("path",      rs.getString("path"));
                row.put("status",    rs.getInt("status"));
                row.put("detail",    rs.getString("detail"));
                list.add(row);
            }
        } catch (SQLException ex) { log.error("queryAuditLogs 失敗: {}", ex.getMessage()); }
        return list;
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
