package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import org.mockito.ArgumentCaptor;

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
class DeviceServletTest {

    @Mock MetricStore         store;
    @Mock TrapReceiver        trapReceiver;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    DeviceServlet servlet;
    StringWriter  responseBody;

    @BeforeEach
    void setUp() throws Exception {
        servlet      = new DeviceServlet(store, trapReceiver);
        responseBody = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    // ─── GET /api/devices ─────────────────────────────────────────

    @Test
    void GET_回傳設備清單() throws Exception {
        when(req.getPathInfo()).thenReturn(null);
        when(store.queryAllDevices()).thenReturn(List.of(
            Map.of("id", 1L, "name", "Router-01", "host", "10.0.0.1")
        ));

        servlet.doGet(req, resp);

        String body = responseBody.toString();
        assertTrue(body.contains("Router-01"));
        assertTrue(body.contains("10.0.0.1"));
    }

    @Test
    void GET_DB例外回傳500() throws Exception {
        when(req.getPathInfo()).thenReturn(null);
        when(store.queryAllDevices()).thenThrow(new RuntimeException("DB error"));

        servlet.doGet(req, resp);

        verify(resp).setStatus(500);
    }

    @Test
    void GET_單台設備_找到回傳200() throws Exception {
        when(req.getPathInfo()).thenReturn("/Router-01");
        when(store.queryDeviceByName("Router-01")).thenReturn(
            Map.of("id", 1L, "name", "Router-01", "host", "10.0.0.1")
        );

        servlet.doGet(req, resp);

        String body = responseBody.toString();
        assertTrue(body.contains("Router-01"));
        assertTrue(body.contains("10.0.0.1"));
    }

    @Test
    void GET_單台設備_找不到回傳404() throws Exception {
        when(req.getPathInfo()).thenReturn("/NotExists");
        when(store.queryDeviceByName("NotExists")).thenReturn(null);

        servlet.doGet(req, resp);

        verify(resp).setStatus(404);
    }

    // ─── POST /api/devices（新增） ────────────────────────────────

    @Test
    void POST_新增設備成功後通知TrapReceiver() throws Exception {
        String json = "{\"name\":\"Server-01\",\"host\":\"192.168.1.10\",\"port\":161,\"community\":\"public\"}";
        when(req.getPathInfo()).thenReturn(null);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.loadEnabledDevices()).thenReturn(List.of());

        servlet.doPost(req, resp);

        DeviceConfig d = captureAdded();
        assertEquals("Server-01",     d.getName());
        assertEquals("192.168.1.10",  d.getHost());
        assertEquals(161,             d.getPort());
        assertEquals("public",        d.getCommunity());
        assertEquals(DeviceConfig.SnmpVersion.V2C, d.getSnmpVersion());
        verify(trapReceiver).reloadDevices(any());
        verify(resp).setStatus(201);
        verify(store).deleteUnknownSourceByIp("192.168.1.10");
    }

