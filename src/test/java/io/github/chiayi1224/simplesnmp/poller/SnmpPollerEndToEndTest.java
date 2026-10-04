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
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Poller 端對端測試：對本機 TestSnmpAgent（模擬 Net-SNMP 主機）以 v1 / v2c / v3 輪詢，寫入真實的 in-memory DB
 */
class SnmpPollerEndToEndTest {

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
}
