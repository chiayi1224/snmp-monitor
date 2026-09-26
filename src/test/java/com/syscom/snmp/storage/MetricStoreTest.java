package com.syscom.snmp.storage;

import com.syscom.snmp.threshold.AlertEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
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
}
