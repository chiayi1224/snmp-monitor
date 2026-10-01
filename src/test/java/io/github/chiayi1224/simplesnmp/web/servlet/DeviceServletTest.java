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

        verify(store).addDevice("Server-01", "192.168.1.10", 161, "public", 2, 3000L);
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
        verify(store, never()).addDevice(any(), any(), anyInt(), any(), anyInt(), anyLong());
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

        verify(store).addDevice("Dev", "10.0.0.1", 161, "public", 2, 3000L);
    }

    // ─── POST /api/devices/{name}（更新） ────────────────────────

    @Test
    void POST_更新設備成功後通知TrapReceiver() throws Exception {
        String json = "{\"host\":\"10.0.0.99\",\"enabled\":true}";
        when(req.getPathInfo()).thenReturn("/Router-01");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.loadEnabledDevices()).thenReturn(List.of());

        servlet.doPost(req, resp);

        verify(store).updateDeviceByName(eq("Router-01"), eq("10.0.0.99"), anyInt(), any(), eq(true), anyInt(), anyLong());
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
}
