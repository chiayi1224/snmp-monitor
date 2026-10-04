package io.github.chiayi1224.simplesnmp.web.servlet;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettingsServletTest {

    @Mock MetricStore         store;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    SettingsServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new SettingsServlet(store);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(store.queryAllSettings()).thenReturn(Map.of(
            "retention_metrics_days", Map.of(), "ping_enabled", Map.of(), "trap_communities", Map.of()));
    }

    private void put(String key, String value) throws Exception {
        when(req.getPathInfo()).thenReturn("/" + key);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"value\":\"" + value + "\"}")));
        servlet.doPut(req, resp);
    }

    @Test
    void 保留天數可設定為任意合法天數() throws Exception {
        put("retention_metrics_days", "30");

        verify(store).upsertSetting("retention_metrics_days", "30", "");
        verify(resp, never()).setStatus(anyInt());
    }

    @Test
    void 保留天數可設為0代表永久保留() throws Exception {
        put("retention_metrics_days", "0");

        verify(store).upsertSetting("retention_metrics_days", "0", "");
    }

    @Test
    void 保留天數為負數或非整數回傳400() throws Exception {
        put("retention_metrics_days", "-1");
        put("retention_metrics_days", "30天");

        verify(resp, times(2)).setStatus(400);
        verify(store, never()).upsertSetting(any(), any(), any());
    }

    @Test
    void 布林設定只接受true或false() throws Exception {
        put("ping_enabled", "yes");

        verify(resp).setStatus(400);
        verify(store, never()).upsertSetting(any(), any(), any());
    }

    @Test
    void 不存在的key回傳404且不新增() throws Exception {
        put("retention_metrcs_days", "30");   // 拼錯的 key

        verify(resp).setStatus(404);
        verify(store, never()).upsertSetting(any(), any(), any());
    }

    @Test
    void 沒有格式限制的設定照常更新() throws Exception {
        put("trap_communities", "public,monitor");

        verify(store).upsertSetting("trap_communities", "public,monitor", "");
    }

    @Test
    void validate_範圍邊界() {
        assertNull(SettingsServlet.validate("retention_audit_days", "36500"));
        assertNotNull(SettingsServlet.validate("retention_audit_days", "36501"));
        assertNotNull(SettingsServlet.validate("web_port", "0"));
        assertNull(SettingsServlet.validate("poll_interval_seconds", "10"));
        assertNotNull(SettingsServlet.validate("poll_interval_seconds", "9"));
    }
}
