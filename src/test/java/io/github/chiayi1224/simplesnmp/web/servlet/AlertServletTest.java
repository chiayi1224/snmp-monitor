package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServletTest {

    @Mock MetricStore         store;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    AlertServlet servlet;
    StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        servlet      = new AlertServlet(store);
        responseBody = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    // ─── GET /api/alerts（無過濾，固定 limit=50） ─────────────────

    @Test
    void GET_無過濾回傳預設50筆() throws Exception {
        when(store.queryAlerts(null, 50)).thenReturn(List.of());

        servlet.doGet(req, resp);

        verify(store).queryAlerts(null, 50);
    }

    @Test
    void GET_回傳JSON格式() throws Exception {
        when(store.queryAlerts(null, 50)).thenReturn(List.of(
            Map.of("deviceName", "Router", "metricName", "cpu",
                   "eventType", "TRIGGERED", "value", 91.0)
        ));

        servlet.doGet(req, resp);

        String body = responseBody.toString();
        assertTrue(body.contains("Router"));
        assertTrue(body.contains("TRIGGERED"));
    }

    // ─── POST /api/alerts（以 JSON body 傳遞過濾條件） ────────────

    @Test
    void POST_空body使用預設值() throws Exception {
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{}")));
        when(store.queryAlerts(null, 50)).thenReturn(List.of());

        servlet.doPost(req, resp);

        verify(store).queryAlerts(null, 50);
    }

    @Test
    void POST_帶device過濾設備() throws Exception {
        String json = "{\"device\":\"Server-01\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.queryAlerts("Server-01", 50)).thenReturn(List.of(
            Map.of("deviceName", "Server-01", "metricName", "cpu", "eventType", "TRIGGERED")
        ));

        servlet.doPost(req, resp);

        verify(store).queryAlerts("Server-01", 50);
        String body = responseBody.toString();
        assertTrue(body.contains("Server-01"));
    }

    @Test
    void POST_帶limit限制筆數() throws Exception {
        String json = "{\"limit\":10}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.queryAlerts(null, 10)).thenReturn(List.of());

        servlet.doPost(req, resp);

        verify(store).queryAlerts(null, 10);
    }

    @Test
    void POST_limit非數字靜默忽略使用預設50() throws Exception {
        String json = "{\"limit\":\"abc\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.queryAlerts(null, 50)).thenReturn(List.of());

        servlet.doPost(req, resp);

        verify(store).queryAlerts(null, 50);
    }

    @Test
    void POST_帶device和limit() throws Exception {
        String json = "{\"device\":\"Router-01\",\"limit\":20}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.queryAlerts("Router-01", 20)).thenReturn(List.of());

        servlet.doPost(req, resp);

        verify(store).queryAlerts("Router-01", 20);
    }
}
