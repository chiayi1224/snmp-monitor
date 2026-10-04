package io.github.chiayi1224.simplesnmp.web.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.profile.BuiltinProfiles;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileCollector;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import io.github.chiayi1224.simplesnmp.snmp.TestSnmpAgent;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.OID;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 範本預覽端對端測試：真實 DB、SnmpSession 與本機 TestSnmpAgent（模擬 Cisco 設備）
 */
class ProfilePreviewEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MetricStore    store;
    private SnmpSession    session;
    private TestSnmpAgent  agent;
    private ProfileServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        store   = new MetricStore(":memory:");
        session = new SnmpSession();
        agent   = new TestSnmpAgent()
            .put(OidConstants.SYS_OBJECT_ID, new OID("1.3.6.1.4.1.9.1.1208"))
            .put("1.3.6.1.4.1.9.9.109.1.1.1.1.8.1", new Gauge32(12))
            .put("1.3.6.1.4.1.9.9.48.1.1.1.5.1", new Gauge32(250))
            .put("1.3.6.1.4.1.9.9.48.1.1.1.6.1", new Gauge32(750));
        store.addDevice(new DeviceConfig.Builder("Cisco-1", "127.0.0.1").port(agent.port()).retries(0).timeoutMs(800).build());
        servlet = new ProfileServlet(store, new ProfileCollector(session));
    }

    @AfterEach
    void tearDown() throws Exception {
        agent.close();
        session.close();
        store.close();
    }

    private JsonNode preview(String json, int expectedStatus) throws Exception {
        HttpServletRequest  req  = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter out = new StringWriter();
        when(req.getPathInfo()).thenReturn("/preview");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(resp.getWriter()).thenReturn(new PrintWriter(out));

        servlet.doPost(req, resp);

        if (expectedStatus == 200) verify(resp, never()).setStatus(anyInt());
        else verify(resp).setStatus(expectedStatus);
        return JSON.readTree(out.toString());
    }

    @Test
    void 以內建Cisco範本預覽_列出cpu與memory_不儲存() throws Exception {
        long cisco = store.loadProfiles().stream()
            .filter(p -> BuiltinProfiles.CISCO_IOS.equals(p.name())).map(MetricProfile::id).findFirst().orElseThrow();

        JsonNode r = preview("{\"deviceName\":\"Cisco-1\",\"profileId\":" + cisco + "}", 200);

        assertEquals(BuiltinProfiles.CISCO_IOS, r.path("profileName").asText());
        JsonNode results = r.path("results");
        assertEquals("cpu", results.get(0).path("metricKey").asText());
        assertTrue(results.get(0).path("ok").asBoolean());
        assertEquals(12.0, results.get(0).path("values").get(0).path("value").asDouble(), 1e-9);
        assertEquals("%", results.get(0).path("unit").asText());
        assertEquals(25.0, results.get(1).path("values").get(0).path("value").asDouble(), 1e-9);

        assertTrue(store.queryLatestStatus().isEmpty(), "預覽不應儲存數值");
        assertTrue(store.queryAlerts(null, 10).isEmpty(), "預覽不應產生告警");
    }

    @Test
    void 未儲存範本_OID不存在的指標失敗_其他指標照常_停用指標也試算() throws Exception {
        JsonNode r = preview("""
            {"deviceName":"Cisco-1","profile":{"metrics":[
              {"metricKey":"temperature","kind":"VALUE","params":{"oid":"1.3.6.1.4.1.9.9.13.1.3.1.3.1"},"unit":"°C"},
              {"metricKey":"cpu","kind":"TABLE_AVERAGE","params":{"column":"1.3.6.1.4.1.9.9.109.1.1.1.1.8"},"unit":"%","enabled":false}]}}""", 200);

        JsonNode results = r.path("results");
        assertEquals(2, results.size());
        assertFalse(results.get(0).path("ok").asBoolean());
        assertTrue(results.get(0).path("error").asText().contains("OID 不存在"));
        assertTrue(results.get(1).path("ok").asBoolean());
        assertFalse(results.get(1).path("enabled").asBoolean());
        assertEquals(12.0, results.get(1).path("values").get(0).path("value").asDouble(), 1e-9);
    }

    @Test
    void 設備無回應回傳502() throws Exception {
        agent.silent(true);
        JsonNode r = preview("{\"deviceName\":\"Cisco-1\",\"profile\":{\"metrics\":[{\"metricKey\":\"cpu\",\"kind\":\"VALUE\",\"params\":{\"oid\":\"1.3.6.1.2.1.1.3.0\"}}]}}", 502);
        assertTrue(r.path("error").asText().contains("設備無法查詢"));
    }
}
