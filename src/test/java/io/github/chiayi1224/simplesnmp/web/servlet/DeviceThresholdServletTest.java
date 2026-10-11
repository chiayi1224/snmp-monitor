package io.github.chiayi1224.simplesnmp.web.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 每台設備閾值 API：使用 in-memory DB */
class DeviceThresholdServletTest {

    private MetricStore store;
    private DeviceThresholdServlet servlet;
    private HttpServletRequest  req;
    private HttpServletResponse resp;
    private StringWriter out;

    @BeforeEach
    void setUp() throws Exception {
        store = new MetricStore(":memory:");
        store.addDevice(new DeviceConfig("A", "10.0.0.1", "public"));
        store.addDevice(new DeviceConfig("B", "10.0.0.2", "public"));
        servlet = new DeviceThresholdServlet(store);
        reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private void reset() throws Exception {
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        out  = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(out));
    }

    private JsonNode json() throws Exception {
        return new ObjectMapper().readTree(out.toString());
    }

    private JsonNode put(String body) throws Exception {
        reset();
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        servlet.doPut(req, resp);
        return json();
    }

    private JsonNode post(String path, String body) throws Exception {
        reset();
        when(req.getPathInfo()).thenReturn(path);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        servlet.doPost(req, resp);
        return json();
    }

    private JsonNode get(String device) throws Exception {
        reset();
        when(req.getParameter("device")).thenReturn(device);
        servlet.doGet(req, resp);
        return json();
    }

    // ─── PUT ──────────────────────────────────────────────────────

    @Test
    void PUT_新增後以同名取代() throws Exception {
        JsonNode r1 = put("{\"device\":\"A\",\"metricName\":\"cpu\",\"warning\":80,\"critical\":95,\"rearmMargin\":5,\"triggerCount\":3,\"rearmCount\":2}");
        verify(resp, never()).setStatus(anyInt());
        long id = r1.get("id").asLong();

        JsonNode r2 = put("{\"device\":\"A\",\"metricName\":\"cpu\",\"warning\":null,\"critical\":90}");
        assertEquals(id, r2.get("id").asLong());
        ThresholdConfig c = store.loadThreshold("A", "cpu");
        assertNull(c.getWarning());
        assertEquals(90.0, c.getCritical());
    }

    @Test
    void PUT_掛載點與LOW自訂指標() throws Exception {
        put("{\"device\":\"A\",\"metricName\":\"disk:/data\",\"critical\":95}");
        put("{\"device\":\"A\",\"metricName\":\"free_ports\",\"direction\":\"LOW\",\"warning\":10,\"critical\":3}");
        assertEquals(95.0, store.loadThreshold("A", "disk:/data").getCritical());
        assertEquals(ThresholdConfig.Direction.LOW, store.loadThreshold("A", "free_ports").getDirection());
    }

    private void assert400(String body) throws Exception {
        put(body);
        verify(resp).setStatus(400);
    }

    @Test
    void PUT_兩個觸發值都沒有回400() throws Exception {
        assert400("{\"device\":\"A\",\"metricName\":\"cpu\"}");
        assertNull(store.loadThreshold("A", "cpu"));
    }

    @Test
    void PUT_Warning高於Critical回400() throws Exception {
        assert400("{\"device\":\"A\",\"metricName\":\"cpu\",\"warning\":95,\"critical\":90}");
    }

    @Test
    void PUT_內建指標改LOW回400() throws Exception {
        assert400("{\"device\":\"A\",\"metricName\":\"cpu\",\"direction\":\"LOW\",\"warning\":20,\"critical\":10}");
    }

    @Test
    void PUT_百分比超過100回400() throws Exception {
        assert400("{\"device\":\"A\",\"metricName\":\"disk\",\"critical\":120}");
    }

    @Test
    void PUT_保留名稱回400() throws Exception {
        assert400("{\"device\":\"A\",\"metricName\":\"device_unreachable\",\"critical\":1}");
    }

    @Test
    void PUT_名稱不合法或觸發值不是數字回400() throws Exception {
        assert400("{\"device\":\"A\",\"metricName\":\"CPU Temp\",\"critical\":1}");
        assert400("{\"device\":\"A\",\"metricName\":\"disk:\",\"critical\":80}");
        assert400("{\"device\":\"A\",\"metricName\":\"cpu\",\"critical\":\"high\"}");
    }

    @Test
    void PUT_設備不存在回404() throws Exception {
        put("{\"device\":\"Nope\",\"metricName\":\"cpu\",\"critical\":90}");
        verify(resp).setStatus(404);
    }

    // ─── GET / DELETE ─────────────────────────────────────────────

    @Test
    void GET_設備閾值與可選指標_無設備參數時回傳筆數() throws Exception {
        put("{\"device\":\"A\",\"metricName\":\"cpu\",\"critical\":90}");

        JsonNode r = get("A");
        assertEquals(1, r.get("thresholds").size());
        assertEquals("cpu", r.get("thresholds").get(0).get("metricName").asText());
        assertTrue(r.get("availableMetrics").toString().contains("\"disk\""));

        assertEquals(1, get(null).get("counts").get("A").asInt());

        get("Nope");
        verify(resp).setStatus(404);
    }

    @Test
    void DELETE_刪除與404() throws Exception {
        long id = put("{\"device\":\"A\",\"metricName\":\"cpu\",\"critical\":90}").get("id").asLong();

        reset();
        when(req.getPathInfo()).thenReturn("/" + id);
        servlet.doDelete(req, resp);
        assertNull(store.loadThreshold("A", "cpu"));

        reset();
        when(req.getPathInfo()).thenReturn("/" + id);
        servlet.doDelete(req, resp);
        verify(resp).setStatus(404);
    }

    // ─── copy ─────────────────────────────────────────────────────

    @Test
    void copy_成功() throws Exception {
        put("{\"device\":\"A\",\"metricName\":\"cpu\",\"critical\":90}");
        JsonNode r = post("/copy", "{\"from\":\"A\",\"to\":[\"B\"]}");
        verify(resp, never()).setStatus(anyInt());
        assertEquals("B", r.get("to").get(0).asText());
        assertEquals(90.0, store.loadThreshold("B", "cpu").getCritical());
    }

    @Test
    void copy_目標不存在回400且所有目標不變() throws Exception {
        put("{\"device\":\"A\",\"metricName\":\"cpu\",\"critical\":90}");
        post("/copy", "{\"from\":\"A\",\"to\":[\"B\",\"Nope\"]}");
        verify(resp).setStatus(400);
        assertNull(store.loadThreshold("B", "cpu"));
    }

    @Test
    void copy_沒有目標回400_來源不存在回404() throws Exception {
        post("/copy", "{\"from\":\"A\",\"to\":[]}");
        verify(resp).setStatus(400);
        post("/copy", "{\"from\":\"Nope\",\"to\":[\"B\"]}");
        verify(resp).setStatus(404);
    }
}
