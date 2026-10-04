package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.profile.BuiltinProfiles;
import io.github.chiayi1224.simplesnmp.profile.MetricKind;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class ProfileServletTest {

    @Mock MetricStore         store;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    ProfileServlet servlet;
    StringWriter   body;

    private static final String VALID = """
        {"name":"Switch-X","matchPrefix":"1.3.6.1.4.1.99999.1","metrics":[
          {"metricKey":"cpu","kind":"VALUE","params":{"oid":"1.3.6.1.4.1.99999.2.1.0"},"unit":"%"},
          {"metricKey":"temperature","kind":"value","params":{"oid":"1.3.6.1.4.1.99999.3.1.0","scale":0.1},"unit":"°C"}]}""";

    @BeforeEach
    void setUp() throws Exception {
        servlet = new ProfileServlet(store);
        body    = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));
        when(store.queryAllDevices()).thenReturn(List.of());
        when(store.loadProfile(1)).thenReturn(BuiltinProfiles.netSnmp().withId(1L));
        when(store.loadProfile(5)).thenReturn(new MetricProfile(5L, "Mine", "", "", false,
            List.of(new ProfileMetric("cpu", MetricKind.VALUE, Map.of("oid", "1.1"), "%", true))));
    }

    private void request(String path, String json) throws Exception {
        when(req.getPathInfo()).thenReturn(path);
        if (json != null) when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    }

    // ─── 新增 ─────────────────────────────────────────────────────

    @Test
    void 新增範本回傳201_參數數字轉為字串() throws Exception {
        when(store.createProfile(any())).thenReturn(10L);
        request(null, VALID);

        servlet.doPost(req, resp);

        verify(resp).setStatus(201);
        ArgumentCaptor<MetricProfile> captor = ArgumentCaptor.forClass(MetricProfile.class);
        verify(store).createProfile(captor.capture());
        MetricProfile p = captor.getValue();
        assertEquals("Switch-X", p.name());
        assertFalse(p.builtin(), "API 建立的範本一律不是內建");
        assertEquals(MetricKind.VALUE, p.metrics().get(1).kind());
        assertEquals("0.1", p.metrics().get(1).params().get("scale"));
    }

    @Test
    void 指標名稱不合法回傳400() throws Exception {
        request(null, "{\"name\":\"X\",\"metrics\":[{\"metricKey\":\"CPU Temp\",\"kind\":\"VALUE\",\"params\":{\"oid\":\"1.1\"}}]}");

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).createProfile(any());
    }

    @Test
    void 不支援的計算類型回傳400並列出可用類型() throws Exception {
        request(null, "{\"name\":\"X\",\"metrics\":[{\"metricKey\":\"temp\",\"kind\":\"EXPRESSION\"}]}");

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        assertTrue(body.toString().contains("STORAGE_TABLE"));
    }

    @Test
    void 名稱重複回傳409() throws Exception {
        when(store.profileNameExists("Switch-X", -1)).thenReturn(true);
        request(null, VALID);

        servlet.doPost(req, resp);

        verify(resp).setStatus(409);
        verify(store, never()).createProfile(any());
    }

    // ─── 內建範本保護 ─────────────────────────────────────────────

    @Test
    void 修改內建範本回傳403() throws Exception {
        request("/1", VALID);

        servlet.doPut(req, resp);

        verify(resp).setStatus(403);
        verify(store, never()).updateProfile(anyLong(), any());
    }

    @Test
    void 刪除內建範本回傳403() throws Exception {
        request("/1", null);

        servlet.doDelete(req, resp);

        verify(resp).setStatus(403);
        verify(store, never()).deleteProfile(anyLong());
    }

    @Test
    void 修改與刪除自訂範本() throws Exception {
        request("/5", VALID);
        servlet.doPut(req, resp);
        verify(store).updateProfile(eq(5L), any());

        servlet.doDelete(req, resp);
        verify(store).deleteProfile(5L);
    }

    @Test
    void 範本不存在回傳404() throws Exception {
        request("/99", VALID);

        servlet.doPut(req, resp);

        verify(resp).setStatus(404);
    }

    // ─── 複製 ─────────────────────────────────────────────────────

    @Test
    void 複製內建範本_產生非內建的複本並避開重複名稱() throws Exception {
        when(store.profileNameExists(BuiltinProfiles.NET_SNMP + "（複本）", -1)).thenReturn(true);
        when(store.createProfile(any())).thenReturn(11L);
        request("/1/copy", null);

        servlet.doPost(req, resp);

        verify(resp).setStatus(201);
        ArgumentCaptor<MetricProfile> captor = ArgumentCaptor.forClass(MetricProfile.class);
        verify(store).createProfile(captor.capture());
        assertEquals(BuiltinProfiles.NET_SNMP + "（複本 2）", captor.getValue().name());
        assertFalse(captor.getValue().builtin());
        assertEquals(3, captor.getValue().metrics().size());
    }

    // ─── 列表 ─────────────────────────────────────────────────────

    @Test
    void 列表包含套用設備數() throws Exception {
        when(store.loadProfiles()).thenReturn(List.of(BuiltinProfiles.netSnmp().withId(1L)));
        Map<String, Object> d1 = new HashMap<>(Map.of("appliedProfileId", 1L));
        Map<String, Object> d2 = new HashMap<>(Map.of("appliedProfileId", 1L));
        when(store.queryAllDevices()).thenReturn(List.of(d1, d2));
        request(null, null);

        servlet.doGet(req, resp);

        String json = body.toString();
        assertTrue(json.contains("\"deviceCount\" : 2"), json);
        assertTrue(json.contains("\"kind\" : \"HUNDRED_MINUS\""), json);
    }
}
