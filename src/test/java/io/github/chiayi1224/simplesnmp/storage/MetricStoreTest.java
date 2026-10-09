package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.profile.BuiltinProfiles;
import io.github.chiayi1224.simplesnmp.profile.MetricKind;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricStore 整合測試（使用 SQLite in-memory DB）
 */
class MetricStoreTest {

    private MetricStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new MetricStore(":memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    // ─── 預設帳號 ─────────────────────────────────────────────────

    @Test
    void 初始化後預設admin帳號存在() {
        Map<String, Object> user = store.authenticate("admin", "admin");
        assertNotNull(user);
        assertEquals("admin", user.get("username"));
        assertEquals("admin", user.get("role"));
        assertEquals(true,    user.get("mustChange"));
    }

    @Test
    void 帳號不存在回傳null() {
        assertNull(store.authenticate("nobody", "pass"));
    }

    @Test
    void 密碼錯誤回傳null() {
        assertNull(store.authenticate("admin", "wrongpass"));
    }

    // ─── 帳號 CRUD ─────────────────────────────────────────────────

    @Test
    void addUser_新增帳號並可驗證() throws SQLException {
        store.addUser("alice", "password123", "admin");
        Map<String, Object> user = store.authenticate("alice", "password123");
        assertNotNull(user);
        assertEquals("alice", user.get("username"));
        assertEquals(false,   user.get("mustChange")); // 非預設帳號不需改密碼
    }

    @Test
    void addUser_mustChangeTrue_驗證後mustChange為true() throws SQLException {
        store.addUser("bob", "pass", "admin", true);
        Map<String, Object> user = store.authenticate("bob", "pass");
        assertNotNull(user);
        assertEquals(true, user.get("mustChange"));
    }

    @Test
    void addUser_重複帳號拋出例外() throws SQLException {
        store.addUser("dup", "pass", "admin");
        assertThrows(SQLException.class, () -> store.addUser("dup", "pass2", "admin"));
    }

    @Test
    void queryAllUsers_包含所有帳號() throws SQLException {
        store.addUser("user1", "p1", "admin");
        store.addUser("user2", "p2", "admin");
        List<Map<String, Object>> list = store.queryAllUsers();
        // 包含預設 admin + user1 + user2
        assertTrue(list.size() >= 3);
        assertTrue(list.stream().anyMatch(u -> "user1".equals(u.get("username"))));
        assertTrue(list.stream().anyMatch(u -> "user2".equals(u.get("username"))));
    }

    @Test
    void changePassword_更改後可用新密碼驗證() throws SQLException {
        store.addUser("carol", "oldpass", "admin");
        Map<String, Object> u = store.authenticate("carol", "oldpass");
        long id = ((Number) u.get("id")).longValue();

        store.changePassword(id, "newpass123");

        assertNull(store.authenticate("carol", "oldpass"));
        assertNotNull(store.authenticate("carol", "newpass123"));
    }

    @Test
    void changePassword_清除mustChange旗標() throws SQLException {
        store.addUser("dave", "pass", "admin", true);
        Map<String, Object> u = store.authenticate("dave", "pass");
        assertTrue((Boolean) u.get("mustChange"));

        long id = ((Number) u.get("id")).longValue();
        store.changePassword(id, "newpass");

        Map<String, Object> after = store.authenticate("dave", "newpass");
        assertEquals(false, after.get("mustChange"));
    }

    @Test
    void resetPassword_可用新密碼登入且設定mustChange旗標() throws SQLException {
        store.addUser("frank", "oldpass", "admin");
        Map<String, Object> u = store.authenticate("frank", "oldpass");
        assertEquals(false, u.get("mustChange"));

        long id = ((Number) u.get("id")).longValue();
        store.resetPassword(id, "reset123");

        assertNull(store.authenticate("frank", "oldpass"));
        Map<String, Object> after = store.authenticate("frank", "reset123");
        assertEquals(true, after.get("mustChange"));
    }

    @Test
    void updateUser_修改帳號名稱() throws SQLException {
        store.addUser("eve", "pass", "admin");
        Map<String, Object> u = store.authenticate("eve", "pass");
        long id = ((Number) u.get("id")).longValue();

        store.updateUser(id, "eve-renamed", "admin", true);

        assertNull(store.authenticate("eve", "pass"));
        assertNotNull(store.authenticate("eve-renamed", "pass"));
    }

    @Test
    void deleteUser_刪除後無法驗證() throws SQLException {
        store.addUser("frank", "pass", "admin");
        Map<String, Object> u = store.authenticate("frank", "pass");
        long id = ((Number) u.get("id")).longValue();

        store.deleteUser(id);

        assertNull(store.authenticate("frank", "pass"));
    }

    // ─── 設備 CRUD ─────────────────────────────────────────────────

    @Test
    void addDevice_新增後可查詢() throws SQLException {
        store.addDevice("Router-01", "10.0.0.1", 161, "public");
        List<Map<String, Object>> list = store.queryAllDevices();
        assertTrue(list.stream().anyMatch(d -> "Router-01".equals(d.get("name"))));
    }

    @Test
    void queryDevice_不回傳community明文() throws SQLException {
        store.addDevice("Secret", "10.0.0.20", 161, "s3cretCommunity");

        Map<String, Object> dev = store.queryDeviceByName("Secret");

        assertFalse(dev.containsKey("community"));
        assertEquals(true, dev.get("communityConfigured"));
        assertFalse(store.queryAllDevices().toString().contains("s3cretCommunity"));
    }

    @Test
    void updateDeviceByName_community為null時沿用舊值() throws SQLException {
        store.addDevice("Keep", "10.0.0.21", 161, "oldCommunity");

        store.updateDeviceByName(new DeviceConfig.Builder("Keep", "10.0.0.22").community(null).build(), true);
        assertEquals("oldCommunity", store.loadDevice("Keep").getCommunity());

        store.updateDeviceByName(new DeviceConfig.Builder("Keep", "10.0.0.22").community("newCommunity").build(), true);
        assertEquals("newCommunity", store.loadDevice("Keep").getCommunity());
    }

    @Test
    void addDevice_重複名稱拋出例外() throws SQLException {
        store.addDevice("SW", "10.0.0.2", 161, "public");
        assertThrows(SQLException.class,
            () -> store.addDevice("SW", "10.0.0.3", 161, "public"));
    }

    @Test
    void updateDevice_更新後查詢到新值() throws SQLException {
        store.addDevice("Dev", "10.0.0.5", 161, "public");
        List<Map<String, Object>> list = store.queryAllDevices();
        long id = ((Number) list.stream()
            .filter(d -> "Dev".equals(d.get("name")))
            .findFirst().get().get("id")).longValue();

        store.updateDevice(id, "Dev-New", "10.0.0.99", 162, "private", true);

        List<Map<String, Object>> updated = store.queryAllDevices();
        Map<String, Object> dev = updated.stream()
            .filter(d -> "Dev-New".equals(d.get("name")))
            .findFirst().orElse(null);
        assertNotNull(dev);
        assertEquals("10.0.0.99", dev.get("host"));
        assertEquals(162,         dev.get("port"));
    }

    @Test
    void deleteDevice_刪除後查不到() throws SQLException {
        store.addDevice("TempDev", "10.0.0.6", 161, "public");
        List<Map<String, Object>> list = store.queryAllDevices();
        long id = ((Number) list.stream()
            .filter(d -> "TempDev".equals(d.get("name")))
            .findFirst().get().get("id")).longValue();

        store.deleteDevice(id);

        List<Map<String, Object>> after = store.queryAllDevices();
        assertFalse(after.stream().anyMatch(d -> "TempDev".equals(d.get("name"))));
    }

    @Test
    void loadEnabledDevices_只回傳啟用設備() throws SQLException {
        store.addDevice("Enabled",  "10.0.0.10", 161, "public");
        store.addDevice("Disabled", "10.0.0.11", 161, "public");

        // 停用 Disabled
        List<Map<String, Object>> list = store.queryAllDevices();
        long id = ((Number) list.stream()
            .filter(d -> "Disabled".equals(d.get("name")))
            .findFirst().get().get("id")).longValue();
        store.updateDevice(id, "Disabled", "10.0.0.11", 161, "public", false);

        var enabled = store.loadEnabledDevices();
        assertTrue(enabled.stream().anyMatch(d -> "Enabled".equals(d.getName())));
        assertFalse(enabled.stream().anyMatch(d -> "Disabled".equals(d.getName())));
    }

    // ─── Metrics 寫入與查詢 ────────────────────────────────────────

    @Test
    void saveCpu_寫入後可在latestStatus查到() {
        store.saveCpu("Server-01", 55.5);
        List<Map<String, Object>> status = store.queryLatestStatus();
        Map<String, Object> s = status.stream()
            .filter(m -> "Server-01".equals(m.get("deviceName")))
            .findFirst().orElse(null);
        assertNotNull(s);
        assertNotNull(s.get("cpu"));
    }

    @Test
    void saveMemory_寫入後可在latestStatus查到() {
        // queryLatestStatus 依賴 queryDeviceNames()（由 cpu_metrics 取得），需先寫 CPU
        store.saveCpu("Server-02", 50.0);
        store.saveMemory("Server-02", 60.0, 4_000_000L, 2_400_000L);
        List<Map<String, Object>> status = store.queryLatestStatus();
        Map<String, Object> s = status.stream()
            .filter(m -> "Server-02".equals(m.get("deviceName")))
            .findFirst().orElse(null);
        assertNotNull(s);
        assertNotNull(s.get("memory"));
    }

    // ─── Alert Events ──────────────────────────────────────────────

    @Test
    void saveAlertEvent_TRIGGERED_hasOpenAlert回傳true() {
        AlertEvent e = AlertEvent.triggered("Server-01", "cpu", 91.0, 90.0);
        store.saveAlertEvent(e);
        assertTrue(store.hasOpenAlert("Server-01", "cpu"));
    }

    @Test
    void hasOpenAlert_無告警回傳false() {
        assertFalse(store.hasOpenAlert("Server-01", "cpu"));
    }

    @Test
    void saveAlertEvent_RESOLVED後hasOpenAlert回傳false() {
        store.saveAlertEvent(AlertEvent.triggered("Server-01", "cpu", 91.0, 90.0));
        store.saveAlertEvent(AlertEvent.resolved("Server-01", "cpu", 79.0, 80.0));
        assertFalse(store.hasOpenAlert("Server-01", "cpu"));
    }

    @Test
    void queryAlerts_回傳正確數量() {
        store.saveAlertEvent(AlertEvent.triggered("Server-01", "cpu",    91.0, 90.0));
        store.saveAlertEvent(AlertEvent.triggered("Server-01", "memory", 86.0, 85.0));
        List<Map<String, Object>> alerts = store.queryAlerts(null, 10);
        assertTrue(alerts.size() >= 2);
    }

    @Test
    void queryAlerts_依設備過濾() {
        store.saveAlertEvent(AlertEvent.triggered("Server-A", "cpu", 91.0, 90.0));
        store.saveAlertEvent(AlertEvent.triggered("Server-B", "cpu", 91.0, 90.0));
        List<Map<String, Object>> alerts = store.queryAlerts("Server-A", 10);
        assertTrue(alerts.stream().allMatch(a -> "Server-A".equals(a.get("deviceName"))));
    }

    @Test
    void queryAlerts_limit限制筆數() {
        for (int i = 0; i < 5; i++) {
            store.saveAlertEvent(AlertEvent.triggered("S", "cpu", 91.0, 90.0));
        }
        List<Map<String, Object>> alerts = store.queryAlerts(null, 3);
        assertEquals(3, alerts.size());
    }

    // ─── 設備 SNMP 版本與 v3 設定 ───────────────────────────────

    @Test
    void 舊版schema的設備升級後為v2c(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("old.db").toString();
        // 模擬升級前的 devices 資料表（沒有 snmp_version 與 v3 欄位）
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE devices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, host TEXT NOT NULL,
                    port INTEGER NOT NULL DEFAULT 161, community TEXT NOT NULL DEFAULT 'public',
                    enabled INTEGER NOT NULL DEFAULT 1, created TEXT NOT NULL, updated TEXT NOT NULL)
                """);
            st.execute("INSERT INTO devices(name, host, created, updated) VALUES('Legacy', '10.0.0.1', 'x', 'x')");
        }

        try (MetricStore upgraded = new MetricStore(dbPath)) {
            DeviceConfig d = upgraded.loadDevice("Legacy");
            assertEquals(DeviceConfig.SnmpVersion.V2C, d.getSnmpVersion());
            assertEquals("2c", upgraded.queryDeviceByName("Legacy").get("snmpVersion"));
            assertEquals(1, upgraded.loadEnabledDevices().size());
        }
    }

    private static DeviceConfig v3Device(String host, String auth, String priv) {
        return new DeviceConfig.Builder("Core-SW", host)
            .snmpVersion(DeviceConfig.SnmpVersion.V3).v3User("monitor")
            .v3AuthProtocol(V3UserConfig.AuthProtocol.SHA256).v3AuthPassword(auth)
            .v3PrivProtocol(V3UserConfig.PrivProtocol.AES256).v3PrivPassword(priv)
            .build();
    }

    @Test
    void v3設備_完整設定可載入() throws SQLException {
        store.addDevice(v3Device("10.0.0.9", "authPass1", "privPass1"));

        DeviceConfig d = store.loadDevice("Core-SW");
        assertEquals(DeviceConfig.SnmpVersion.V3, d.getSnmpVersion());
        assertEquals("monitor", d.getV3User());
        assertEquals(V3UserConfig.AuthProtocol.SHA256, d.getV3AuthProtocol());
        assertEquals("authPass1", d.getV3AuthPassword());
        assertEquals(V3UserConfig.PrivProtocol.AES256, d.getV3PrivProtocol());
        assertEquals("privPass1", d.getV3PrivPassword());
    }

    @Test
    void v3設備_查詢結果不含密碼() throws SQLException {
        store.addDevice(v3Device("10.0.0.9", "authPass1", "privPass1"));

        Map<String, Object> row = store.queryDeviceByName("Core-SW");
        assertEquals(true, row.get("v3AuthConfigured"));
        assertEquals(true, row.get("v3PrivConfigured"));
        assertEquals("SHA256", row.get("v3AuthProtocol"));
        String all = store.queryAllDevices().toString() + row;
        assertFalse(all.contains("authPass1"));
        assertFalse(all.contains("privPass1"));
    }

    @Test
    void v3設備_更新時密碼為null沿用舊值_空字串清除() throws SQLException {
        store.addDevice(v3Device("10.0.0.9", "authPass1", "privPass1"));

        store.updateDeviceByName(v3Device("10.0.0.10", null, null), true);
        DeviceConfig kept = store.loadDevice("Core-SW");
        assertEquals("10.0.0.10", kept.getHost());
        assertEquals("authPass1", kept.getV3AuthPassword());
        assertEquals("privPass1", kept.getV3PrivPassword());

        store.updateDeviceByName(v3Device("10.0.0.10", null, ""), true);
        assertEquals("", store.loadDevice("Core-SW").getV3PrivPassword());
        assertEquals(false, store.queryDeviceByName("Core-SW").get("v3PrivConfigured"));
    }

    // ─── OID 掃描結果 ─────────────────────────────────────────────

    private long completedScan(String device, String... oids) throws SQLException {
        long id = store.createScan(device, true);
        List<String[]> rows = new java.util.ArrayList<>();
        for (String oid : oids) rows.add(new String[]{ oid, "OCTET STRING", "value-of-" + oid });
        store.addScanResults(id, rows);
        store.finishScan(id, "COMPLETED", null, false);
        return id;
    }

    @Test
    void 掃描_完成後記錄筆數與狀態() throws SQLException {
        long id = completedScan("SW", "1.3.6.1.2.1.1.1.0", "1.3.6.1.2.1.1.5.0");

        Map<String, Object> scan = store.queryScan(id);
        assertEquals("COMPLETED", scan.get("status"));
        assertEquals(2L, scan.get("oidCount"));
        assertEquals(false, scan.get("truncated"));
        assertNotNull(scan.get("finished"));
    }

    @Test
    void 掃描_每台設備只保留最近3次() throws SQLException {
        long first = completedScan("SW", "1.3.6.1.2.1.1.1.0");
        for (int i = 0; i < 3; i++) completedScan("SW", "1.3.6.1.2.1.1.1.0");
        completedScan("Other", "1.3.6.1.2.1.1.1.0");

        store.pruneScans("SW", 3);

        assertEquals(3, store.queryScans("SW").size());
        assertNull(store.queryScan(first), "最舊的掃描應被刪除");
        assertEquals(0L, store.queryScanOids(first, null, null, 0, 100).get("total"), "結果也應一併刪除");
        assertEquals(1, store.queryScans("Other").size(), "其他設備不受影響");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 掃描_依前綴篩選且不誤判相似前綴() throws SQLException {
        long id = completedScan("SW",
            "1.3.6.1.2.1.2.2.1.2.1", "1.3.6.1.2.1.2.2.1.2.2", "1.3.6.1.2.1.2.20.1", "1.3.6.1.2.1.1.5.0");

        Map<String, Object> r = store.queryScanOids(id, "1.3.6.1.2.1.2.2", null, 0, 100);

        assertEquals(2L, r.get("total"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertEquals("1.3.6.1.2.1.2.2.1.2.1", items.get(0).get("oid"));
    }

    @Test
    void 掃描_依值關鍵字篩選並跳脫萬用字元() throws SQLException {
        long id = store.createScan("SW", true);
        store.addScanResults(id, List.of(
            new String[]{ "1.1", "OCTET STRING", "GigabitEthernet0/1" },
            new String[]{ "1.2", "OCTET STRING", "100%_used" },
            new String[]{ "1.3", "OCTET STRING", "Loopback0" }));

        assertEquals(1L, store.queryScanOids(id, null, "gigabit", 0, 100).get("total"));
        assertEquals(1L, store.queryScanOids(id, null, "%_", 0, 100).get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 掃描_分頁() throws SQLException {
        String[] oids = new String[25];
        for (int i = 0; i < 25; i++) oids[i] = "1.3.6." + (i + 1);
        long id = completedScan("SW", oids);

        Map<String, Object> page = store.queryScanOids(id, null, null, 20, 10);

        assertEquals(25L, page.get("total"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertEquals(5, items.size());
        assertEquals("1.3.6.21", items.get(0).get("oid"), "依寫入順序（走訪順序）排列");
    }

    @Test
    void 掃描_重新啟動時未完成的掃描標記為失敗(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("scan.db").toString();
        long id;
        try (MetricStore first = new MetricStore(dbPath)) {
            id = first.createScan("SW", false);
        }
        try (MetricStore reopened = new MetricStore(dbPath)) {
            Map<String, Object> scan = reopened.queryScan(id);
            assertEquals("FAILED", scan.get("status"));
            assertTrue(scan.get("error").toString().contains("重啟"));
        }
    }

    // ─── 未定義 Trap / 未登錄來源 ─────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void 未定義Trap_刪除後同OID再次寫入從1計次() throws SQLException {
        store.saveUnknownTrap("1.3.6.1.4.1.9999.0.7", "SW", "10.0.0.1", "a");
        store.saveUnknownTrap("1.3.6.1.4.1.9999.0.7", "SW", "10.0.0.1", "b");
        long id = (Long) store.queryUnknownTraps().get(0).get("id");
        assertEquals(2, store.queryUnknownTraps().get(0).get("count"));

        assertTrue(store.deleteUnknownTrap(id));
        assertFalse(store.deleteUnknownTrap(id), "第二次刪除應回傳 false");
        store.saveUnknownTrap("1.3.6.1.4.1.9999.0.7", "SW", "10.0.0.1", "c");

        assertEquals(1, store.queryUnknownTraps().get(0).get("count"));
    }

    @Test
    void 未登錄來源_依ID與依IP刪除() throws SQLException {
        store.saveUnknownSource("10.0.0.98", "1.3.6.1", "");
        store.saveUnknownSource("10.0.0.99", "1.3.6.1", "");
        long id = (Long) store.queryUnknownSources().stream()
            .filter(r -> "10.0.0.98".equals(r.get("sourceIp"))).findFirst().orElseThrow().get("id");

        assertTrue(store.deleteUnknownSource(id));
        store.deleteUnknownSourceByIp("10.0.0.99");

        assertTrue(store.queryUnknownSources().isEmpty());
    }

    @Test
    void 觸發OID重複檢查排除自己() throws SQLException {
        store.addTrapMapping("fan", "1.3.6.1.4.1.9999.0.7", null, "");
        long id = (Long) store.queryAllTrapMappings().stream()
            .filter(r -> "fan".equals(r.get("metricName"))).findFirst().orElseThrow().get("id");

        assertTrue (store.trapTriggeredOidExists("1.3.6.1.4.1.9999.0.7", -1));
        assertFalse(store.trapTriggeredOidExists("1.3.6.1.4.1.9999.0.7", id));
        assertFalse(store.trapTriggeredOidExists("1.3.6.1.4.1.9999.0.8", -1));
    }

    // ─── 監控範本 ─────────────────────────────────────────────────

    private static MetricProfile sampleProfile(String name) {
        return new MetricProfile(null, name, "測試", "1.3.6.1.4.1.99999", false, List.of(
            new ProfileMetric("cpu", MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.99999.2.1.0"), "%", true),
            new ProfileMetric("temperature", MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.99999.3.1.0", "scale", "0.1"), "°C", false)));
    }

    @Test
    void 範本_首次啟動建立三個內建範本() {
        List<MetricProfile> all = store.loadProfiles();
        assertEquals(List.of(BuiltinProfiles.CISCO_IOS, BuiltinProfiles.HOST_RESOURCES, BuiltinProfiles.NET_SNMP),
            all.stream().filter(MetricProfile::builtin).map(MetricProfile::name).sorted().toList());
        assertTrue(all.stream().allMatch(p -> !p.metrics().isEmpty()));
    }

    @Test
    void 範本_新增查詢更新刪除() throws SQLException {
        long id = store.createProfile(sampleProfile("Switch-X"));

        MetricProfile loaded = store.loadProfile(id);
        assertEquals("Switch-X", loaded.name());
        assertEquals(2, loaded.metrics().size());
        ProfileMetric temp = loaded.metrics().get(1);
        assertEquals("0.1", temp.params().get("scale"));
        assertEquals("°C", temp.unit());
        assertFalse(temp.enabled());

        store.updateProfile(id, new MetricProfile(id, "Switch-Y", "", "", false, List.of(loaded.metrics().get(0))));
        assertEquals("Switch-Y", store.loadProfile(id).name());
        assertEquals(1, store.loadProfile(id).metrics().size());

        store.deleteProfile(id);
        assertNull(store.loadProfile(id));
    }

    @Test
    void 範本_名稱重複檢查排除自己() throws SQLException {
        long id = store.createProfile(sampleProfile("Switch-X"));

        assertTrue (store.profileNameExists("Switch-X", -1));
        assertFalse(store.profileNameExists("Switch-X", id));
        assertTrue (store.profileNameExists(BuiltinProfiles.CISCO_IOS, id), "不可使用內建範本的名稱");
    }

    @Test
    void 範本_重複啟動不產生重複內建範本_使用者複本不被覆蓋(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("profiles.db").toString();
        try (MetricStore first = new MetricStore(dbPath)) {
            MetricProfile net = first.loadProfiles().stream()
                .filter(p -> p.name().equals(BuiltinProfiles.NET_SNMP)).findFirst().orElseThrow();
            first.createProfile(new MetricProfile(null, BuiltinProfiles.NET_SNMP + "（複本）", "我的修改",
                net.matchPrefix(), false, List.of(net.metrics().get(0))));
        }
        try (MetricStore second = new MetricStore(dbPath)) {
            List<MetricProfile> all = second.loadProfiles();
            assertEquals(3, all.stream().filter(MetricProfile::builtin).count());
            MetricProfile copy = all.stream().filter(p -> p.name().endsWith("（複本）")).findFirst().orElseThrow();
            assertEquals("我的修改", copy.description());
            assertEquals(1, copy.metrics().size());
        }
    }

    @Test
    void 範本_設備套用與刪除後改回自動() throws SQLException {
        long id = store.createProfile(sampleProfile("Switch-X"));
        store.addDevice(new DeviceConfig.Builder("Core-SW", "10.0.0.9").profileId(id).build());
        store.addDevice(new DeviceConfig.Builder("Linux-1", "10.0.0.1").build());
        store.updateDeviceSysObjectId("Linux-1", "1.3.6.1.4.1.8072.3.2.10");

        assertEquals("Switch-X", store.queryDeviceByName("Core-SW").get("appliedProfileName"));
        assertEquals(id, store.loadDevice("Core-SW").getProfileId());
        assertEquals(BuiltinProfiles.NET_SNMP, store.queryDeviceByName("Linux-1").get("appliedProfileName"));
        assertEquals("1.3.6.1.4.1.8072.3.2.10", store.loadDevice("Linux-1").getSysObjectId());

        store.deleteProfile(id);

        assertNull(store.loadDevice("Core-SW").getProfileId());
        assertEquals(BuiltinProfiles.HOST_RESOURCES, store.queryDeviceByName("Core-SW").get("appliedProfileName"));
    }

    // ─── 自訂指標閾值 ─────────────────────────────────────────────

    @Test
    void 閾值_自訂指標未設定回傳null_內建三項有預設() {
        assertNull(store.loadThresholdConfig("temperature"));
        assertNotNull(store.loadThresholdConfig("cpu"));
        assertNotNull(store.loadThresholdConfig("disk:/home"));
        assertEquals("disk:/home", store.loadThresholdConfig("disk:/home").getMetricName());
    }

    @Test
    void 閾值_新增自訂LOW方向後可載入_刪除後回到null() throws SQLException {
        long id = store.createThreshold("free_ports", ThresholdConfig.Direction.LOW, 5, 8, 2, 2, "可用埠數");
        assertTrue(store.thresholdExists("free_ports"));
        assertEquals("free_ports", store.thresholdMetricName(id));

        ThresholdConfig cfg = store.loadThresholdConfig("free_ports");
        assertEquals(ThresholdConfig.Direction.LOW, cfg.getDirection());
        assertEquals(5.0, cfg.getThreshold());
        assertTrue(cfg.isExceeded(2));

        Map<String, Object> row = store.queryAllThresholds().stream()
            .filter(r -> "free_ports".equals(r.get("metricName"))).findFirst().orElseThrow();
        assertEquals("LOW", row.get("direction"));
        assertEquals(false, row.get("builtin"));

        store.updateThreshold(id, ThresholdConfig.Direction.HIGH, 50, 40, 1, 1);
        assertEquals(ThresholdConfig.Direction.HIGH, store.loadThresholdConfig("free_ports").getDirection());

        store.deleteThreshold(id);
        assertNull(store.loadThresholdConfig("free_ports"));
        assertNull(store.thresholdMetricName(id));
    }

    @Test
    void 閾值_內建三項方向為HIGH且標記builtin() {
        List<Map<String, Object>> rows = store.queryAllThresholds();
        assertEquals(3, rows.size());
        for (Map<String, Object> r : rows) {
            assertEquals("HIGH", r.get("direction"));
            assertEquals(true, r.get("builtin"));
        }
    }

    // ─── 自訂指標 ─────────────────────────────────────────────────

    @Test
    void 自訂指標_最新狀態每個指標取最後一筆_既有欄位不變() throws Exception {
        store.saveCpu("SW-01", 10.0);
        store.saveCustomMetric("SW-01", "temperature", 40.0, "°C");
        Thread.sleep(5);   // 時間欄位為 ISO-8601 字串，間隔確保排序
        store.saveCustomMetric("SW-01", "temperature", 45.0, "°C");
        store.saveCustomMetric("SW-01", "free_ports", 3.0, "");

        Map<String, Object> s = store.queryLatestStatus().stream()
            .filter(m -> "SW-01".equals(m.get("deviceName"))).findFirst().orElseThrow();
        assertEquals(List.of("deviceName", "cpu", "memory", "disks", "custom"), List.copyOf(s.keySet()));
        assertNotNull(s.get("cpu"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> custom = (List<Map<String, Object>>) s.get("custom");
        assertEquals(2, custom.size());
        assertEquals("free_ports", custom.get(0).get("metricKey"));
        assertEquals("temperature", custom.get(1).get("metricKey"));
        assertEquals(45.0, custom.get(1).get("value"));
        assertEquals("°C", custom.get(1).get("unit"));
    }

    @Test
    void 自訂指標_只有自訂指標的設備也出現在狀態查詢() {
        store.saveCustomMetric("UPS-01", "battery_pct", 98.0, "%");
        Map<String, Object> s = store.queryLatestStatus().stream()
            .filter(m -> "UPS-01".equals(m.get("deviceName"))).findFirst().orElseThrow();
        assertNull(s.get("cpu"));
        assertEquals(1, ((List<?>) s.get("custom")).size());
    }

    // ─── Poll OID 升級轉換（D8）────────────────────────────────────

    /** 建立升級前的 DB：devices 含輪詢狀態，poll_oids 依參數設定啟用狀態 */
    private static String legacyDb(Path dir, boolean cpuIdleEnabled, boolean diskEnabled) throws Exception {
        String dbPath = dir.resolve("legacy.db").toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE devices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, host TEXT NOT NULL,
                    port INTEGER NOT NULL DEFAULT 161, community TEXT NOT NULL DEFAULT 'public',
                    enabled INTEGER NOT NULL DEFAULT 1, created TEXT NOT NULL, updated TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'UNKNOWN')
                """);
            st.execute("INSERT INTO devices(name, host, created, updated, status) VALUES('Polled', '10.0.0.1', 'x', 'x', 'ONLINE')");
            st.execute("INSERT INTO devices(name, host, created, updated, status) VALUES('Offline', '10.0.0.2', 'x', 'x', 'OFFLINE')");
            st.execute("INSERT INTO devices(name, host, created, updated) VALUES('NeverPolled', '10.0.0.3', 'x', 'x')");
            st.execute("""
                CREATE TABLE poll_oids (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, oid TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '', group_name TEXT NOT NULL, poll_type TEXT NOT NULL DEFAULT 'SCALAR',
                    enabled INTEGER NOT NULL DEFAULT 1, created TEXT NOT NULL, updated TEXT NOT NULL)
                """);
            String[][] rows = {
                { "1.3.6.1.4.1.2021.11.9.0",  "cpu",    "1" },   // cpuUser：舊版計算不使用
                { "1.3.6.1.4.1.2021.11.11.0", "cpu",    cpuIdleEnabled ? "1" : "0" },
                { "1.3.6.1.4.1.2021.4.5.0",   "memory", "1" },
                { "1.3.6.1.4.1.2021.4.6.0",   "memory", "1" },
                { "1.3.6.1.4.1.2021.4.14.0",  "memory", "1" },
                { "1.3.6.1.4.1.2021.4.15.0",  "memory", "1" },
                { "1.3.6.1.2.1.25.2.3.1.2",   "disk",   diskEnabled ? "1" : "0" },
                { "1.3.6.1.2.1.25.2.3.1.3",   "disk",   diskEnabled ? "1" : "0" },
            };
            for (String[] r : rows) {
                st.execute("INSERT INTO poll_oids(oid, name, group_name, enabled, created, updated) VALUES('"
                    + r[0] + "', '" + r[0] + "', '" + r[1] + "', " + r[2] + ", 'x', 'x')");
            }
        }
        return dbPath;
    }

    private static MetricProfile migrated(MetricStore s) {
        return s.loadProfiles().stream()
            .filter(p -> "Net-SNMP（UCD-SNMP）（升級轉換）".equals(p.name())).findFirst().orElse(null);
    }

    @Test
    void 升級轉換_停用的CPU與磁碟轉為範本並指定給曾輪詢的設備_只執行一次(@TempDir Path dir) throws Exception {
        String dbPath = legacyDb(dir, false, false);

        try (MetricStore upgraded = new MetricStore(dbPath)) {
            MetricProfile p = migrated(upgraded);
            assertNotNull(p, "應建立升級轉換範本");
            assertFalse(p.builtin());
            Map<String, Boolean> enabled = new java.util.HashMap<>();
            for (ProfileMetric m : p.metrics()) enabled.put(m.metricKey(), m.enabled());
            assertEquals(Map.of("cpu", false, "memory", true, "disk", false), enabled);

            assertEquals(p.id(), upgraded.loadDevice("Polled").getProfileId());
            assertEquals(p.id(), upgraded.loadDevice("Offline").getProfileId());
            assertNull(upgraded.loadDevice("NeverPolled").getProfileId(), "從未輪詢的設備維持自動");

            // 管理者之後把設備改回自動
            upgraded.updateDeviceByName(new DeviceConfig.Builder("Polled", "10.0.0.1").profileId(null).build(), true);
        }

        try (MetricStore again = new MetricStore(dbPath)) {
            assertEquals(1, again.loadProfiles().stream().filter(p -> p.name().contains("升級轉換")).count(), "不重複轉換");
            assertNull(again.loadDevice("Polled").getProfileId(), "第二次開啟不再重新指定");
        }
    }

    @Test
    void 升級轉換_只停用未使用的OID或全部啟用時不建立範本(@TempDir Path dir) throws Exception {
        String dbPath = legacyDb(dir, true, true);
        try (MetricStore upgraded = new MetricStore(dbPath)) {
            assertNull(migrated(upgraded));
            assertNull(upgraded.loadDevice("Polled").getProfileId());
        }
    }

    @Test
    void 升級轉換_全新安裝不建立範本但標記已轉換() {
        assertNull(migrated(store));
        assertEquals("true", store.getSetting("poll_oids_migrated", "false"));
    }
}