    @Test
    void POST_name為空回傳400不寫DB() throws Exception {
        String json = "{\"name\":\"\",\"host\":\"192.168.1.10\"}";
        when(req.getPathInfo()).thenReturn(null);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).addDevice(any(DeviceConfig.class));
        verify(trapReceiver, never()).reloadDevices(any());
    }

    @Test
    void POST_host為空回傳400() throws Exception {
        String json = "{\"name\":\"Router\",\"host\":\"\"}";
        when(req.getPathInfo()).thenReturn(null);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
    }

    @Test
    void POST_無port和community使用預設值() throws Exception {
        String json = "{\"name\":\"Dev\",\"host\":\"10.0.0.1\"}";
        when(req.getPathInfo()).thenReturn(null);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.loadEnabledDevices()).thenReturn(List.of());

        servlet.doPost(req, resp);

        DeviceConfig d = captureAdded();
        assertEquals(161,      d.getPort());
        assertEquals("public", d.getCommunity());
        assertEquals(2,        d.getRetries());
        assertEquals(3000L,    d.getTimeoutMs());
    }

    // ─── POST /api/devices/{name}（更新） ────────────────────────

    @Test
    void POST_更新設備成功後通知TrapReceiver() throws Exception {
        String json = "{\"host\":\"10.0.0.99\",\"enabled\":true}";
        when(req.getPathInfo()).thenReturn("/Router-01");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.loadEnabledDevices()).thenReturn(List.of());

        servlet.doPost(req, resp);

        ArgumentCaptor<DeviceConfig> captor = ArgumentCaptor.forClass(DeviceConfig.class);
        verify(store).updateDeviceByName(captor.capture(), eq(true));
        assertEquals("Router-01", captor.getValue().getName());
        assertEquals("10.0.0.99", captor.getValue().getHost());
        verify(trapReceiver).reloadDevices(any());
        String body = responseBody.toString();
        assertTrue(body.contains("更新成功"));
    }

    @Test
    void POST_更新設備未帶或留空community_沿用舊值() throws Exception {
        DeviceConfig existing = new DeviceConfig.Builder("Router-01", "10.0.0.1").community("secret").build();
        when(store.loadDevice("Router-01")).thenReturn(existing);
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post("/Router-01", "{\"host\":\"10.0.0.99\"}");
        post("/Router-01", "{\"host\":\"10.0.0.99\",\"community\":\"  \"}");

        ArgumentCaptor<DeviceConfig> captor = ArgumentCaptor.forClass(DeviceConfig.class);
        verify(store, times(2)).updateDeviceByName(captor.capture(), eq(true));
        captor.getAllValues().forEach(d -> assertNull(d.getCommunity()));
    }

    @Test
    void POST_更新設備帶community_寫入新值() throws Exception {
        when(store.loadDevice("Router-01")).thenReturn(new DeviceConfig.Builder("Router-01", "10.0.0.1").build());
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post("/Router-01", "{\"host\":\"10.0.0.99\",\"community\":\"newOne\"}");

        ArgumentCaptor<DeviceConfig> captor = ArgumentCaptor.forClass(DeviceConfig.class);
        verify(store).updateDeviceByName(captor.capture(), eq(true));
        assertEquals("newOne", captor.getValue().getCommunity());
    }

    // ─── 連線中斷告警設定 ─────────────────────────────────────────

    @Test
    void POST_新增設備帶連線中斷告警設定() throws Exception {
        when(store.loadEnabledDevices()).thenReturn(List.of());
        post(null, "{\"name\":\"R\",\"host\":\"10.0.0.1\",\"unreachableSeverity\":\"warning\",\"unreachableCount\":3}");

        DeviceConfig d = captureAdded();
        assertEquals("WARNING", d.getUnreachableSeverity());
        assertEquals(3, d.getUnreachableCount());
    }

    @Test
    void POST_新增設備預設不告警() throws Exception {
        when(store.loadEnabledDevices()).thenReturn(List.of());
        post(null, "{\"name\":\"R\",\"host\":\"10.0.0.1\"}");

        DeviceConfig d = captureAdded();
        assertEquals("", d.getUnreachableSeverity());
        assertEquals(1, d.getUnreachableCount());
    }

    @Test
    void POST_連線中斷告警等級或次數不合法回400() throws Exception {
        post(null, "{\"name\":\"R\",\"host\":\"10.0.0.1\",\"unreachableSeverity\":\"INFO\"}");
        post(null, "{\"name\":\"R\",\"host\":\"10.0.0.1\",\"unreachableSeverity\":\"CRITICAL\",\"unreachableCount\":0}");

        verify(resp, times(2)).setStatus(400);
        verify(store, never()).addDevice(any(DeviceConfig.class));
    }

    @Test
    void POST_更新設備未帶連線中斷告警設定_沿用舊值() throws Exception {
        when(store.loadDevice("Router-01")).thenReturn(new DeviceConfig.Builder("Router-01", "10.0.0.1")
            .unreachableSeverity("CRITICAL").unreachableCount(4).build());
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post("/Router-01", "{\"host\":\"10.0.0.99\"}");

        ArgumentCaptor<DeviceConfig> captor = ArgumentCaptor.forClass(DeviceConfig.class);
        verify(store).updateDeviceByName(captor.capture(), eq(true));
        assertEquals("CRITICAL", captor.getValue().getUnreachableSeverity());
        assertEquals(4, captor.getValue().getUnreachableCount());
    }

    // ─── 磁碟過濾變更 ─────────────────────────────────────────────

    @Test
    void POST_磁碟過濾改變時解除不再符合的掛載點告警() throws Exception {
        when(store.loadDevice("Router-01")).thenReturn(new DeviceConfig.Builder("Router-01", "10.0.0.1").build());
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post("/Router-01", "{\"host\":\"10.0.0.1\",\"diskFilter\":\"/\"}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Predicate<String>> filter = ArgumentCaptor.forClass(java.util.function.Predicate.class);
        verify(store).resolveOpenAlerts(eq("Router-01"), filter.capture(), anyString());
        assertTrue(filter.getValue().test("disk:/data"), "不再符合的掛載點要解除");
        assertFalse(filter.getValue().test("disk:/"), "仍符合的掛載點保留");
        assertFalse(filter.getValue().test("cpu"), "非磁碟指標不受影響");
    }

    @Test
    void POST_磁碟過濾未改變時不解除告警() throws Exception {
        when(store.loadDevice("Router-01")).thenReturn(new DeviceConfig.Builder("Router-01", "10.0.0.1").diskFilter("/").build());
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post("/Router-01", "{\"host\":\"10.0.0.1\"}");

        verify(store, never()).resolveOpenAlerts(any(), any(), any());
    }

    // ─── POST /api/devices/{name}（刪除） ────────────────────────

    @Test
    void POST_刪除設備成功後通知TrapReceiver() throws Exception {
        String json = "{\"action\":\"delete\"}";
        when(req.getPathInfo()).thenReturn("/Router-01");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.loadEnabledDevices()).thenReturn(List.of());

        servlet.doPost(req, resp);

        verify(store).deleteDeviceByName("Router-01");
        verify(trapReceiver).reloadDevices(any());
        String body = responseBody.toString();
        assertTrue(body.contains("刪除成功"));
    }

    private DeviceConfig captureAdded() throws Exception {
        ArgumentCaptor<DeviceConfig> captor = ArgumentCaptor.forClass(DeviceConfig.class);
        verify(store).addDevice(captor.capture());
        return captor.getValue();
    }

    private void post(String path, String json) throws Exception {
        when(req.getPathInfo()).thenReturn(path);
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        servlet.doPost(req, resp);
    }

    // ─── SNMP 版本與 v3 ──────────────────────────────────────────

    @Test
    void POST_新增v3設備() throws Exception {
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post(null, """
            {"name":"Core","host":"10.0.0.9","snmpVersion":"3","v3User":"monitor",
             "v3AuthProtocol":"SHA-256","v3AuthPassword":"authPass1",
             "v3PrivProtocol":"AES-256","v3PrivPassword":"privPass1"}""");

        DeviceConfig d = captureAdded();
        assertEquals(DeviceConfig.SnmpVersion.V3,       d.getSnmpVersion());
        assertEquals("monitor",                         d.getV3User());
        assertEquals(V3UserConfig.AuthProtocol.SHA256,  d.getV3AuthProtocol());
        assertEquals("authPass1",                       d.getV3AuthPassword());
        assertEquals(V3UserConfig.PrivProtocol.AES256,  d.getV3PrivProtocol());
        assertEquals("privPass1",                       d.getV3PrivPassword());
        verify(resp).setStatus(201);
    }

    @Test
    void POST_版本不合法回傳400() throws Exception {
        post(null, "{\"name\":\"X\",\"host\":\"10.0.0.1\",\"snmpVersion\":\"4\"}");

        verify(resp).setStatus(400);
        verify(store, never()).addDevice(any(DeviceConfig.class));
    }

    @Test
    void POST_v3只有加密沒有認證回傳400() throws Exception {
        post(null, "{\"name\":\"X\",\"host\":\"h\",\"snmpVersion\":\"3\",\"v3User\":\"u\",\"v3PrivPassword\":\"privPass1\"}");

        verify(resp).setStatus(400);
        verify(store, never()).addDevice(any(DeviceConfig.class));
    }

    @Test
    void POST_v3密碼過短回傳400() throws Exception {
        post(null, "{\"name\":\"X\",\"host\":\"h\",\"snmpVersion\":\"3\",\"v3User\":\"u\",\"v3AuthPassword\":\"short\"}");

        verify(resp).setStatus(400);
        verify(store, never()).addDevice(any(DeviceConfig.class));
    }

    @Test
    void POST_更新v3設備未帶密碼_沿用舊值且版本不變() throws Exception {
        DeviceConfig existing = new DeviceConfig.Builder("Core", "10.0.0.9")
            .snmpVersion(DeviceConfig.SnmpVersion.V3).v3User("monitor")
            .v3AuthPassword("authPass1").v3PrivPassword("privPass1").build();
        when(store.loadDevice("Core")).thenReturn(existing);
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post("/Core", "{\"host\":\"10.0.0.10\"}");

        ArgumentCaptor<DeviceConfig> captor = ArgumentCaptor.forClass(DeviceConfig.class);
        verify(store).updateDeviceByName(captor.capture(), eq(true));
        DeviceConfig d = captor.getValue();
        assertEquals(DeviceConfig.SnmpVersion.V3, d.getSnmpVersion());
        assertEquals("monitor", d.getV3User());
        assertNull(d.getV3AuthPassword(), "未帶密碼應傳 null，由資料庫沿用舊值");
        assertNull(d.getV3PrivPassword());
    }

    @Test
    void POST_更新時清除認證但保留加密回傳400() throws Exception {
        DeviceConfig existing = new DeviceConfig.Builder("Core", "10.0.0.9")
            .snmpVersion(DeviceConfig.SnmpVersion.V3).v3User("monitor")
            .v3AuthPassword("authPass1").v3PrivPassword("privPass1").build();
        when(store.loadDevice("Core")).thenReturn(existing);

        post("/Core", "{\"host\":\"10.0.0.9\",\"v3AuthPassword\":\"\"}");

        verify(resp).setStatus(400);
        verify(store, never()).updateDeviceByName(any(DeviceConfig.class), anyBoolean());
    }

    // ─── 監控範本 ─────────────────────────────────────────────────

    @Test
    void POST_新增設備指定範本() throws Exception {
        when(store.loadProfile(7)).thenReturn(new io.github.chiayi1224.simplesnmp.profile.MetricProfile(
            7L, "Mine", "", "", false, List.of()));
        when(store.loadEnabledDevices()).thenReturn(List.of());

        post(null, "{\"name\":\"SW\",\"host\":\"10.0.0.1\",\"profileId\":7}");

        assertEquals(7L, captureAdded().getProfileId());
    }

    @Test
    void POST_指定不存在的範本回傳400() throws Exception {
        post(null, "{\"name\":\"SW\",\"host\":\"10.0.0.1\",\"profileId\":99}");

        verify(resp).setStatus(400);
        verify(store, never()).addDevice(any(DeviceConfig.class));
    }

    @Test
    void POST_更新時省略profileId沿用原設定_傳null改回自動() throws Exception {
        when(store.loadProfile(7)).thenReturn(new io.github.chiayi1224.simplesnmp.profile.MetricProfile(
            7L, "Mine", "", "", false, List.of()));
        when(store.loadDevice("SW")).thenReturn(new DeviceConfig.Builder("SW", "10.0.0.1").profileId(7L).build());
        when(store.loadEnabledDevices()).thenReturn(List.of());
        ArgumentCaptor<DeviceConfig> captor = ArgumentCaptor.forClass(DeviceConfig.class);

        post("/SW", "{\"host\":\"10.0.0.2\"}");
        verify(store).updateDeviceByName(captor.capture(), eq(true));
        assertEquals(7L, captor.getValue().getProfileId());

        clearInvocations(store);
        StringWriter second = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(second));
        post("/SW", "{\"host\":\"10.0.0.2\",\"profileId\":null}");
        verify(store).updateDeviceByName(captor.capture(), eq(true));
        assertNull(captor.getValue().getProfileId());
    }
}
