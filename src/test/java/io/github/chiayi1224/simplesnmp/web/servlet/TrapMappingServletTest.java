package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrapMappingServletTest {

    @Mock MetricStore         store;
    @Mock TrapReceiver        trapReceiver;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    TrapMappingServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new TrapMappingServlet(store, trapReceiver);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    private void body(String json) throws Exception {
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    }

    @Test
    void 新增對應回傳201並重新載入() throws Exception {
        body("{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\"}");

        servlet.doPost(req, resp);

        verify(store).addTrapMapping("fan", "1.3.6.1.4.1.9999.0.7", null, "", Severity.CRITICAL);
        verify(trapReceiver).reloadMappings(any());
        verify(resp).setStatus(201);
        verify(store, never()).deleteUnknownTrap(anyLong());
    }

    @Test
    void 從未定義Trap建立_成功後刪除該筆記錄() throws Exception {
        body("{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\",\"fromUnknownId\":42}");

        servlet.doPost(req, resp);

        verify(store).addTrapMapping(any(), any(), any(), any(), any(Severity.class));
        verify(store).deleteUnknownTrap(42);
    }

    @Test
    void 觸發OID已存在回傳409且保留未定義記錄() throws Exception {
        when(store.trapTriggeredOidExists("1.3.6.1.4.1.9999.0.7", -1)).thenReturn(true);
        body("{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\",\"fromUnknownId\":42}");

        servlet.doPost(req, resp);

        verify(resp).setStatus(409);
        verify(store, never()).addTrapMapping(any(), any(), any(), any(), any(Severity.class));
        verify(store, never()).deleteUnknownTrap(anyLong());
    }

    @Test
    void 更新為其他對應已使用的OID回傳409() throws Exception {
        when(req.getPathInfo()).thenReturn("/5");
        when(store.trapTriggeredOidExists("1.3.6.1.4.1.9999.0.7", 5)).thenReturn(true);
        body("{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\"}");

        servlet.doPut(req, resp);

        verify(resp).setStatus(409);
        verify(store, never()).updateTrapMapping(anyLong(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void 更新自己的OID不算重複() throws Exception {
        when(req.getPathInfo()).thenReturn("/5");
        body("{\"metricName\":\"fan2\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\"}");

        servlet.doPut(req, resp);

        verify(store).updateTrapMapping(eq(5L), eq("fan2"), any(), any(), any(), eq(Severity.CRITICAL), eq(true));
    }

    @Test
    void 新增時指定WARNING等級() throws Exception {
        body("{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\",\"severity\":\"warning\"}");

        servlet.doPost(req, resp);

        verify(store).addTrapMapping("fan", "1.3.6.1.4.1.9999.0.7", null, "", Severity.WARNING);
        verify(resp).setStatus(201);
    }

    @Test
    void 不合法的等級回傳400() throws Exception {
        body("{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\",\"severity\":\"INFO\"}");

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).addTrapMapping(any(), any(), any(), any(), any(Severity.class));
    }

    @Test
    void 更新時不合法的等級回傳400() throws Exception {
        when(req.getPathInfo()).thenReturn("/5");
        body("{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9999.0.7\",\"severity\":\"MAJOR\"}");

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).updateTrapMapping(anyLong(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
