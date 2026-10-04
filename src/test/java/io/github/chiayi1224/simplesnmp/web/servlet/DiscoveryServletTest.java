package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.discovery.DiscoveryService;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscoveryServletTest {

    @Mock MetricStore         store;
    @Mock DiscoveryService    discovery;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    DiscoveryServlet servlet;
    StringWriter     body;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new DiscoveryServlet(store, discovery);
        body    = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));
    }

    private void post(String json) throws Exception {
        when(req.getPathInfo()).thenReturn("/scans");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        servlet.doPost(req, resp);
    }

    // ─── 啟動掃描 ─────────────────────────────────────────────────

    @Test
    void 啟動掃描回傳202與編號() throws Exception {
        when(discovery.start(eq("SW"), eq(true), eq(List.of("1.3.6.1.2.1")))).thenReturn(new DiscoveryService.StartResult(7, false));

        post("{\"deviceName\":\"SW\",\"walk\":true,\"subtrees\":[\"1.3.6.1.2.1\"]}");

        verify(resp).setStatus(202);
        assertTrue(body.toString().contains("\"scanId\" : 7"));
    }

    @Test
    void 設備已有掃描回傳409與進行中的編號() throws Exception {
        when(discovery.start(any(), anyBoolean(), any())).thenReturn(new DiscoveryService.StartResult(5, true));

        post("{\"deviceName\":\"SW\"}");

        verify(resp).setStatus(409);
        assertTrue(body.toString().contains("\"scanId\" : 5"));
    }

    @Test
    void 設備不存在回傳404() throws Exception {
        when(discovery.start(any(), anyBoolean(), any())).thenThrow(new DiscoveryService.DeviceNotFoundException("Nope"));

        post("{\"deviceName\":\"Nope\"}");

        verify(resp).setStatus(404);
    }

    @Test
    void 子樹格式不正確回傳400且不啟動() throws Exception {
        post("{\"deviceName\":\"SW\",\"walk\":true,\"subtrees\":[\"1.3.6.1' OR 1=1\"]}");

        verify(resp).setStatus(400);
        verify(discovery, never()).start(any(), anyBoolean(), any());
    }

    @Test
    void 缺少設備名稱回傳400() throws Exception {
        post("{}");

        verify(resp).setStatus(400);
    }

    // ─── 查詢 ─────────────────────────────────────────────────────

    @Test
    void 單次掃描_探測結果以JSON物件回傳() throws Exception {
        Map<String, Object> scan = new HashMap<>(Map.of("id", 3L, "status", "COMPLETED",
            "probesJson", "[{\"key\":\"if-mib\",\"supported\":true}]"));
        when(store.queryScan(3)).thenReturn(scan);
        when(req.getPathInfo()).thenReturn("/scans/3");

        servlet.doGet(req, resp);

        String json = body.toString();
        assertTrue(json.contains("\"probes\" : [ {"), json);
        assertFalse(json.contains("probesJson"));
    }

    @Test
    void 掃描不存在回傳404() throws Exception {
        when(store.queryScan(99)).thenReturn(null);
        when(req.getPathInfo()).thenReturn("/scans/99");

        servlet.doGet(req, resp);

        verify(resp).setStatus(404);
    }

    @Test
    void OID明細_limit上限500且前綴去除頭尾的點() throws Exception {
        when(store.queryScan(3)).thenReturn(Map.of("id", 3L));
        when(store.queryScanOids(anyLong(), any(), any(), anyInt(), anyInt())).thenReturn(new HashMap<>());
        when(req.getPathInfo()).thenReturn("/scans/3/oids");
        when(req.getParameter("prefix")).thenReturn(".1.3.6.1.2.1.2.");
        when(req.getParameter("limit")).thenReturn("9999");

        servlet.doGet(req, resp);

        verify(store).queryScanOids(3, "1.3.6.1.2.1.2", null, 0, 500);
    }

    @Test
    void OID明細_前綴格式不正確回傳400() throws Exception {
        when(store.queryScan(3)).thenReturn(Map.of("id", 3L));
        when(req.getPathInfo()).thenReturn("/scans/3/oids");
        when(req.getParameter("prefix")).thenReturn("1.3.%");

        servlet.doGet(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).queryScanOids(anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void 未知路徑回傳404() throws Exception {
        when(req.getPathInfo()).thenReturn("/scans/3/other");

        servlet.doGet(req, resp);

        verify(resp).setStatus(404);
    }
}
