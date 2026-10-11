package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.TrapOidMapping;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import io.github.chiayi1224.simplesnmp.profile.BuiltinProfiles;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileResolver;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * SQLite 儲存（對外統一入口）
 *
 * 本類別負責開啟連線、建立 schema、寫入預設資料，實際的 SQL 依資料表分散於各 Repository：
 *   users               — {@link UserRepository}      管理帳號（密碼 BCrypt hash）
 *   devices             — {@link DeviceRepository}    受監控設備清單
 *   cpu/mem/disk/custom_metrics — {@link MetricRepository} 輪詢指標歷史
 *   alert_events        — {@link AlertRepository}     閾值狀態變化
 *   trap_oid_mappings / unknown_traps / unknown_sources — {@link TrapRepository}
 *   poll_oids           — 已停用（僅保留資料以利回滾；升級時由 {@link PollOidMigration} 轉換為範本）
 *   app_settings        — {@link SettingsRepository}  系統設定（key-value）
 *   device_thresholds   — {@link ThresholdRepository} 每台設備的告警閾值
 *   threshold_settings  — 已停用（僅保留資料以利回滾；升級時由 {@link ThresholdMigration} 轉換為每台設備的閾值）
 *   snmp_v3_users       — {@link V3UserRepository}    SNMPv3 Trap 使用者
 *   api_tokens          — {@link ApiTokenRepository}  API Token
 *   audit_logs          — {@link AuditLogRepository}  稽核日誌
 *   device_scans / device_scan_results — {@link DiscoveryRepository} OID 掃描結果
 *   metric_profiles / profile_metrics   — {@link ProfileRepository}   監控範本
 */
