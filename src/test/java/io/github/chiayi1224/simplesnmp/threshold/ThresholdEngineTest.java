package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.model.DeviceStatus;
import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 閾值引擎：以 in-memory DB 驗證每設備閾值、磁碟覆寫、閾值移除、重啟還原與連線中斷告警
 */
class ThresholdEngineTest {

    private MetricStore     store;
    private ThresholdEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        store  = new MetricStore(":memory:");
        engine = new ThresholdEngine(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private void threshold(String device, String metric, Double warning, Double critical) throws Exception {
        store.upsertDeviceThreshold(device, new ThresholdConfig.Builder(metric).warning(warning).critical(critical)
            .rearmMargin(5).triggerCount(3).rearmCount(2).build());
    }

    private List<Map<String, Object>> events(String device) {
        return store.queryAlerts(device, 100);
    }

    private Map<String, Object> last(String device) {
        List<Map<String, Object>> e = events(device);
        return e.isEmpty() ? null : e.get(0);
    }

    /** 告警描述（系統文字以訊息代碼保存，轉成正體中文比對） */
    private static String msg(Map<String, Object> event) {
        return io.github.chiayi1224.simplesnmp.i18n.StoredText.localize((String) event.get("message"));
    }

    private void cpu(String device, double... values) {
        for (double v : values) engine.evaluateCpu(device, v);
    }

    // ─── 每設備閾值 ───────────────────────────────────────────────

    @Test
    void 兩台設備不同門檻() throws Exception {
        threshold("A", "cpu", null, 80.0);
        threshold("B", "cpu", null, 95.0);
        cpu("A", 90, 90, 90);
        cpu("B", 90, 90, 90);

        assertEquals("CRITICAL", last("A").get("severity"));
        assertTrue(events("B").isEmpty());
    }

    @Test
    void 沒有閾值不告警() {
        cpu("A", 100, 100, 100, 100);
        engine.evaluate("A", "temperature", 999);
        assertTrue(events("A").isEmpty());
    }

    @Test
    void Warning觸發後升級為Critical並記錄等級() throws Exception {
        threshold("A", "cpu", 80.0, 95.0);
        cpu("A", 85, 85, 85);
        assertEquals("TRIGGERED", last("A").get("eventType"));
        assertEquals("WARNING", last("A").get("severity"));

        cpu("A", 97, 97, 97);
        assertEquals("CHANGED", last("A").get("eventType"));
        assertEquals("CRITICAL", last("A").get("severity"));
        assertEquals(Severity.CRITICAL, store.openAlertSeverity("A", "cpu"));
    }

    @Test
    void 記憶體與自訂指標() throws Exception {
        threshold("A", "memory", null, 85.0);
        store.upsertDeviceThreshold("A", new ThresholdConfig.Builder("free_ports")
            .direction(ThresholdConfig.Direction.LOW).critical(5.0).rearmMargin(3).triggerCount(2).rearmCount(2).build());
        for (int i = 0; i < 3; i++) engine.evaluateMemory("A", 90);
        engine.evaluate("A", "free_ports", 2);
        engine.evaluate("A", "free_ports", 2);

        assertEquals(Severity.CRITICAL, store.openAlertSeverity("A", "memory"));
        assertEquals(Severity.CRITICAL, store.openAlertSeverity("A", "free_ports"));
    }

    // ─── 磁碟 ─────────────────────────────────────────────────────

    private static List<DiskInfo> disks(double rootPct, double dataPct) {
        return List.of(new DiskInfo("/", 100, (long) rootPct), new DiskInfo("/data", 100, (long) dataPct));
    }

    @Test
    void 磁碟_套用設備預設_各掛載點各自告警() throws Exception {
        threshold("A", "disk", null, 80.0);
        for (int i = 0; i < 3; i++) engine.evaluateDisks("A", disks(85, 85));
        assertEquals(Severity.CRITICAL, store.openAlertSeverity("A", "disk:/"));
        assertEquals(Severity.CRITICAL, store.openAlertSeverity("A", "disk:/data"));
    }

    @Test
    void 磁碟_掛載點覆寫() throws Exception {
        threshold("A", "disk", null, 80.0);
        threshold("A", "disk:/data", null, 95.0);
        for (int i = 0; i < 3; i++) engine.evaluateDisks("A", disks(50, 90));
        assertNull(store.openAlertSeverity("A", "disk:/data"));
    }

    @Test
    void 磁碟_只有覆寫沒有預設() throws Exception {
        threshold("A", "disk:/data", null, 95.0);
        for (int i = 0; i < 3; i++) engine.evaluateDisks("A", disks(99, 10));
        assertNull(store.openAlertSeverity("A", "disk:/"));
    }

    // ─── 閾值移除與變更 ───────────────────────────────────────────

    @Test
    void 刪除有未解除告警的閾值_下一次評估記錄解除_之後不再告警() throws Exception {
        threshold("A", "cpu", null, 80.0);
        cpu("A", 90, 90, 90);
        long id = (Long) store.queryDeviceThresholds("A").get(0).get("id");
        store.deleteDeviceThreshold(id);

        cpu("A", 99);
        assertEquals("RESOLVED", last("A").get("eventType"));
        assertEquals("閾值已移除", msg(last("A")));
        cpu("A", 99, 99, 99, 99);
        assertEquals(2, events("A").size());
    }

    @Test
    void 閾值修改後下一次評估立即生效() throws Exception {
        threshold("A", "cpu", null, 85.0);
        cpu("A", 90, 90);
        threshold("A", "cpu", null, 80.0);
        cpu("A", 90);
        assertEquals("TRIGGERED", last("A").get("eventType"), "計數保留，第 3 次依新門檻觸發");
        assertEquals(80.0, (Double) last("A").get("thresholdVal"), 1e-9);
    }

    // ─── 重啟還原 ─────────────────────────────────────────────────

    @Test
    void 重啟後持續超標不重複觸發() throws Exception {
        threshold("A", "cpu", null, 80.0);
        cpu("A", 90, 90, 90);

        ThresholdEngine restarted = new ThresholdEngine(store);
        for (int i = 0; i < 5; i++) restarted.evaluateCpu("A", 90);
        assertEquals(1, events("A").size());
    }

    @Test
    void 重啟後恢復會解除() throws Exception {
        threshold("A", "cpu", 70.0, 80.0);
        cpu("A", 90, 90, 90);

        ThresholdEngine restarted = new ThresholdEngine(store);
        restarted.evaluateCpu("A", 10);
        restarted.evaluateCpu("A", 10);
        assertEquals("RESOLVED", last("A").get("eventType"));
        assertEquals("CRITICAL", last("A").get("severity"));
    }

    @Test
    void 告警在別處被解除後重新同步_可再次觸發() throws Exception {
        threshold("A", "cpu", null, 80.0);
        cpu("A", 90, 90, 90);
        store.resolveOpenAlerts("A", m -> true, "設備已刪除");

        cpu("A", 90, 90, 90);
        assertEquals("TRIGGERED", last("A").get("eventType"));
        assertEquals(3, events("A").size());
    }

    // ─── 連線中斷告警 ─────────────────────────────────────────────

    private static DeviceConfig device(String severity, int count) {
        return new DeviceConfig.Builder("A", "10.0.0.1").unreachableSeverity(severity).unreachableCount(count).build();
    }

    @Test
    void 連線中斷_預設關閉不告警() {
        for (int i = 0; i < 3; i++) engine.evaluateReachability(device("", 1), DeviceStatus.SNMP_ERROR);
        assertTrue(events("A").isEmpty());
    }

    @Test
    void 連線中斷_連續達到次數後告警_恢復後解除() {
        DeviceConfig d = device("CRITICAL", 2);
        engine.evaluateReachability(d, DeviceStatus.SNMP_ERROR);
        assertTrue(events("A").isEmpty());
        engine.evaluateReachability(d, DeviceStatus.SNMP_ERROR);
        Map<String, Object> e = last("A");
        assertEquals("device_unreachable", e.get("metricName"));
        assertEquals("CRITICAL", e.get("severity"));
        assertEquals("SNMP 無回應", msg(e));

        engine.evaluateReachability(d, DeviceStatus.ONLINE);
        assertEquals("RESOLVED", last("A").get("eventType"));
        assertEquals("連線恢復", msg(last("A")));
    }

    @Test
    void 連線中斷_Ping失敗訊息與WARNING等級() {
        engine.evaluateReachability(device("WARNING", 1), DeviceStatus.OFFLINE);
        assertEquals("WARNING", last("A").get("severity"));
        assertTrue(msg(last("A")).contains("Ping"));
    }

    @Test
    void 連線中斷_關閉設定後解除() {
        engine.evaluateReachability(device("CRITICAL", 1), DeviceStatus.SNMP_ERROR);
        engine.evaluateReachability(device("", 1), DeviceStatus.SNMP_ERROR);
        assertEquals("RESOLVED", last("A").get("eventType"));
        assertNull(store.openAlertSeverity("A", "device_unreachable"));
    }

    @Test
    void 連線中斷_等級由WARNING改為CRITICAL時升級() {
        engine.evaluateReachability(device("WARNING", 1), DeviceStatus.SNMP_ERROR);
        engine.evaluateReachability(device("CRITICAL", 1), DeviceStatus.SNMP_ERROR);
        assertEquals("CHANGED", last("A").get("eventType"));
        assertEquals("CRITICAL", last("A").get("severity"));
    }
}
