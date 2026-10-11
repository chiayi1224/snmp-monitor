package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V1AlertServletTest {

    @Mock MetricStore         store;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    V1AlertServlet servlet;
    StringWriter   responseBody;

    @BeforeEach
    void setUp() throws Exception {
        servlet      = new V1AlertServlet(store);
        responseBody = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    private void body(String json) throws Exception {
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    }

    @Test
    void 空body使用預設值() throws Exception {
        body("");
        servlet.doPost(req, resp);
        verify(store).queryAlerts(null, null, false, 50);
    }

    @Test
    void 帶device與limit_回傳severity欄位() throws Exception {
        body("{\"device\":\"Router-01\",\"limit\":20}");
        when(store.queryAlerts("Router-01", null, false, 20)).thenReturn(List.of(
            Map.of("deviceName", "Router-01", "eventType", "TRIGGERED", "severity", "WARNING")));

        servlet.doPost(req, resp);

        assertTrue(responseBody.toString().contains("\"severity\" : \"WARNING\""));
    }

    @Test
    void 依等級篩選() throws Exception {
        body("{\"severity\":\"critical\"}");
        servlet.doPost(req, resp);
        verify(store).queryAlerts(null, Severity.CRITICAL, false, 50);
    }

    @Test
    void 只看未解除() throws Exception {
        body("{\"active\":true}");
        servlet.doPost(req, resp);
        verify(store).queryAlerts(null, null, true, 50);
    }

    @Test
    void 不合法的等級回400() throws Exception {
        body("{\"severity\":\"MAJOR\"}");
        servlet.doPost(req, resp);
        verify(resp).setStatus(400);
        verify(store, never()).queryAlerts(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void limit非數字使用預設_超過上限截為1000() throws Exception {
        body("{\"limit\":\"abc\"}");
        servlet.doPost(req, resp);
        verify(store).queryAlerts(null, null, false, 50);

        body("{\"limit\":99999}");
        servlet.doPost(req, resp);
        verify(store).queryAlerts(null, null, false, 1000);
    }
}
