package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThresholdServletTest {

    @Mock MetricStore         store;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    ThresholdServlet servlet;
    StringWriter     body;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new ThresholdServlet(store);
        body    = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));
        when(store.thresholdMetricName(1)).thenReturn("cpu");
        when(store.thresholdMetricName(7)).thenReturn("temperature");
    }

    private void request(String path, String json) throws Exception {
        when(req.getPathInfo()).thenReturn(path);
        if (json != null) when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    }

    // ─── POST ─────────────────────────────────────────────────────

    @Test
    void 新增自訂LOW閾值回傳201() throws Exception {
        when(store.createThreshold(any(), any(), anyDouble(), anyDouble(), anyInt(), anyInt(), any())).thenReturn(9L);
        request(null, """
            {"metricName":"free_ports","direction":"low","threshold":5,"rearm":8,"triggerCount":2,"rearmCount":2}""");

        servlet.doPost(req, resp);

        verify(resp).setStatus(201);
        verify(store).createThreshold("free_ports", ThresholdConfig.Direction.LOW, 5, 8, 2, 2, "");
    }

    @Test
    void 新增_值不限百分比範圍() throws Exception {
        request(null, """
            {"metricName":"if_errors","threshold":5000,"rearm":1000,"triggerCount":1,"rearmCount":1}""");
        servlet.doPost(req, resp);
        verify(resp).setStatus(201);
        verify(store).createThreshold(eq("if_errors"), eq(ThresholdConfig.Direction.HIGH), eq(5000.0), eq(1000.0), eq(1), eq(1), any());
    }

    @Test
    void 新增_LOW方向恢復值不大於觸發值回傳400() throws Exception {
        request(null, """
            {"metricName":"free_ports","direction":"LOW","threshold":5,"rearm":3}""");
        servlet.doPost(req, resp);
        verify(resp).setStatus(400);
        assertTrue(body.toString().contains("rearm 應大於 threshold"));
        verify(store, never()).createThreshold(any(), any(), anyDouble(), anyDouble(), anyInt(), anyInt(), any());
    }

    @Test
    void 新增_不合法方向或名稱回傳400() throws Exception {
        request(null, """
            {"metricName":"temp","direction":"SIDEWAYS","threshold":5,"rearm":3}""");
        servlet.doPost(req, resp);
        request(null, """
            {"metricName":"Temp-1","threshold":5,"rearm":3}""");
        servlet.doPost(req, resp);
        verify(resp, times(2)).setStatus(400);
    }

    @Test
    void 新增_內建名稱或已存在回傳409() throws Exception {
        when(store.thresholdExists("temperature")).thenReturn(true);
        request(null, """
            {"metricName":"cpu","threshold":90,"rearm":80}""");
        servlet.doPost(req, resp);
        request(null, """
            {"metricName":"temperature","threshold":90,"rearm":80}""");
        servlet.doPost(req, resp);
        verify(resp, times(2)).setStatus(409);
    }

    // ─── PUT ──────────────────────────────────────────────────────

    @Test
    void 更新內建_方向固定HIGH且限制百分比() throws Exception {
        request("/1", """
            {"direction":"LOW","threshold":95,"rearm":85,"triggerCount":3,"rearmCount":2}""");
        servlet.doPut(req, resp);
        verify(store).updateThreshold(1, ThresholdConfig.Direction.HIGH, 95, 85, 3, 2);

        request("/1", """
            {"threshold":150,"rearm":85}""");
        servlet.doPut(req, resp);
        verify(resp).setStatus(400);
    }

    @Test
    void 更新自訂_可改方向() throws Exception {
        request("/7", """
            {"direction":"LOW","threshold":10,"rearm":15,"triggerCount":1,"rearmCount":1}""");
        servlet.doPut(req, resp);
        verify(store).updateThreshold(7, ThresholdConfig.Direction.LOW, 10, 15, 1, 1);
    }

    @Test
    void 更新不存在回傳404() throws Exception {
        request("/99", "{}");
        servlet.doPut(req, resp);
        verify(resp).setStatus(404);
    }

    // ─── DELETE ───────────────────────────────────────────────────

    @Test
    void 刪除內建回傳403_自訂可刪除() throws Exception {
        request("/1", null);
        servlet.doDelete(req, resp);
        verify(resp).setStatus(403);
        verify(store, never()).deleteThreshold(1);

        request("/7", null);
        servlet.doDelete(req, resp);
        verify(store).deleteThreshold(7);
    }
}
