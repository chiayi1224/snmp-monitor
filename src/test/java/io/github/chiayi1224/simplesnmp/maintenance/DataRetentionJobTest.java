package io.github.chiayi1224.simplesnmp.maintenance;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 資料保存期限：以檔案 DB 測試，舊資料透過另一條獨立連線寫入（同時驗證 WAL 下可並行存取）
 */
class DataRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @TempDir Path dir;
    private String      dbPath;
    private MetricStore store;
    private Connection  external;
    private DataRetentionJob job;

    @BeforeEach
    void setUp() throws Exception {
        dbPath   = dir.resolve("retention.db").toString();
        store    = new MetricStore(dbPath);
        external = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = external.createStatement()) { st.execute("PRAGMA busy_timeout=5000"); }
        job = new DataRetentionJob(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        external.close();
        store.close();
    }

    private static String daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days)).toString();
    }

    private void exec(String sql, Object... params) throws Exception {
        try (PreparedStatement ps = external.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    private long count(String table) throws Exception {
        try (Statement st = external.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void cpu(int daysAgo)   throws Exception { exec("INSERT INTO cpu_metrics(ts, device_name, used_pct) VALUES(?, 'SW', 10)", daysAgo(daysAgo)); }
    private void alert(String device, String type, int daysAgo) throws Exception {
        exec("INSERT INTO alert_events(ts, device_name, metric_name, event_type, value, threshold_val) VALUES(?, ?, 'cpu', ?, 95, 90)",
            daysAgo(daysAgo), device, type);
    }

    // ─── WAL ─────────────────────────────────────────────────────

    @Test
    void 檔案DB啟用WAL模式() throws Exception {
        try (Statement st = external.createStatement(); ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1));
        }
    }

    @Test
    void WAL模式下外部工具長時間讀取時程式仍可寫入() throws Exception {
        cpu(1);
        // 外部連線開啟讀取交易且尚未結束（例如備份工具正在讀取）
        external.setAutoCommit(false);
        try (Statement st = external.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM cpu_metrics")) {
            assertTrue(rs.next());

            long start = System.currentTimeMillis();
            store.saveAuditLog("TEST", "u", "127.0.0.1", "GET", "/", 200, "讀取期間寫入");
            long elapsed = System.currentTimeMillis() - start;

            // 非 WAL 模式下，寫入會等外部讀取結束，直到 busy_timeout（5 秒）後失敗
            assertTrue(elapsed < 1000, "寫入不應被外部讀取擋住，實際等了 " + elapsed + " ms");
        } finally {
            external.commit();
            external.setAutoCommit(true);
        }
        assertEquals(1, store.queryAuditLogs("TEST", null, 10).size());
    }

    // ─── 清除 ─────────────────────────────────────────────────────

    @Test
    void 指標_超過90天刪除_未超過保留() throws Exception {
        cpu(91); cpu(89); cpu(1);
        exec("INSERT INTO mem_metrics(ts, device_name, used_pct, total_kb, used_kb) VALUES(?, 'SW', 50, 100, 50)", daysAgo(120));
        exec("INSERT INTO disk_metrics(ts, device_name, mount_point, total_bytes, used_bytes, used_pct) VALUES(?, 'SW', '/', 100, 50, 50)", daysAgo(120));
        exec("INSERT INTO disk_metrics(ts, device_name, mount_point, total_bytes, used_bytes, used_pct) VALUES(?, 'SW', '/', 100, 50, 50)", daysAgo(10));
        exec("INSERT INTO custom_metrics(ts, device_name, metric_key, value, unit) VALUES(?, 'SW', 'temperature', 45, '°C')", daysAgo(100));
        exec("INSERT INTO custom_metrics(ts, device_name, metric_key, value, unit) VALUES(?, 'SW', 'temperature', 46, '°C')", daysAgo(2));

        DataRetentionJob.Result r = job.runOnce();

        assertEquals(4, r.metrics());
        assertEquals(2, count("cpu_metrics"));
        assertEquals(0, count("mem_metrics"));
        assertEquals(1, count("disk_metrics"));
        assertEquals(1, count("custom_metrics"), "自訂指標同樣依 retention_metrics_days 清除");
    }

    @Test
    void 告警_最後一筆為CHANGED時保留_仍為未解除() throws Exception {
        alert("D", "TRIGGERED", 500);
        alert("D", "CHANGED", 400);

        job.runOnce();

        assertEquals(1, count("alert_events"));
        assertTrue(store.hasOpenAlert("D", "cpu"));
    }

    @Test
    void 告警_保留每個指標最後一筆狀態_避免長期未恢復的告警被當成已結束() throws Exception {
        alert("A", "TRIGGERED", 400);                               // A 一直沒恢復：必須保留
        alert("B", "TRIGGERED", 500); alert("B", "RESOLVED", 400);  // B：舊的 TRIGGERED 可刪，最後的 RESOLVED 保留
        alert("C", "TRIGGERED", 30);                                // 未超過保留期

        assertTrue(store.hasOpenAlert("A", "cpu"));
        DataRetentionJob.Result r = job.runOnce();

        assertEquals(1, r.alerts());
        assertEquals(3, count("alert_events"));
        assertTrue (store.hasOpenAlert("A", "cpu"), "清除後 A 的告警仍應為開啟");
        assertFalse(store.hasOpenAlert("B", "cpu"));
        assertTrue (store.hasOpenAlert("C", "cpu"));
    }

    @Test
    void 稽核日誌_超過365天刪除() throws Exception {
        exec("INSERT INTO audit_logs(ts, event_type) VALUES(?, 'LOGIN_SUCCESS')", daysAgo(366));
        exec("INSERT INTO audit_logs(ts, event_type) VALUES(?, 'LOGIN_SUCCESS')", daysAgo(364));

        assertEquals(1, job.runOnce().auditLogs());
        assertEquals(1, count("audit_logs"));
    }

    @Test
    void 保留天數由設定決定且修改後立即生效() throws Exception {
        cpu(20); cpu(5);
        store.upsertSetting("retention_metrics_days", "7", "");

        assertEquals(1, job.runOnce().metrics());
        assertEquals(1, count("cpu_metrics"));
    }

    @Test
    void 設定為0代表永久保留() throws Exception {
        cpu(1000);
        store.upsertSetting("retention_metrics_days", "0", "");

        assertEquals(-1, job.runOnce().metrics());
        assertEquals(1, count("cpu_metrics"));
    }

    @Test
    void 預設保留天數已寫入設定() {
        assertEquals("90",  store.getSetting("retention_metrics_days", null));
        assertEquals("365", store.getSetting("retention_alerts_days", null));
        assertEquals("365", store.getSetting("retention_audit_days", null));
    }
}
