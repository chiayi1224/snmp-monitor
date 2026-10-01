package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.TrapOidMapping;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * SQLite 儲存（對外統一入口）
 *
 * 本類別負責開啟連線、建立 schema、寫入預設資料，實際的 SQL 依資料表分散於各 Repository：
 *   users               — {@link UserRepository}      管理帳號（密碼 BCrypt hash）
 *   devices             — {@link DeviceRepository}    受監控設備清單
 *   cpu/mem/disk_metrics— {@link MetricRepository}    輪詢指標歷史
 *   alert_events        — {@link AlertRepository}     閾值狀態變化
 *   trap_oid_mappings / unknown_traps / unknown_sources — {@link TrapRepository}
 *   poll_oids           — {@link PollOidRepository}   Poll OID 設定
 *   app_settings        — {@link SettingsRepository}  系統設定（key-value）
 *   threshold_settings  — {@link ThresholdRepository} 告警閾值設定
 *   snmp_v3_users       — {@link V3UserRepository}    SNMPv3 Trap 使用者
 *   api_tokens          — {@link ApiTokenRepository}  API Token
 *   audit_logs          — {@link AuditLogRepository}  稽核日誌
 */
public class MetricStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricStore.class);

    private final Connection conn;

    private final UserRepository      users;
    private final DeviceRepository    devices;
    private final MetricRepository    metrics;
    private final AlertRepository     alerts;
    private final TrapRepository      traps;
    private final PollOidRepository   pollOids;
    private final SettingsRepository  settings;
    private final ThresholdRepository thresholds;
    private final V3UserRepository    v3Users;
    private final ApiTokenRepository  apiTokens;
    private final AuditLogRepository  auditLogs;

    public MetricStore(String dbPath) throws Exception {
        // 確保父目錄存在（目錄不存在時自動建立，DB 檔由 SQLite JDBC 自動建立）
        if (!":memory:".equals(dbPath)) {
            Path parent = Paths.get(dbPath).toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.setAutoCommit(true);

        Db db = new Db(conn);
        SchemaManager schema = new SchemaManager(db);
        schema.initSchema();
        schema.migrateSchema();

        users      = new UserRepository(db);
        devices    = new DeviceRepository(db);
        metrics    = new MetricRepository(db);
        alerts     = new AlertRepository(db);
        traps      = new TrapRepository(db);
        pollOids   = new PollOidRepository(db);
        settings   = new SettingsRepository(db);
        thresholds = new ThresholdRepository(db);
        v3Users    = new V3UserRepository(db);
        apiTokens  = new ApiTokenRepository(db);
        auditLogs  = new AuditLogRepository(db);

        users.seedDefaults();
        traps.seedDefaults();
        pollOids.seedDefaults();
        settings.seedDefaults();
        thresholds.seedDefaults();
        v3Users.seedDefaults();
        log.info("MetricStore 開啟: {}", dbPath);
    }

    // ─── 帳號 ──────────────────────────────────────────────────────

    /** 驗證帳號密碼，成功回傳帳號資訊 Map，失敗回傳 null */
    public Map<String, Object> authenticate(String username, String password) {
        return users.authenticate(username, password);
    }

    /** 取得所有帳號（不含密碼 hash） */
    public List<Map<String, Object>> queryAllUsers() {
        return users.queryAll();
    }

    public void addUser(String username, String password, String role) throws SQLException {
        users.add(username, password, role, false);
    }

    /** 新增帳號（mustChange=true 代表第一次登入必須改密碼） */
    public void addUser(String username, String password, String role, boolean mustChange) throws SQLException {
        users.add(username, password, role, mustChange);
    }

    /** 修改密碼，並清除強制改密碼旗標 */
    public void changePassword(long id, String newPassword) throws SQLException {
        users.changePassword(id, newPassword);
    }

    /** 更新帳號（不含密碼） */
    public void updateUser(long id, String username, String role, boolean enabled) throws SQLException {
        users.update(id, username, role, enabled);
    }

    /** 刪除帳號（保護：至少留一個啟用帳號） */
    public void deleteUser(long id) throws SQLException {
        users.delete(id);
    }

    // ─── 設備 ──────────────────────────────────────────────────────

    public List<DeviceConfig> loadEnabledDevices() {
        return devices.loadEnabled();
    }

    public List<Map<String, Object>> queryAllDevices() {
        return devices.queryAll();
    }

    public void addDevice(String name, String host, int port, String community) throws SQLException {
        devices.add(name, host, port, community, 2, 3000L);
    }

    public void addDevice(String name, String host, int port, String community,
                          int retries, long timeoutMs) throws SQLException {
        devices.add(name, host, port, community, retries, timeoutMs);
    }

    /** 保留舊的呼叫方式，retries/timeout_ms 維持原值 */
    public void updateDevice(long id, String name, String host, int port, String community, boolean enabled) throws SQLException {
        devices.update(id, name, host, port, community, enabled);
    }

    public void updateDevice(long id, String name, String host, int port, String community,
                             boolean enabled, int retries, long timeoutMs) throws SQLException {
        devices.update(id, name, host, port, community, enabled, retries, timeoutMs);
    }

    public void deleteDevice(long id) throws SQLException {
        devices.delete(id);
    }

    /** 依名稱查詢單台設備 */
    public Map<String, Object> queryDeviceByName(String name) {
        return devices.queryByName(name);
    }

    /** 依名稱更新設備 */
    public void updateDeviceByName(String name, String host, int port, String community,
                                   boolean enabled, int retries, long timeoutMs) throws SQLException {
        devices.updateByName(name, host, port, community, enabled, retries, timeoutMs);
    }

    /** 依名稱刪除設備 */
    public void deleteDeviceByName(String name) throws SQLException {
        devices.deleteByName(name);
    }

    /** 更新設備連線狀態（ONLINE / OFFLINE / UNKNOWN） */
    public void updateDeviceStatus(String deviceName, String status) {
        devices.updateStatus(deviceName, status);
    }

    // ─── 指標 / 告警 ───────────────────────────────────────────────

    public void saveCpu(String deviceName, double usedPct) {
        metrics.saveCpu(deviceName, usedPct);
    }

    public void saveMemory(String deviceName, double usedPct, long totalKb, long usedKb) {
        metrics.saveMemory(deviceName, usedPct, totalKb, usedKb);
    }

    public void saveDisk(String deviceName, List<DiskInfo> disks) {
        metrics.saveDisk(deviceName, disks);
    }

    public void saveAlertEvent(AlertEvent event) {
        alerts.save(event);
    }

    public boolean hasOpenAlert(String deviceName, String metricName) {
        return alerts.hasOpen(deviceName, metricName);
    }

    public List<String> queryDeviceNames() {
        return metrics.queryDeviceNames();
    }

    public List<Map<String, Object>> queryLatestStatus() {
        return metrics.queryLatestStatus();
    }

    public List<Map<String, Object>> queryAlerts(String deviceName, int limit) {
        return alerts.query(deviceName, limit);
    }

    // ─── Trap OID Mapping ─────────────────────────────────────────

    /** 載入所有啟用的 Trap OID 對應（供 TrapReceiver 使用） */
    public List<TrapOidMapping.Mapping> loadTrapMappings() {
        return traps.loadMappings();
    }

    /** 查詢所有 Trap OID 對應（含 id，供 Web API 使用） */
    public List<Map<String, Object>> queryAllTrapMappings() {
        return traps.queryAllMappings();
    }

    public void addTrapMapping(String metricName, String triggeredOid,
                               String resolvedOid, String description) throws SQLException {
        traps.addMapping(metricName, triggeredOid, resolvedOid, description);
    }

    public void updateTrapMapping(long id, String metricName, String triggeredOid,
                                  String resolvedOid, String description, boolean enabled) throws SQLException {
        traps.updateMapping(id, metricName, triggeredOid, resolvedOid, description, enabled);
    }

    public void deleteTrapMapping(long id) throws SQLException {
        traps.deleteMapping(id);
    }

    // ─── 未定義 Trap / 未登錄來源探索 ─────────────────────────────

    /** 記錄未定義的 Trap OID（同一 OID 只保留一筆，重複出現則更新計次與最後來源） */
    public void saveUnknownTrap(String trapOid, String deviceName, String sourceIp, String varbinds) {
        traps.saveUnknownTrap(trapOid, deviceName, sourceIp, varbinds);
    }

    /** 查詢所有未定義 Trap 記錄，依最後出現時間降冪排列 */
    public List<Map<String, Object>> queryUnknownTraps() {
        return traps.queryUnknownTraps();
    }

    /** 記錄未登錄來源 IP（同一 IP 只保留一筆，重複出現則更新計次） */
    public void saveUnknownSource(String sourceIp, String lastTrapOid, String varbinds) {
        traps.saveUnknownSource(sourceIp, lastTrapOid, varbinds);
    }

    /** 查詢所有未登錄來源記錄，依最後出現時間降冪 */
    public List<Map<String, Object>> queryUnknownSources() {
        return traps.queryUnknownSources();
    }

    // ─── Poll OID ─────────────────────────────────────────────────

    /** 載入所有啟用的 SCALAR OID 字串（供 SnmpPoller GET 使用） */
    public List<String> loadEnabledScalarOids() {
        return pollOids.loadEnabledScalarOids();
    }

    /** 磁碟 WALK 是否啟用（只要 disk group 中有任一筆啟用即視為啟用） */
    public boolean isDiskPollEnabled() {
        return pollOids.isDiskPollEnabled();
    }

    /** 查詢所有 Poll OID（供 Web API 列表） */
    public List<Map<String, Object>> queryAllPollOids() {
        return pollOids.queryAll();
    }

    public void addPollOid(String oid, String name, String description,
                           String groupName, String pollType) throws SQLException {
        pollOids.add(oid, name, description, groupName, pollType);
    }

    public void updatePollOid(long id, String oid, String name, String description,
                              String groupName, String pollType, boolean enabled) throws SQLException {
        pollOids.update(id, oid, name, description, groupName, pollType, enabled);
    }

    public void deletePollOid(long id) throws SQLException {
        pollOids.delete(id);
    }

    // ─── app_settings ─────────────────────────────────────────────

    public Map<String, Object> queryAllSettings() {
        return settings.queryAll();
    }

    public String getSetting(String key, String defaultValue) {
        return settings.get(key, defaultValue);
    }

    public int getSettingInt(String key, int defaultValue) {
        return settings.getInt(key, defaultValue);
    }

    public boolean getSettingBool(String key, boolean defaultValue) {
        return settings.getBool(key, defaultValue);
    }

    public void upsertSetting(String key, String value, String description) throws SQLException {
        settings.upsert(key, value, description);
    }

    // ─── threshold_settings ───────────────────────────────────────

    public ThresholdConfig loadThresholdConfig(String metricName) {
        return thresholds.load(metricName);
    }

    public List<Map<String, Object>> queryAllThresholds() {
        return thresholds.queryAll();
    }

    public void updateThreshold(long id, double threshold, double rearm,
                                int triggerCount, int rearmCount) throws SQLException {
        thresholds.update(id, threshold, rearm, triggerCount, rearmCount);
    }

    // ─── snmp_v3_users ────────────────────────────────────────────

    public List<V3UserConfig> loadEnabledV3Users() {
        return v3Users.loadEnabled();
    }

    public List<Map<String, Object>> queryAllV3Users() {
        return v3Users.queryAll();
    }

    public void addV3User(String username, String authPassword, String authProtocol,
                          String privPassword, String privProtocol) throws SQLException {
        v3Users.add(username, authPassword, authProtocol, privPassword, privProtocol);
    }

    public void updateV3User(long id, String username, String authPassword, String authProtocol,
                             String privPassword, String privProtocol, boolean enabled) throws SQLException {
        v3Users.update(id, username, authPassword, authProtocol, privPassword, privProtocol, enabled);
    }

    public void deleteV3User(long id) throws SQLException {
        v3Users.delete(id);
    }

    // ─── api_tokens ───────────────────────────────────────────────

    /** 新增 Token，回傳 token 字串 */
    public String createApiToken(String description, String expiresAt) throws SQLException {
        return apiTokens.create(description, expiresAt);
    }

    /** 列出所有 token（不含 token 明文） */
    public List<Map<String, Object>> queryAllApiTokens() {
        return apiTokens.queryAll();
    }

    /** 驗證 token：存在、啟用且未過期 → 回傳 {id, description}，否則回 null */
    public Map<String, Object> validateApiToken(String token) {
        return apiTokens.validate(token);
    }

    public void updateApiToken(long id, String description, boolean enabled, String expiresAt) throws SQLException {
        apiTokens.update(id, description, enabled, expiresAt);
    }

    public void deleteApiToken(long id) throws SQLException {
        apiTokens.delete(id);
    }

    // ─── audit_logs ───────────────────────────────────────────────

    /** 寫入稽核日誌（不拋例外，失敗只 log） */
    public void saveAuditLog(String eventType, String actor, String sourceIp,
                             String method, String path, int status, String detail) {
        auditLogs.save(eventType, actor, sourceIp, method, path, status, detail);
    }

    /** 查詢稽核日誌 */
    public List<Map<String, Object>> queryAuditLogs(String eventType, String actor, int limit) {
        return auditLogs.query(eventType, actor, limit);
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
