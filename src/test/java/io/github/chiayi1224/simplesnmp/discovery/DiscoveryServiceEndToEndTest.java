package io.github.chiayi1224.simplesnmp.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.i18n.StoredText;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import io.github.chiayi1224.simplesnmp.snmp.TestSnmpAgent;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiscoveryService 端對端測試：對本機 TestSnmpAgent 執行完整掃描並檢查寫入 DB 的結果
 */
class DiscoveryServiceEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetricStore      store;
    private SnmpSession      session;
    private TestSnmpAgent    agent;
    private DiscoveryService service;

    @BeforeEach
    void setUp() throws Exception {
        store   = new MetricStore(":memory:");
        session = new SnmpSession();
        agent   = linuxAgent();
        service = new DiscoveryService(store, session, DiscoveryService.Limits.DEFAULT);
        store.addDevice(device(agent, "Linux-1"));
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
        agent.close();
        session.close();
        store.close();
    }

    /** 模擬 Net-SNMP Linux 主機 */
    private static TestSnmpAgent linuxAgent() throws Exception {
        TestSnmpAgent a = new TestSnmpAgent()
            .put(DiscoveryService.SYS_DESCR, "Linux web01 6.1.0 x86_64")
            .put(DiscoveryService.SYS_OBJECT_ID, new OID("1.3.6.1.4.1.8072.3.2.10"))
            .put(DiscoveryService.SYS_UPTIME, new TimeTicks(123456))
            .put(DiscoveryService.SYS_NAME, "web01")
            .put(MibProbes.HR_PROCESSOR_LOAD + ".196608", 12)
            .put(MibProbes.HR_PROCESSOR_LOAD + ".196609", 30)
            .put(MibProbes.HR_STORAGE_ENTRY + ".2.31", new OID("1.3.6.1.2.1.25.2.1.4"))
            .put(MibProbes.HR_STORAGE_ENTRY + ".3.31", "/")
            .put(MibProbes.HR_STORAGE_ENTRY + ".4.31", 4096)
            .put(MibProbes.HR_STORAGE_ENTRY + ".5.31", new Integer32(1000))
            .put(MibProbes.HR_STORAGE_ENTRY + ".6.31", new Integer32(250))
            .put(MibProbes.UCD_CPU_USER, 5)
            .put(MibProbes.UCD_CPU_SYSTEM, 3)
            .put(MibProbes.UCD_CPU_IDLE, 92)
            .put(MibProbes.UCD_MEM_TOTAL_REAL, 8_000_000)
            .put(MibProbes.UCD_MEM_AVAIL_REAL, 2_000_000)
            .put("1.3.6.1.4.1.8072.1.2.1.0", "net-snmp 子樹");
        for (int i = 1; i <= 3; i++) a.put(MibProbes.IF_DESCR + "." + i, "eth" + (i - 1));
        return a;
    }

    private static DeviceConfig device(TestSnmpAgent a, String name) {
        return new DeviceConfig.Builder(name, "127.0.0.1").port(a.port()).retries(0).timeoutMs(500).build();
    }

    private Map<String, Object> await(long scanId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> scan = store.queryScan(scanId);
            if (scan != null && !"RUNNING".equals(scan.get("status"))) return scan;
            Thread.sleep(50);
        }
        fail("掃描逾時未完成");
        return null;
    }

    private static JsonNode probe(Map<String, Object> scan, String key) throws Exception {
        for (JsonNode p : JSON.readTree((String) scan.get("probesJson"))) {
            if (key.equals(p.get("key").asText())) return p;
        }
        throw new AssertionError("找不到探測項目 " + key);
    }

    @Test
    void Linux主機_系統資訊與MIB探測() throws Exception {
        Map<String, Object> scan = await(service.start("Linux-1", false, List.of()).scanId());

        assertEquals("COMPLETED", scan.get("status"));
        assertEquals("Net-SNMP", scan.get("vendor"));
        assertEquals("web01", scan.get("sysName"));
        assertEquals("1.3.6.1.4.1.8072.3.2.10", scan.get("sysObjectId"));
        assertTrue(scan.get("sysDescr").toString().startsWith("Linux"));

        assertTrue(probe(scan, "hr-processor").get("supported").asBoolean());
        assertEquals(2, probe(scan, "hr-processor").get("samples").size());
        assertTrue(StoredText.localize(probe(scan, "hr-storage").get("samples").get(0).asText()).contains("固定磁碟"));
        assertTrue(StoredText.localize(probe(scan, "hr-storage").get("samples").get(0).asText()).contains("25.0%"));
        assertTrue(StoredText.localize(probe(scan, "ucd-cpu").get("samples").get(0).asText()).contains("idle 92%"));
        assertTrue(probe(scan, "ucd-memory").get("supported").asBoolean());
        assertTrue(StoredText.localize(probe(scan, "if-mib").get("samples").get(0).asText()).contains("共 3 個介面"));
        assertFalse(probe(scan, "cisco-cpu").get("supported").asBoolean(), "Linux 不支援 Cisco MIB");
        assertFalse(probe(scan, "cisco-memory").get("supported").asBoolean());
        assertEquals(0L, scan.get("oidCount"), "未勾選 walk 時不收集 OID");
    }

    @Test
    void Cisco設備_辨識廠牌並支援Cisco_MIB() throws Exception {
        try (TestSnmpAgent cisco = new TestSnmpAgent()
                .put(DiscoveryService.SYS_DESCR, "Cisco IOS Software, C2960")
                .put(DiscoveryService.SYS_OBJECT_ID, new OID("1.3.6.1.4.1.9.1.1208"))
                .put(DiscoveryService.SYS_NAME, "core-sw")
                .put(MibProbes.CISCO_CPU_5MIN + ".1", 17)
                .put(MibProbes.CISCO_MEM_POOL + ".2.1", "Processor")
                .put(MibProbes.CISCO_MEM_POOL + ".5.1", new Integer32(50 * 1024 * 1024))
                .put(MibProbes.CISCO_MEM_POOL + ".6.1", new Integer32(150 * 1024 * 1024))) {
            store.addDevice(device(cisco, "Core-SW"));

            Map<String, Object> scan = await(service.start("Core-SW", false, List.of()).scanId());

            assertEquals("Cisco", scan.get("vendor"));
            assertTrue(StoredText.localize(probe(scan, "cisco-cpu").get("samples").get(0).asText()).contains("17%"));
            String mem = StoredText.localize(probe(scan, "cisco-memory").get("samples").get(0).asText());
            assertTrue(mem.contains("Processor") && mem.contains("50.0 MB") && mem.contains("150.0 MB"), mem);
            assertFalse(probe(scan, "ucd-cpu").get("supported").asBoolean());
        }
    }

    @Test
    void 完整walk_預設走訪MIB2與廠牌子樹() throws Exception {
        Map<String, Object> scan = await(service.start("Linux-1", true, List.of()).scanId());

        assertEquals("COMPLETED", scan.get("status"));
        assertFalse((Boolean) scan.get("truncated"));
        long total = (Long) store.queryScanOids((Long) scan.get("id"), null, null, 0, 1).get("total");
        // MIB-2：系統 4 + 處理器 2 + 儲存 5 + 介面 3 = 14；廠牌子樹（8072）1；UCD（2021）不在預設範圍
        assertEquals(15L, total);
        assertEquals(1L, store.queryScanOids((Long) scan.get("id"), "1.3.6.1.4.1.8072", null, 0, 10).get("total"));
    }

    @Test
    void 指定子樹() throws Exception {
        Map<String, Object> scan = await(service.start("Linux-1", true, List.of("1.3.6.1.4.1.2021")).scanId());

        assertEquals(5L, scan.get("oidCount"));
    }

    @Test
    void 超過筆數上限時截斷() throws Exception {
        service.close();
        service = new DiscoveryService(store, session, new DiscoveryService.Limits(4, 120_000));

        Map<String, Object> scan = await(service.start("Linux-1", true, List.of()).scanId());

        assertEquals("COMPLETED", scan.get("status"));
        assertTrue((Boolean) scan.get("truncated"));
        assertEquals(4L, scan.get("oidCount"));
    }

    @Test
    void 設備無回應_掃描失敗並記錄原因() throws Exception {
        agent.silent(true);

        Map<String, Object> scan = await(service.start("Linux-1", true, List.of()).scanId());

        assertEquals("FAILED", scan.get("status"));
        assertTrue(StoredText.localize(scan.get("error").toString()).contains("無回應"));
    }

    @Test
    void 同一設備重複啟動_回傳進行中的掃描() throws Exception {
        agent.silent(true);   // 讓第一次掃描停在等待逾時
        DiscoveryService.StartResult first  = service.start("Linux-1", false, List.of());
        DiscoveryService.StartResult second = service.start("Linux-1", false, List.of());

        assertFalse(first.alreadyRunning());
        assertTrue(second.alreadyRunning());
        assertEquals(first.scanId(), second.scanId());
        await(first.scanId());
        assertFalse(service.isRunning("Linux-1"));
    }

    @Test
    void 設備不存在() {
        assertThrows(DiscoveryService.DeviceNotFoundException.class, () -> service.start("Nope", false, List.of()));
    }

    @Test
    void 每台設備只保留最近3次() throws Exception {
        long first = 0;
        for (int i = 0; i < 4; i++) {
            long id = service.start("Linux-1", false, List.of()).scanId();
            if (i == 0) first = id;
            await(id);
        }
        assertEquals(3, store.queryScans("Linux-1").size());
        assertNull(store.queryScan(first));
    }

    @Test
    void 掃描只發出讀取請求() throws Exception {
        await(service.start("Linux-1", true, List.of()).scanId());

        assertFalse(agent.receivedPduTypes().isEmpty());
        assertFalse(agent.receivedPduTypes().contains(PDU.SET));
    }

    @Test
    void 不可列印的OCTET_STRING以hex顯示() {
        assertEquals("00:01:ff", DiscoveryService.display(new OctetString(new byte[]{ 0, 1, (byte) 0xff })));
        assertEquals("abc", DiscoveryService.display(new OctetString("abc")));
    }

    @Test
    void 預設子樹() {
        assertEquals(List.of("1.3.6.1.2.1", "1.3.6.1.4.1.9"),
            DiscoveryService.resolveSubtrees(List.of(), "1.3.6.1.4.1.9.1.1208"));
        assertEquals(List.of("1.3.6.1.2.1"), DiscoveryService.resolveSubtrees(List.of(" "), null));
        assertEquals(List.of("1.3.6.1.4.1.2021"), DiscoveryService.resolveSubtrees(List.of("1.3.6.1.4.1.2021", "1.3.6.1.4.1.2021"), null));
    }
}