public class MetricStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricStore.class);

    private final Connection conn;

    private final UserRepository      users;
    private final DeviceRepository    devices;
    private final MetricRepository    metrics;
    private final AlertRepository     alerts;
    private final TrapRepository      traps;
    private final SettingsRepository  settings;
    private final ThresholdRepository thresholds;
    private final V3UserRepository    v3Users;
    private final ApiTokenRepository  apiTokens;
    private final AuditLogRepository  auditLogs;
    private final DiscoveryRepository discovery;
    private final ProfileRepository   profiles;

    public MetricStore(String dbPath) throws Exception {
        // 確保父目錄存在（目錄不存在時自動建立，DB 檔由 SQLite JDBC 自動建立）
        if (!":memory:".equals(dbPath)) {
            Path parent = Paths.get(dbPath).toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        conn.setAutoCommit(true);
        configureConnection(conn, !":memory:".equals(dbPath));

        Db db = new Db(conn);
        SchemaManager schema = new SchemaManager(db);
        schema.initSchema();
        schema.migrateSchema();

        users      = new UserRepository(db);
        devices    = new DeviceRepository(db);
        metrics    = new MetricRepository(db);
        alerts     = new AlertRepository(db);
        traps      = new TrapRepository(db);
        settings   = new SettingsRepository(db);
        thresholds = new ThresholdRepository(db);
        v3Users    = new V3UserRepository(db);
        apiTokens  = new ApiTokenRepository(db);
        auditLogs  = new AuditLogRepository(db);
        discovery  = new DiscoveryRepository(db);
        profiles   = new ProfileRepository(db);

        users.seedDefaults();
        traps.seedDefaults();
        settings.seedDefaults();
        v3Users.seedDefaults();
        discovery.failInterruptedScans();
        profiles.syncBuiltin(BuiltinProfiles.all());
        new PollOidMigration(db, profiles, settings).runOnce();
        new ThresholdMigration(db, settings).runOnce();
        log.info("MetricStore opened: {}", dbPath);
    }

    /** 寫入被鎖住時最多等待的毫秒數（例如外部工具正在寫入），超過才回報 SQLITE_BUSY */
    static final int BUSY_TIMEOUT_MS = 5_000;

    /**
     * 連線設定：
     *   - WAL 模式：讀寫可同時進行，外部工具（sqlite3、備份程式）讀取時不會擋住寫入；
     *     設定會記錄在 DB 檔中，執行時會多出 -wal / -shm 兩個檔案
     *   - synchronous=NORMAL：WAL 模式下的建議值，斷電最多遺失最後幾筆交易，不會損毀資料檔
     *   - busy_timeout：遇到鎖定時等待而非立即失敗
     * in-memory DB 不支援 WAL，只設定 busy_timeout。
     */
    private static void configureConnection(Connection conn, boolean fileDb) throws SQLException {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            if (!fileDb) return;
            try (java.sql.ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
                String mode = rs.next() ? rs.getString(1) : "?";
                if (!"wal".equalsIgnoreCase(mode)) log.warn("Unable to enable WAL mode, current mode is {}", mode);
            }
            st.execute("PRAGMA synchronous=NORMAL");
        }
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

    /** 管理員重設他人密碼，並設定強制改密碼旗標 */
    public void resetPassword(long id, String newPassword) throws SQLException {
        users.resetPassword(id, newPassword);
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

    /** 設備清單；每台另附實際套用的範本（appliedProfileId / appliedProfileName） */
    public List<Map<String, Object>> queryAllDevices() {
        ProfileResolver resolver = new ProfileResolver(profiles.loadAll());
        List<Map<String, Object>> rows = devices.queryAll();
        rows.forEach(r -> addAppliedProfile(r, resolver));
        return rows;
    }

    private static void addAppliedProfile(Map<String, Object> row, ProfileResolver resolver) {
        MetricProfile applied = resolver.resolve((Long) row.get("profileId"), (String) row.get("sysObjectId"));
        row.put("appliedProfileId",   applied.id());
        row.put("appliedProfileName", applied.name());
    }

    /** 輪詢時更新設備的 sysObjectID */
    public void updateDeviceSysObjectId(String name, String sysObjectId) {
        devices.updateSysObjectId(name, sysObjectId);
    }

    /** 新增設備（含 SNMP 版本與 v3 設定） */
    public void addDevice(DeviceConfig device) throws SQLException {
        devices.add(device);
    }

    /** 依名稱更新設備；v3 密碼為 null 時沿用舊值 */
    public void updateDeviceByName(DeviceConfig device, boolean enabled) throws SQLException {
        devices.updateByName(device, enabled);
    }

    /** 依名稱載入設備完整設定（含 v3 密碼，僅供內部連線使用）；查無資料回傳 null */
    public DeviceConfig loadDevice(String name) {
        return devices.loadByName(name);
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

    /** 依編號刪除設備；同 {@link #deleteDeviceByName(String)} 一併刪除閾值並解除告警 */
    public void deleteDevice(long id) throws SQLException {
        String name = devices.nameOf(id);
        if (name != null) deleteDeviceByName(name);
    }

    /** 依名稱查詢單台設備 */
    public Map<String, Object> queryDeviceByName(String name) {
        Map<String, Object> row = devices.queryByName(name);
        if (row != null) addAppliedProfile(row, new ProfileResolver(profiles.loadAll()));
        return row;
    }

    /** 依名稱更新設備 */
    public void updateDeviceByName(String name, String host, int port, String community,
                                   boolean enabled, int retries, long timeoutMs) throws SQLException {
        devices.updateByName(name, host, port, community, enabled, retries, timeoutMs);
    }

    /** 依名稱刪除設備，一併刪除其閾值並解除其未解除告警（告警歷史保留） */
    public void deleteDeviceByName(String name) throws SQLException {
        devices.deleteByName(name);
        thresholds.deleteByDevice(name);
        alerts.resolveOpen(name, metric -> true, "設備已刪除");
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

    public void saveCustomMetric(String deviceName, String metricKey, double value, String unit) {
        metrics.saveCustom(deviceName, metricKey, value, unit);
    }

    public void saveAlertEvent(AlertEvent event) {
        alerts.save(event);
    }

    public boolean hasOpenAlert(String deviceName, String metricName) {
        return alerts.hasOpen(deviceName, metricName);
    }

    /** 設備指標目前未解除告警的等級；沒有未解除告警回傳 null */
    public Severity openAlertSeverity(String deviceName, String metricName) {
        return alerts.openSeverity(deviceName, metricName);
    }

    /** 為設備中指標名稱符合條件的未解除告警記錄解除事件，回傳解除筆數 */
    public int resolveOpenAlerts(String deviceName, Predicate<String> metricFilter, String message) throws SQLException {
        return alerts.resolveOpen(deviceName, metricFilter, message);
    }

    public List<String> queryDeviceNames() {
        return metrics.queryDeviceNames();
    }

    public List<Map<String, Object>> queryLatestStatus() {
        return metrics.queryLatestStatus();
    }

    public List<Map<String, Object>> queryAlerts(String deviceName, int limit) {
        return alerts.query(deviceName, null, false, limit);
    }

    /** 查詢告警記錄；deviceName、severity 為 null 代表不篩選，activeOnly 只回傳未解除的告警 */
    public List<Map<String, Object>> queryAlerts(String deviceName, Severity severity, boolean activeOnly, int limit) {
        return alerts.query(deviceName, severity, activeOnly, limit);
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

    /** 新增 Trap 對應，等級為 CRITICAL */
    public void addTrapMapping(String metricName, String triggeredOid,
                               String resolvedOid, String description) throws SQLException {
        addTrapMapping(metricName, triggeredOid, resolvedOid, description, Severity.CRITICAL);
    }

    public void addTrapMapping(String metricName, String triggeredOid,
                               String resolvedOid, String description, Severity severity) throws SQLException {
        traps.addMapping(metricName, triggeredOid, resolvedOid, description, severity);
    }

    public void updateTrapMapping(long id, String metricName, String triggeredOid, String resolvedOid,
                                  String description, Severity severity, boolean enabled) throws SQLException {
        traps.updateMapping(id, metricName, triggeredOid, resolvedOid, description, severity, enabled);
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

    /** 刪除一筆未定義 Trap 記錄；回傳是否有刪到 */
    public boolean deleteUnknownTrap(long id) throws SQLException {
        return traps.deleteUnknownTrap(id);
    }

    /** 刪除一筆未登錄來源記錄；回傳是否有刪到 */
    public boolean deleteUnknownSource(long id) throws SQLException {
        return traps.deleteUnknownSource(id);
    }

    /** 依來源 IP 刪除未登錄來源記錄 */
    public void deleteUnknownSourceByIp(String sourceIp) throws SQLException {
        traps.deleteUnknownSourceByIp(sourceIp);
    }

    /** 觸發 OID 是否已被其他對應使用（excludeId 為更新中的那一筆，新增時傳 -1） */
    public boolean trapTriggeredOidExists(String triggeredOid, long excludeId) throws SQLException {
        return traps.triggeredOidExists(triggeredOid, excludeId);
    }

    /** 查詢所有未登錄來源記錄，依最後出現時間降冪 */
    public List<Map<String, Object>> queryUnknownSources() {
        return traps.queryUnknownSources();
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

    // ─── device_thresholds ────────────────────────────────────────

    /** 設備指定指標的閾值（disk:&lt;掛載點&gt; 沒有覆寫時使用 disk）；沒有設定回傳 null */
    public ThresholdConfig loadThreshold(String deviceName, String metricName) {
        return thresholds.load(deviceName, metricName);
    }

    public List<Map<String, Object>> queryDeviceThresholds(String deviceName) {
        return thresholds.queryByDevice(deviceName);
    }

    /** 指定編號的閾值；不存在回傳 null */
    public Map<String, Object> queryDeviceThreshold(long id) throws SQLException {
        return thresholds.queryById(id);
    }

    /** 每台設備的閾值筆數 */
    public Map<String, Long> countDeviceThresholds() {
        return thresholds.countByDevice();
    }

    /** 新增或取代設備的指標閾值，回傳編號 */
    public long upsertDeviceThreshold(String deviceName, ThresholdConfig config) throws SQLException {
        return thresholds.upsert(deviceName, config);
    }

    public void deleteDeviceThreshold(long id) throws SQLException {
        thresholds.delete(id);
    }

    /**
     * 把來源設備的閾值與連線中斷告警設定複製到目標設備（同名指標取代、其他保留）。
     * 呼叫端須先確認所有目標設備存在。
     */
    public void copyDeviceThresholds(String fromDevice, List<String> toDevices) throws SQLException {
        thresholds.copy(fromDevice, toDevices);
        devices.copyUnreachableSettings(fromDevice, toDevices);
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

    // ─── 監控範本 ─────────────────────────────────────────────────

    /** 所有範本（含指標），內建在前 */
    public List<MetricProfile> loadProfiles() {
        return profiles.loadAll();
    }

    /** 單一範本；不存在回傳 null */
    public MetricProfile loadProfile(long id) {
        return profiles.load(id);
    }

    public boolean profileNameExists(String name, long excludeId) throws SQLException {
        return profiles.nameExists(name, excludeId);
    }

    public long createProfile(MetricProfile profile) throws SQLException {
        return profiles.create(profile);
    }

    public void updateProfile(long id, MetricProfile profile) throws SQLException {
        profiles.update(id, profile);
    }

    /** 刪除範本；原本手動指定此範本的設備改回自動套用 */
    public void deleteProfile(long id) throws SQLException {
        devices.clearProfile(id);
        profiles.delete(id);
    }

    // ─── 資料保存期限 ─────────────────────────────────────────────

    /** 刪除 cutoff 之前的指標資料（cpu / mem / disk），回傳刪除筆數 */
    public int purgeMetricsBefore(Instant cutoff) throws SQLException {
        return metrics.purgeBefore(cutoff.toString());
    }

    /** 刪除 cutoff 之前的告警記錄（各指標最後一筆狀態保留），回傳刪除筆數 */
    public int purgeAlertsBefore(Instant cutoff) throws SQLException {
        return alerts.purgeBefore(cutoff.toString());
    }

    /** 刪除 cutoff 之前的稽核日誌，回傳刪除筆數 */
    public int purgeAuditLogsBefore(Instant cutoff) throws SQLException {
        return auditLogs.purgeBefore(cutoff.toString());
    }

    // ─── OID 掃描 ─────────────────────────────────────────────────

    public long createScan(String deviceName, boolean walk) throws SQLException {
        return discovery.create(deviceName, walk);
    }

    public void updateScanProgress(long scanId, String progress) throws SQLException {
        discovery.updateProgress(scanId, progress);
    }

    public void saveScanSystemInfo(long scanId, String descr, String objectId, String name,
                                   String uptime, String vendor) throws SQLException {
        discovery.saveSystemInfo(scanId, descr, objectId, name, uptime, vendor);
    }

    public void saveScanProbes(long scanId, String probesJson) throws SQLException {
        discovery.saveProbes(scanId, probesJson);
    }

    /** 批次寫入掃描到的 OID；每列為 {oid, type, value} */
    public void addScanResults(long scanId, List<String[]> rows) throws SQLException {
        discovery.addResults(scanId, rows);
    }

    public void finishScan(long scanId, String status, String error, boolean truncated) throws SQLException {
        discovery.finish(scanId, status, error, truncated);
    }

    /** 每台設備只保留最近 keep 次掃描 */
    public void pruneScans(String deviceName, int keep) throws SQLException {
        discovery.prune(deviceName, keep);
    }

    public List<Map<String, Object>> queryScans(String deviceName) {
        return discovery.list(deviceName);
    }

    public Map<String, Object> queryScan(long scanId) {
        return discovery.get(scanId);
    }

    /** OID 明細分頁查詢，回傳 {total, items} */
    public Map<String, Object> queryScanOids(long scanId, String prefix, String keyword, int offset, int limit) {
        return discovery.queryOids(scanId, prefix, keyword, offset, limit);
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
