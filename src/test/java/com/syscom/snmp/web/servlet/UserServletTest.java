package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
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
class UserServletTest {

    @Mock MetricStore         store;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    UserServlet  servlet;
    StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        servlet      = new UserServlet(store);
        responseBody = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    // ─── GET /api/users ───────────────────────────────────────────

    @Test
    void GET_回傳帳號清單() throws Exception {
        when(store.queryAllUsers()).thenReturn(List.of(
            Map.of("id", 1L, "username", "admin", "role", "admin")
        ));

        servlet.doGet(req, resp);

        String body = responseBody.toString();
        assertTrue(body.contains("admin"));
    }

    // ─── POST /api/users ──────────────────────────────────────────

    @Test
    void POST_新增帳號成功() throws Exception {
        String json = "{\"username\":\"alice\",\"password\":\"password123\",\"role\":\"admin\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(store).addUser("alice", "password123", "admin");
        verify(resp).setStatus(201);
    }

    @Test
    void POST_帳號為空回傳400() throws Exception {
        String json = "{\"username\":\"\",\"password\":\"pass123\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).addUser(any(), any(), any());
    }

    @Test
    void POST_密碼為空回傳400() throws Exception {
        String json = "{\"username\":\"bob\",\"password\":\"\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
    }

    @Test
    void POST_密碼少於六字元回傳400() throws Exception {
        String json = "{\"username\":\"bob\",\"password\":\"12345\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        String body = responseBody.toString();
        assertTrue(body.contains("6"));
    }

    @Test
    void POST_無role使用預設admin() throws Exception {
        String json = "{\"username\":\"carol\",\"password\":\"password\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(store).addUser("carol", "password", "admin");
    }

    // ─── PUT /api/users/{id} ──────────────────────────────────────

    @Test
    void PUT_修改帳號資訊成功() throws Exception {
        String json = "{\"username\":\"alice-new\",\"role\":\"admin\",\"enabled\":true}";
        when(req.getPathInfo()).thenReturn("/2");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(store).updateUser(2L, "alice-new", "admin", true);
        String body = responseBody.toString();
        assertTrue(body.contains("更新成功"));
    }

    @Test
    void PUT_修改密碼成功() throws Exception {
        String json = "{\"password\":\"newpassword\"}";
        when(req.getPathInfo()).thenReturn("/2/password");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(store).changePassword(2L, "newpassword");
        String body = responseBody.toString();
        assertTrue(body.contains("密碼已更新"));
    }

    @Test
    void PUT_修改密碼太短回傳400() throws Exception {
        String json = "{\"password\":\"short\"}";
        when(req.getPathInfo()).thenReturn("/2/password");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).changePassword(anyLong(), any());
    }

    @Test
    void PUT_缺少ID回傳400() throws Exception {
        when(req.getPathInfo()).thenReturn(null);

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
    }

    // ─── DELETE /api/users/{id} ───────────────────────────────────

    @Test
    void DELETE_刪除帳號成功() throws Exception {
        when(req.getPathInfo()).thenReturn("/2");

        servlet.doDelete(req, resp);

        verify(store).deleteUser(2L);
        String body = responseBody.toString();
        assertTrue(body.contains("刪除成功"));
    }

    @Test
    void DELETE_缺少ID回傳400() throws Exception {
        when(req.getPathInfo()).thenReturn(null);

        servlet.doDelete(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).deleteUser(anyLong());
    }

    @Test
    void DELETE_路徑為斜線回傳400() throws Exception {
        when(req.getPathInfo()).thenReturn("/");

        servlet.doDelete(req, resp);

        verify(resp).setStatus(400);
    }
}
