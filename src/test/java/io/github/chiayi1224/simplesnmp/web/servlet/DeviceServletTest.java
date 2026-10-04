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
}
