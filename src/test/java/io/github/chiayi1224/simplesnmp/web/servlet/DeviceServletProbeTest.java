package io.github.chiayi1224.simplesnmp.web.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.profile.ProfileCollector;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import io.github.chiayi1224.simplesnmp.snmp.TestSnmpAgent;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 磁碟探測（POST action=probe-disks）：以本機 SNMP agent 與真實 DB 做端對端測試 */
class DeviceServletProbeTest {

    private MetricStore   store;
    private SnmpSession   session;
    private TestSnmpAgent agent;
    private DeviceServlet servlet;
    private HttpServletRequest  req;
    private HttpServletResponse resp;
    private StringWriter out;

    @BeforeEach
    void setUp() throws Exception {
        store   = new MetricStore(":memory:");
        session = new SnmpSession();
        agent   = new TestSnmpAgent()
            .put(OidConstants.HR_STORAGE_TYPE + ".1", new OID("1.3.6.1.2.1.25.2.1.2"))     // RAM：不應出現
            .put(OidConstants.HR_STORAGE_DESCR + ".1", "Physical memory");
        addDisk(36, "/", 1000, 800);
        addDisk(31, "/boot/efi", 100, 10);

        servlet = new DeviceServlet(store, mock(TrapReceiver.class), new ProfileCollector(session));
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        out  = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(out));
    }

    @AfterEach
    void tearDown() throws Exception {
        agent.close();
        session.close();
        store.close();
    }

    private void addDisk(int index, String name, int size, int used) {
        agent.put(OidConstants.HR_STORAGE_TYPE + "." + index, new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK))
             .put(OidConstants.HR_STORAGE_DESCR + "." + index, name)
             .put(OidConstants.HR_STORAGE_UNIT + "." + index, 4096)
             .put(OidConstants.HR_STORAGE_SIZE + "." + index, new Integer32(size))
             .put(OidConstants.HR_STORAGE_USED + "." + index, new Integer32(used));
    }

    private JsonNode post(String path, String json) throws Exception {
        when(req.getPathInfo()).thenReturn(path);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        servlet.doPost(req, resp);
        return new ObjectMapper().readTree(out.toString());
    }

    @Test
    void 新設備_以對話框連線資料探測_列出固定磁碟並依名稱排序() throws Exception {
        JsonNode r = post(null, "{\"action\":\"probe-disks\",\"host\":\"127.0.0.1\",\"port\":" + agent.port()
            + ",\"community\":\"public\"}");

        assertEquals(2, r.get("disks").size(), "RAM 不應被當成磁碟");
        assertEquals("/",          r.get("disks").get(0).get("mountPoint").asText());
        assertEquals("/boot/efi",  r.get("disks").get(1).get("mountPoint").asText());
        assertEquals(80.0,         r.get("disks").get(0).get("usedPct").asDouble(), 0.001);
        assertEquals(4096L * 1000, r.get("disks").get(0).get("totalBytes").asLong());
        verify(resp, never()).setStatus(anyInt());
        assertTrue(store.queryAllDevices().isEmpty(), "探測不應新增設備");
    }

    @Test
    void 既有設備_community留空時使用已儲存的值() throws Exception {
        agent.community("secret");
        store.addDevice(new DeviceConfig.Builder("Host-1", "127.0.0.1").port(agent.port()).community("secret").build());

        JsonNode r = post("/Host-1", "{\"action\":\"probe-disks\",\"host\":\"127.0.0.1\",\"port\":" + agent.port()
            + ",\"community\":\"\"}");

        assertEquals(2, r.get("disks").size());
    }

    @Test
    void 設備無回應回502() throws Exception {
        agent.silent(true);
        JsonNode r = post(null, "{\"action\":\"probe-disks\",\"host\":\"127.0.0.1\",\"port\":" + agent.port() + "}");

        verify(resp).setStatus(502);
        assertTrue(r.get("error").asText().contains("設備無法查詢"), r.toString());
    }

    @Test
    void 設備沒有hrStorage回502並說明() throws Exception {
        try (TestSnmpAgent bare = new TestSnmpAgent().put(OidConstants.SYS_OBJECT_ID, new OID("1.3.6.1.4.1.9.1.1"))) {
            JsonNode r = post(null, "{\"action\":\"probe-disks\",\"host\":\"127.0.0.1\",\"port\":" + bare.port() + "}");

            verify(resp).setStatus(502);
            assertTrue(r.get("error").asText().contains("沒有提供磁碟資訊"));
        }
    }

    @Test
    void 缺少host回400_不存在的設備回404_v3缺使用者回400() throws Exception {
        post(null, "{\"action\":\"probe-disks\"}");
        verify(resp).setStatus(400);

        reset(resp);
        out.getBuffer().setLength(0);
        when(resp.getWriter()).thenReturn(new PrintWriter(out));
        post("/NoSuch", "{\"action\":\"probe-disks\",\"host\":\"127.0.0.1\"}");
        verify(resp).setStatus(404);

        reset(resp);
        out.getBuffer().setLength(0);
        when(resp.getWriter()).thenReturn(new PrintWriter(out));
        post(null, "{\"action\":\"probe-disks\",\"host\":\"127.0.0.1\",\"snmpVersion\":\"3\"}");
        verify(resp).setStatus(400);
    }

    @Test
    void 未啟用探測功能回503() throws Exception {
        servlet = new DeviceServlet(store, mock(TrapReceiver.class));
        post(null, "{\"action\":\"probe-disks\",\"host\":\"127.0.0.1\"}");
        verify(resp).setStatus(503);
    }
}
