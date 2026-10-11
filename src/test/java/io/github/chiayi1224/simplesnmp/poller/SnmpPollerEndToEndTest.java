package io.github.chiayi1224.simplesnmp.poller;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import io.github.chiayi1224.simplesnmp.snmp.TestSnmpAgent;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.chiayi1224.simplesnmp.profile.BuiltinProfiles;
import io.github.chiayi1224.simplesnmp.profile.MetricKind;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import org.snmp4j.PDU;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Gauge32;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Poller 端對端測試：對本機 TestSnmpAgent 以 v1 / v2c / v3 輪詢，寫入真實的 in-memory DB。
 * 預設模擬 Net-SNMP 主機；個別測試改寫 sysObjectID 與 MIB 模擬 Cisco、未知廠牌與自訂範本。
 */
class SnmpPollerEndToEndTest {

    private static final String LINUX_SYS_OID = "1.3.6.1.4.1.8072.3.2.10";

    private MetricStore   store;
    private SnmpSession   session;
    private TestSnmpAgent agent;
    private SnmpPoller    poller;

    @BeforeEach
    void setUp() throws Exception {
        store = new MetricStore(":memory:");
        store.upsertSetting("ping_enabled", "false", "");
        session = new SnmpSession();
        agent = new TestSnmpAgent()
            .put(OidConstants.SYS_OBJECT_ID, new OID(LINUX_SYS_OID))
            .put(OidConstants.CPU_IDLE, 75)                 // CPU 25%
            .put(OidConstants.MEM_TOTAL, 1000)
            .put(OidConstants.MEM_FREE, 200)
            .put(OidConstants.MEM_BUFFER, 100)
            .put(OidConstants.MEM_CACHE, 200)               // 記憶體 50%
            // hrStorage：index 1 為 RAM（應被略過）、index 31 為固定磁碟 "/"
            .put(OidConstants.HR_STORAGE_TYPE + ".1", new OID("1.3.6.1.2.1.25.2.1.2"))
            .put(OidConstants.HR_STORAGE_DESCR + ".1", "Physical memory")
            .put(OidConstants.HR_STORAGE_TYPE + ".31", new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK))
            .put(OidConstants.HR_STORAGE_DESCR + ".31", "/")
            .put(OidConstants.HR_STORAGE_UNIT + ".31", 4096)
            .put(OidConstants.HR_STORAGE_SIZE + ".31", new Integer32(1000))
            .put(OidConstants.HR_STORAGE_USED + ".31", new Integer32(800))   // 磁碟 80%
            .addUser(new UsmUser(new OctetString("monitor"),
                AuthHMAC192SHA256.ID, new OctetString("authPass1"), PrivAES128.ID, new OctetString("privPass1")));
        poller = new SnmpPoller(store, 60, session);
    }

    @AfterEach
    void tearDown() throws Exception {
        agent.close();
        session.close();
        store.close();
    }

    private DeviceConfig.Builder device() {
        return new DeviceConfig.Builder("Host-1", "127.0.0.1").port(agent.port()).retries(0).timeoutMs(800);
    }

    @SuppressWarnings("unchecked")
    private void assertCollected() {
        assertEquals("ONLINE", store.queryDeviceByName("Host-1").get("status"));
        Map<String, Object> status = store.queryLatestStatus().get(0);
        assertEquals(25.0, (Double) ((Map<String, Object>) status.get("cpu")).get("usedPct"), 0.001);
        assertEquals(50.0, (Double) ((Map<String, Object>) status.get("memory")).get("usedPct"), 0.001);
        List<Map<String, Object>> disks = (List<Map<String, Object>>) status.get("disks");
        assertEquals(1, disks.size(), "只應包含固定磁碟");
        assertEquals("/", disks.get(0).get("mountPoint"));
        assertEquals(80.0, (Double) disks.get(0).get("usedPct"), 0.001);
        assertEquals(4096L * 1000, disks.get(0).get("totalBytes"));
    }

    @Test
    void v2c_輪詢() throws Exception {
        store.addDevice(device().build());
        poller.pollAll();
        assertCollected();
    }

    @Test
    void v1_輪詢() throws Exception {
        store.addDevice(device().snmpVersion(DeviceConfig.SnmpVersion.V1).build());
        poller.pollAll();
        assertCollected();
    }

    @Test
    void v3_authPriv輪詢() throws Exception {
        store.addDevice(device().snmpVersion(DeviceConfig.SnmpVersion.V3).v3User("monitor")
            .v3AuthProtocol(V3UserConfig.AuthProtocol.SHA256).v3AuthPassword("authPass1")
            .v3PrivProtocol(V3UserConfig.PrivProtocol.AES128).v3PrivPassword("privPass1").build());
        poller.pollAll();
        assertCollected();
    }

    @Test
    void v3_密碼錯誤標記SNMP_ERROR() throws Exception {
        store.addDevice(device().snmpVersion(DeviceConfig.SnmpVersion.V3).v3User("monitor")
            .v3AuthProtocol(V3UserConfig.AuthProtocol.SHA256).v3AuthPassword("WRONGpass1")
            .v3PrivProtocol(V3UserConfig.PrivProtocol.AES128).v3PrivPassword("privPass1").build());
        poller.pollAll();

        assertEquals("SNMP_ERROR", store.queryDeviceByName("Host-1").get("status"));
        assertTrue(store.queryLatestStatus().isEmpty(), "認證失敗不應寫入任何指標");
    }

    @Test
    void 設備無回應標記SNMP_ERROR() throws Exception {
        agent.silent(true);
        store.addDevice(device().build());
        poller.pollAll();

        assertEquals("SNMP_ERROR", store.queryDeviceByName("Host-1").get("status"));
    }

    // ─── 連線中斷告警 ─────────────────────────────────────────────

    private List<Map<String, Object>> unreachableEvents() {
        return store.queryAlerts("Host-1", 100).stream()
            .filter(e -> "device_unreachable".equals(e.get("metricName"))).toList();
    }

    @Test
    void 連線中斷_預設不告警() throws Exception {
        agent.silent(true);
        store.addDevice(device().build());
        poller.pollAll();
        poller.pollAll();

        assertEquals("SNMP_ERROR", store.queryDeviceByName("Host-1").get("status"));
        assertTrue(unreachableEvents().isEmpty());
    }

    @Test
    void 連線中斷_CRITICAL連續2次後觸發_恢復ONLINE後解除() throws Exception {
        agent.silent(true);
        store.addDevice(device().unreachableSeverity("CRITICAL").unreachableCount(2).build());
        poller.pollAll();
        assertTrue(unreachableEvents().isEmpty(), "第 1 次不告警");
        poller.pollAll();
        Map<String, Object> e = unreachableEvents().get(0);
        assertEquals("TRIGGERED", e.get("eventType"));
        assertEquals("CRITICAL", e.get("severity"));
        assertEquals("SNMP 無回應", e.get("message"));

        agent.silent(false);
        poller.pollAll();
        assertEquals("RESOLVED", unreachableEvents().get(0).get("eventType"));
        assertEquals("ONLINE", store.queryDeviceByName("Host-1").get("status"));
    }

    @Test
    void 連線中斷_關閉設定後解除() throws Exception {
        agent.silent(true);
        store.addDevice(device().unreachableSeverity("WARNING").build());
        poller.pollAll();
        assertEquals("WARNING", unreachableEvents().get(0).get("severity"));

        store.updateDeviceByName(device().unreachableSeverity("").build(), true);
        poller.pollAll();
        assertEquals("RESOLVED", unreachableEvents().get(0).get("eventType"));
    }

    // ─── 依範本收集 ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> latest() {
        return store.queryLatestStatus().stream()
            .filter(m -> "Host-1".equals(m.get("deviceName"))).findFirst().orElseThrow();
    }

    private static double pct(Map<String, Object> status, String metric) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) status.get(metric);
        return (Double) m.get("usedPct");
    }

    @Test
    void 首次輪詢保存sysObjectID_同一週期套用Net_SNMP範本() throws Exception {
        store.addDevice(device().build());
        poller.pollAll();

        Map<String, Object> d = store.queryDeviceByName("Host-1");
        assertEquals(LINUX_SYS_OID, d.get("sysObjectId"));
        assertEquals(BuiltinProfiles.NET_SNMP, d.get("appliedProfileName"));
        assertCollected();
    }

    @Test
    void Net_SNMP回歸_數值與改寫前的計算完全相同() throws Exception {
        long totalKb = 16_384_256, freeKb = 1_234_567, bufferKb = 234_567, cacheKb = 3_456_789;
        agent.put(OidConstants.CPU_IDLE, 87)
            .put(OidConstants.MEM_TOTAL, new Integer32((int) totalKb))
            .put(OidConstants.MEM_FREE, new Integer32((int) freeKb))
            .put(OidConstants.MEM_BUFFER, new Integer32((int) bufferKb))
            .put(OidConstants.MEM_CACHE, new Integer32((int) cacheKb))
            .put(OidConstants.HR_STORAGE_SIZE + ".31", new Integer32(2_621_440))
            .put(OidConstants.HR_STORAGE_USED + ".31", new Integer32(1_234_567))
            // 第二個固定磁碟與一個網路磁碟（應略過）
            .put(OidConstants.HR_STORAGE_TYPE + ".32", new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK))
            .put(OidConstants.HR_STORAGE_DESCR + ".32", "/home")
            .put(OidConstants.HR_STORAGE_UNIT + ".32", 1024)
            .put(OidConstants.HR_STORAGE_SIZE + ".32", new Integer32(1_000_000))
            .put(OidConstants.HR_STORAGE_USED + ".32", new Integer32(999_999))
            .put(OidConstants.HR_STORAGE_TYPE + ".40", new OID("1.3.6.1.2.1.25.2.1.10"))
            .put(OidConstants.HR_STORAGE_DESCR + ".40", "/mnt/nfs");
        store.addDevice(device().build());
        poller.pollAll();

        // 改寫前 PollParser 的公式（ssCpuIdle、UCD 記憶體、hrStorage 固定磁碟）
        double cpu    = 100.0 - 87;
        long   usedKb = totalKb - freeKb - bufferKb - cacheKb;
        double mem    = (double) usedKb / totalKb * 100.0;
        double root   = (double) (1_234_567L * 4096) / (2_621_440L * 4096) * 100.0;
        double home   = (double) (999_999L * 1024) / (1_000_000L * 1024) * 100.0;

        Map<String, Object> s = latest();
        assertEquals(cpu, pct(s, "cpu"));
        assertEquals(mem, pct(s, "memory"));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) s.get("memory");
        assertEquals(totalKb, m.get("totalKb"));
        assertEquals(usedKb,  m.get("usedKb"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) s.get("disks");
        assertEquals(List.of("/", "/home"), disks.stream().map(x -> x.get("mountPoint")).toList());
        assertEquals(root, disks.get(0).get("usedPct"));
        assertEquals(2_621_440L * 4096, disks.get(0).get("totalBytes"));
        assertEquals(1_234_567L * 4096, disks.get(0).get("usedBytes"));
        assertEquals(home, disks.get(1).get("usedPct"));
    }

    @Test
    void Cisco設備_套用Cisco_IOS範本收集CPU與記憶體() throws Exception {
        try (TestSnmpAgent cisco = new TestSnmpAgent()
                .put(OidConstants.SYS_OBJECT_ID, new OID("1.3.6.1.4.1.9.1.1208"))
                .put("1.3.6.1.4.1.9.9.109.1.1.1.1.8.1", new Gauge32(30))
                .put("1.3.6.1.4.1.9.9.109.1.1.1.1.8.2", new Gauge32(50))
                .put("1.3.6.1.4.1.9.9.48.1.1.1.5.1", new Gauge32(300L * 1024 * 1024))
                .put("1.3.6.1.4.1.9.9.48.1.1.1.6.1", new Gauge32(700L * 1024 * 1024))) {
            store.addDevice(new DeviceConfig.Builder("Host-1", "127.0.0.1").port(cisco.port()).retries(0).timeoutMs(800).build());
            poller.pollAll();

            Map<String, Object> d = store.queryDeviceByName("Host-1");
            assertEquals("ONLINE", d.get("status"));
            assertEquals(BuiltinProfiles.CISCO_IOS, d.get("appliedProfileName"));
            Map<String, Object> s = latest();
            assertEquals(40.0, pct(s, "cpu"), 1e-9);
            assertEquals(30.0, pct(s, "memory"), 1e-9);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) s.get("memory");
            assertEquals(1000L * 1024, m.get("totalKb"), "300 MiB + 700 MiB = 1000 MiB");
            assertTrue(((List<?>) s.get("disks")).isEmpty(), "Cisco 範本不收集磁碟");
        }
    }

    @Test
    void 未知廠牌_套用HOST_RESOURCES範本() throws Exception {
        String ram = "1.3.6.1.2.1.25.2.1.2";
        try (TestSnmpAgent win = new TestSnmpAgent()
                .put(OidConstants.SYS_OBJECT_ID, new OID("1.3.6.1.4.1.311.1.1.3.1.2"))
                .put("1.3.6.1.2.1.25.3.3.1.2.1", 10)
                .put("1.3.6.1.2.1.25.3.3.1.2.2", 30)
                .put(OidConstants.HR_STORAGE_TYPE + ".1", new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK))
                .put(OidConstants.HR_STORAGE_DESCR + ".1", "C:\\")
                .put(OidConstants.HR_STORAGE_UNIT + ".1", 4096)
                .put(OidConstants.HR_STORAGE_SIZE + ".1", 1000)
                .put(OidConstants.HR_STORAGE_USED + ".1", 600)
                .put(OidConstants.HR_STORAGE_TYPE + ".5", new OID(ram))
                .put(OidConstants.HR_STORAGE_DESCR + ".5", "Physical Memory")
                .put(OidConstants.HR_STORAGE_UNIT + ".5", 65536)
                .put(OidConstants.HR_STORAGE_SIZE + ".5", 1000)
                .put(OidConstants.HR_STORAGE_USED + ".5", 250)) {
            store.addDevice(new DeviceConfig.Builder("Host-1", "127.0.0.1").port(win.port()).retries(0).timeoutMs(800).build());
            poller.pollAll();

            Map<String, Object> d = store.queryDeviceByName("Host-1");
            assertEquals(BuiltinProfiles.HOST_RESOURCES, d.get("appliedProfileName"));
            Map<String, Object> s = latest();
            assertEquals(20.0, pct(s, "cpu"), 1e-9);
            assertEquals(25.0, pct(s, "memory"), 1e-9);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> disks = (List<Map<String, Object>>) s.get("disks");
            assertEquals(1, disks.size(), "RAM 不應被當成磁碟");
            assertEquals(60.0, (Double) disks.get(0).get("usedPct"), 1e-9);
            // hrStorage 只 walk 一次（記憶體與磁碟共用）：GETBULK 次數 = hrProcessorLoad 1 次 + hrStorage 1 次
            long bulks = win.receivedPduTypes().stream().filter(t -> t == PDU.GETBULK).count();
            assertEquals(2, bulks);
        }
    }

    private long customProfile(boolean diskEnabled) throws Exception {
        return store.createProfile(new MetricProfile(null, "Switch-X", "", "1.3.6.1.4.1.8072.3.2", false, List.of(
            new ProfileMetric("cpu", MetricKind.HUNDRED_MINUS, Map.of("oid", OidConstants.CPU_IDLE), "%", true),
            new ProfileMetric("temperature", MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.99999.3.1.0"), "°C", true),
            new ProfileMetric("free_ports", MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.99999.4.1.0"), "", true),
            new ProfileMetric("in_octets_k", MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.99999.5.1.0", "scale", "0.001"), "K", true),
            new ProfileMetric("disk", MetricKind.STORAGE_TABLE,
                Map.of("typeFilter", OidConstants.HR_STORAGE_TYPE_FIXED_DISK), "%", diskEnabled))));
    }

    @Test
    void 自訂範本_最長前綴優先_單一指標失敗不影響其他_v1() throws Exception {
        agent.put("1.3.6.1.4.1.99999.4.1.0", 2)
             .put("1.3.6.1.4.1.99999.5.1.0", new Counter32(123_456));
        // temperature 的 OID 不存在；v1 會讓整個 GET 回 noSuchName，Session 需移除後重送
        customProfile(false);
        store.addDevice(device().snmpVersion(DeviceConfig.SnmpVersion.V1).build());
        store.upsertDeviceThreshold("Host-1", new ThresholdConfig.Builder("free_ports").direction(ThresholdConfig.Direction.LOW)
            .critical(5.0).rearmMargin(3).triggerCount(2).rearmCount(2).build());

        poller.pollAll();
        poller.pollAll();

        Map<String, Object> d = store.queryDeviceByName("Host-1");
        assertEquals("ONLINE", d.get("status"));
        assertEquals("Switch-X", d.get("appliedProfileName"), "較長的前綴優先於內建 Net-SNMP");

        Map<String, Object> s = latest();
        assertEquals(25.0, pct(s, "cpu"), 1e-9);
        assertNull(s.get("memory"), "自訂範本沒有 memory 指標");
        assertTrue(((List<?>) s.get("disks")).isEmpty(), "停用的 disk 不收集");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> custom = (List<Map<String, Object>>) s.get("custom");
        assertEquals(List.of("free_ports", "in_octets_k"), custom.stream().map(x -> x.get("metricKey")).toList(),
            "temperature 失敗不應有數值");
        assertEquals(2.0, custom.get(0).get("value"));
        assertEquals(123.456, (Double) custom.get(1).get("value"), 1e-9);
        assertEquals("K", custom.get(1).get("unit"));

        assertTrue(store.hasOpenAlert("Host-1", "free_ports"), "LOW 方向連續 2 次低於 5 應觸發");
        assertFalse(agent.receivedPduTypes().contains(PDU.GETNEXT), "停用的 disk 不應走訪 hrStorage");
    }

    private void addSecondDisk() {
        agent.put(OidConstants.HR_STORAGE_TYPE + ".32", new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK))
             .put(OidConstants.HR_STORAGE_DESCR + ".32", "/data")
             .put(OidConstants.HR_STORAGE_UNIT + ".32", 4096)
             .put(OidConstants.HR_STORAGE_SIZE + ".32", new Integer32(1000))
             .put(OidConstants.HR_STORAGE_USED + ".32", new Integer32(300));
    }

    @SuppressWarnings("unchecked")
    private List<String> diskMounts() {
        Map<String, Object> status = store.queryLatestStatus().get(0);
        return ((List<Map<String, Object>>) status.get("disks")).stream().map(d -> (String) d.get("mountPoint")).toList();
    }

    @Test
    void 磁碟過濾_只記錄符合的掛載點() throws Exception {
        addSecondDisk();
        store.addDevice(device().diskFilter("/data").build());
        poller.pollAll();

        assertEquals("ONLINE", store.queryDeviceByName("Host-1").get("status"));
        assertEquals(List.of("/data"), diskMounts());
        assertEquals("/data", store.queryDeviceByName("Host-1").get("diskFilter"), "過濾條件應存入資料庫並可由設備 API 讀回");
    }

    @Test
    void 磁碟過濾_空白代表全部() throws Exception {
        addSecondDisk();
        store.addDevice(device().build());
        poller.pollAll();
        assertEquals(List.of("/", "/data"), diskMounts());
    }

    @Test
    void 磁碟過濾_沒有任何符合時不寫入磁碟但其他指標照常() throws Exception {
        addSecondDisk();
        store.addDevice(device().diskFilter("/nope").build());
        poller.pollAll();

        assertEquals("ONLINE", store.queryDeviceByName("Host-1").get("status"));
        assertTrue(diskMounts().isEmpty());
        assertNotNull(store.queryLatestStatus().get(0).get("cpu"), "CPU 不受磁碟過濾影響");
    }

    @Test
    void 手動指定範本優先於sysObjectID() throws Exception {
        long id = customProfile(true);
        MetricProfile cisco = store.loadProfiles().stream()
            .filter(p -> BuiltinProfiles.CISCO_IOS.equals(p.name())).findFirst().orElseThrow();
        store.addDevice(device().profileId(cisco.id()).build());
        poller.pollAll();

        Map<String, Object> d = store.queryDeviceByName("Host-1");
        assertEquals(BuiltinProfiles.CISCO_IOS, d.get("appliedProfileName"));
        assertEquals("ONLINE", d.get("status"), "Cisco OID 都不存在，但設備有回應");
        assertTrue(store.queryLatestStatus().isEmpty(), "Net-SNMP 的數值不應因 sysObjectID 而被收集");
        assertNotEquals(id, cisco.id());
    }
}
