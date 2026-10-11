package io.github.chiayi1224.simplesnmp.web.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.i18n.Messages;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
import io.github.chiayi1224.simplesnmp.i18n.StoredText;
import io.github.chiayi1224.simplesnmp.i18n.TestLocale;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 各 API 依請求語系輸出訊息（以英文為主，另驗證簡體中文與預設正體中文），使用 in-memory DB。
 */
class LocalizedServletTest {

    private MetricStore store;
    private HttpServletRequest  req;
    private HttpServletResponse resp;
    private StringWriter out;

    @BeforeEach
    void setUp() throws Exception {
        store = new MetricStore(":memory:");
        store.addDevice(new DeviceConfig("SW-01", "10.0.0.1", "public"));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    /** 以指定的 method 呼叫 servlet，回傳 JSON 回應 */
    private JsonNode call(HttpServlet servlet, String method, String path, String body) throws Exception {
        req  = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        out  = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(out));
        when(req.getMethod()).thenReturn(method);
        when(req.getPathInfo()).thenReturn(path);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body == null ? "" : body)));
        servlet.service(req, resp);
        return new ObjectMapper().readTree(out.toString());
    }

    private String english(HttpServlet servlet, String method, String path, String body) throws Exception {
        String[] error = new String[1];
        TestLocale.english(() -> {
            JsonNode r = call(servlet, method, path, body);
            error[0] = r.has("error") ? r.get("error").asText() : r.path("message").asText();
        });
        return error[0];
    }

    // ─── 錯誤訊息 ─────────────────────────────────────────────────

    @Test
    void 必填欄位與JSON格式錯誤() throws Exception {
        DeviceServlet s = new DeviceServlet(store, mock(TrapReceiver.class));
        assertEquals("Field name is required", english(s, "POST", null, "{\"host\":\"1.2.3.4\"}"));
        assertTrue(english(s, "POST", null, "{bad").startsWith("Invalid JSON"));
    }

    @Test
    void 預設正體中文_與簡體中文() throws Exception {
        DeviceServlet s = new DeviceServlet(store, mock(TrapReceiver.class));
        assertEquals("欄位 name 為必填", call(s, "POST", null, "{\"host\":\"1.2.3.4\"}").get("error").asText());
        TestLocale.run(Messages.ZH_CN, () ->
            assertEquals("字段 name 为必填", call(s, "POST", null, "{\"host\":\"1.2.3.4\"}").get("error").asText()));
    }

    @Test
    void 設備() throws Exception {
        DeviceServlet s = new DeviceServlet(store, mock(TrapReceiver.class));
        assertEquals("Device not found: NoSuch", english(s, "GET", "/NoSuch", null));
        assertEquals("snmpVersion must be 1, 2c or 3", english(s, "POST", null, "{\"name\":\"X\",\"host\":\"1.1.1.1\",\"snmpVersion\":\"4\"}"));
        assertEquals("Created", english(s, "POST", null, "{\"name\":\"X\",\"host\":\"1.1.1.1\"}"));
    }

    @Test
    void 閾值() throws Exception {
        DeviceThresholdServlet s = new DeviceThresholdServlet(store);
        assertEquals("With direction HIGH, Warning must be lower than Critical",
            english(s, "PUT", null, "{\"device\":\"SW-01\",\"metricName\":\"cpu\",\"warning\":95,\"critical\":90}"));
        assertEquals("Saved; takes effect on the next poll",
            english(s, "PUT", null, "{\"device\":\"SW-01\",\"metricName\":\"cpu\",\"critical\":90}"));
    }

    @Test
    void 掃描() throws Exception {
        DiscoveryServlet s = new DiscoveryServlet(store, null);
        assertEquals("Invalid subtree OID: x", english(s, "POST", "/scans", "{\"deviceName\":\"SW-01\",\"subtrees\":[\"x\"]}"));
        assertEquals("Scan #99 not found", english(s, "GET", "/scans/99", null));
    }

    @Test
    void 範本_內建範本唯讀與顯示名稱() throws Exception {
        ProfileServlet s = new ProfileServlet(store);
        long builtinId = store.loadProfiles().stream().filter(p -> p.builtin()).findFirst().orElseThrow().id();
        assertEquals("Built-in profiles cannot be deleted", english(s, "DELETE", "/" + builtinId, null));

        TestLocale.english(() -> {
            JsonNode list = call(s, "GET", null, null);
            JsonNode hr = null;
            for (JsonNode p : list) if ("HOST-RESOURCES 通用".equals(p.get("name").asText())) hr = p;
            assertNotNull(hr);
            assertEquals("HOST-RESOURCES generic", hr.get("displayName").asText());
            assertFalse(hr.get("displayDescription").asText().matches(".*[\\u4e00-\\u9fff].*"));
        });
    }

    @Test
    void 系統設定_錯誤與說明() throws Exception {
        SettingsServlet s = new SettingsServlet(store);
        assertEquals("retention_metrics_days must be between 0 and 36500",
            english(s, "PUT", "/retention_metrics_days", "{\"value\":\"99999\"}"));
        TestLocale.english(() -> {
            JsonNode all = call(s, "GET", null, null);
            assertEquals("Web management port (restart required after change)", all.get("web_port").get("description").asText());
            assertFalse(all.has("thresholds_migrated"), "內部旗標不顯示");
        });
    }

    @Test
    void Trap對應與未定義Trap() throws Exception {
        TrapMappingServlet s = new TrapMappingServlet(store, mock(TrapReceiver.class));
        assertEquals("severity must be WARNING or CRITICAL",
            english(s, "POST", null, "{\"metricName\":\"fan\",\"triggeredOid\":\"1.3.6.1.4.1.9.9\",\"severity\":\"INFO\"}"));
        assertEquals("Record #9 not found", english(new UnknownTrapServlet(store), "DELETE", "/oids/9", null));
    }

    @Test
    void 外部API() throws Exception {
        assertEquals("severity must be WARNING or CRITICAL", english(new V1AlertServlet(store), "POST", null, "{\"severity\":\"X\"}"));
        assertEquals("Device not found: NoSuch", english(new V1DeviceServlet(store), "POST", "/NoSuch", "{}"));
    }

    @Test
    void SNMPv3使用者() throws Exception {
        assertEquals("Field username is required", english(new V3UserServlet(store, mock(TrapReceiver.class)), "POST", null, "{\"username\":\"\"}"));
    }

    @Test
    void 登入() throws Exception {
        AuthServlet s = new AuthServlet(store);
        String[] msg = new String[1];
        TestLocale.english(() -> {
            JsonNode r = call(s, "POST", "/login", "{\"username\":\"admin\",\"password\":\"wrong\"}");
            msg[0] = r.get("error").asText();
        });
        assertEquals("Incorrect username or password", msg[0]);
    }

    @Test
    void 帳號() throws Exception {
        UserServlet s = new UserServlet(store);
        TestLocale.english(() -> {
            HttpSession session = mock(HttpSession.class);
            when(session.getAttribute("user")).thenReturn(Map.of("id", 1L, "username", "admin", "role", "admin"));
            req  = mock(HttpServletRequest.class);
            resp = mock(HttpServletResponse.class);
            out  = new StringWriter();
            when(resp.getWriter()).thenReturn(new PrintWriter(out));
            when(req.getMethod()).thenReturn("POST");
            when(req.getSession(false)).thenReturn(session);
            when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"username\":\"op\",\"password\":\"abc\"}")));
            s.service(req, resp);
            assertEquals("The password must be at least 6 characters", new ObjectMapper().readTree(out.toString()).get("error").asText());
        });
    }

    // ─── 系統產生的文字依讀取時的語系 ─────────────────────────────

    @Test
    void 同一筆告警以不同語言查詢_舊文字原樣() throws Exception {
        store.saveAlertEvent(AlertEvent.triggered("SW-01", "device_unreachable", Severity.CRITICAL, 1, 0.5,
            StoredText.encode(Msg.of("alert.unreachable.snmp"))));
        store.saveAlertEvent(AlertEvent.triggered("SW-01", "cpu", Severity.CRITICAL, 95, 90, "SNMP 無回應（舊資料）"));
        V1AlertServlet s = new V1AlertServlet(store);

        TestLocale.english(() -> {
            JsonNode r = call(s, "POST", null, "{}");
            assertEquals("SNMP 無回應（舊資料）", r.get(0).get("message").asText());
            assertEquals("No SNMP response", r.get(1).get("message").asText());
        });
        TestLocale.run(Messages.ZH_CN, () ->
            assertEquals("SNMP 无响应", call(s, "POST", null, "{}").get(1).get("message").asText()));
        assertEquals("SNMP 無回應", call(s, "POST", null, "{}").get(1).get("message").asText());
    }

    @Test
    void 稽核說明依語系() throws Exception {
        store.saveAuditLog("TOKEN_FAIL", null, "1.1.1.1", "GET", "/api/admin/x", 401,
            StoredText.encode(Msg.of("auth.token_invalid")));
        AuditLogServlet s = new AuditLogServlet(store);
        TestLocale.english(() -> assertEquals("Invalid API token", call(s, "GET", null, null).get(0).get("detail").asText()));
    }

    @Test
    void 掃描進度錯誤與探測結果依語系() throws Exception {
        long id = store.createScan("SW-01", false);
        store.saveScanProbes(id, new ObjectMapper().writeValueAsString(java.util.List.of(Map.of(
            "key", "hr-storage", "name", StoredText.encode(Msg.of("probe.hr-storage.name")), "mib", "HOST-RESOURCES-MIB",
            "supported", true, "samples", java.util.List.of(StoredText.encode(Msg.of("probe.sample.storage", "/",
                Msg.of("probe.storage_type.4"), "10.0 GB", "25.0%")))))));
        store.finishScan(id, "FAILED", StoredText.encode(Msg.of("scan.walk_failed", "1.3.6.1.2.1", Msg.of("snmp.no_response"))), false);
        DiscoveryServlet s = new DiscoveryServlet(store, null);

        TestLocale.english(() -> {
            JsonNode scan = call(s, "GET", "/scans/" + id, null);
            assertEquals("Walking 1.3.6.1.2.1 failed: No response from device", scan.get("error").asText());
            JsonNode probe = scan.get("probes").get(0);
            assertEquals("Storage", probe.get("name").asText());
            assertEquals("/ (fixed disk): 10.0 GB, 25.0% used", probe.get("samples").get(0).asText());
            assertEquals("Walking 1.3.6.1.2.1 failed: No response from device",
                call(s, "GET", "/scans", null).get(0).get("error").asText());
        });
    }
}
