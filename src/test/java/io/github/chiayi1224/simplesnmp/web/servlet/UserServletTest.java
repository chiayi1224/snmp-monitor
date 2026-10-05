package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.web.SessionRevocation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
        loginAs(1L, "admin");
        String json = "{\"username\":\"alice\",\"password\":\"password123\",\"role\":\"admin\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(store).addUser("alice", "password123", "admin");
        verify(resp).setStatus(201);
    }

    @Test
    void POST_帳號為空回傳400() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"username\":\"\",\"password\":\"pass123\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).addUser(any(), any(), any());
    }

    @Test
    void POST_密碼為空回傳400() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"username\":\"bob\",\"password\":\"\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
    }

    @Test
    void POST_密碼少於六字元回傳400() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"username\":\"bob\",\"password\":\"12345\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(resp).setStatus(400);
        String body = responseBody.toString();
        assertTrue(body.contains("6"));
    }

    @Test
    void POST_無role使用預設admin() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"username\":\"carol\",\"password\":\"password\"}";
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPost(req, resp);

        verify(store).addUser("carol", "password", "admin");
    }

    // ─── PUT /api/users/{id} ──────────────────────────────────────

    @Test
    void PUT_修改帳號資訊成功() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"username\":\"alice-new\",\"role\":\"admin\",\"enabled\":true}";
        when(req.getPathInfo()).thenReturn("/2");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(store).updateUser(2L, "alice-new", "admin", true);
        String body = responseBody.toString();
        assertTrue(body.contains("更新成功"));
    }

    /** 模擬目前登入的使用者 */
    private void loginAs(long id, String role) {
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(Map.of("id", id, "username", "u" + id, "role", role));
    }

    @Test
    void PUT_自己修改密碼_清除強制改密碼() throws Exception {
        loginAs(2L, "operator");
        String json = "{\"password\":\"newpassword\"}";
        when(req.getPathInfo()).thenReturn("/2/password");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(store).changePassword(2L, "newpassword");
        verify(store, never()).resetPassword(anyLong(), any());
        assertTrue(responseBody.toString().contains("密碼已更新"));
    }

    @Test
    void PUT_admin修改他人密碼_設定強制改密碼() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"password\":\"newpassword\"}";
        when(req.getPathInfo()).thenReturn("/2/password");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(store).resetPassword(2L, "newpassword");
        verify(store, never()).changePassword(anyLong(), any());
        assertTrue(responseBody.toString().contains("下次登入須修改密碼"));
    }

    @Test
    void PUT_非admin修改他人密碼回傳403() throws Exception {
        loginAs(2L, "operator");
        when(req.getPathInfo()).thenReturn("/3/password");

        servlet.doPut(req, resp);

        verify(resp).setStatus(403);
        verify(store, never()).changePassword(anyLong(), any());
        verify(store, never()).resetPassword(anyLong(), any());
    }

    @Test
    void PUT_未登入修改密碼回傳401() throws Exception {
        when(req.getSession(false)).thenReturn(null);
        when(req.getPathInfo()).thenReturn("/2/password");

        servlet.doPut(req, resp);

        verify(resp).setStatus(401);
        verify(store, never()).changePassword(anyLong(), any());
        verify(store, never()).resetPassword(anyLong(), any());
    }

    @Test
    void PUT_修改密碼太短回傳400() throws Exception {
        loginAs(2L, "admin");
        String json = "{\"password\":\"short\"}";
        when(req.getPathInfo()).thenReturn("/2/password");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).changePassword(anyLong(), any());
        verify(store, never()).resetPassword(anyLong(), any());
    }

    @Test
    void PUT_缺少ID回傳400() throws Exception {
        when(req.getPathInfo()).thenReturn(null);

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
    }

    @Test
    void PUT_ID非數字回傳400() throws Exception {
        when(req.getPathInfo()).thenReturn("/abc");

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).updateUser(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void DELETE_ID非數字回傳400() throws Exception {
        when(req.getPathInfo()).thenReturn("/abc");

        servlet.doDelete(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).deleteUser(anyLong());
    }

    // ─── DELETE /api/users/{id} ───────────────────────────────────

    @Test
    void DELETE_刪除帳號成功() throws Exception {
        loginAs(1L, "admin");
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

    // ─── 帳號管理權限 ─────────────────────────────────────────────

    @Test
    void POST_非admin新增帳號回傳403() throws Exception {
        loginAs(2L, "operator");

        servlet.doPost(req, resp);

        verify(resp).setStatus(403);
        verify(store, never()).addUser(any(), any(), any());
    }

    @Test
    void PUT_非admin修改帳號回傳403() throws Exception {
        loginAs(2L, "operator");
        when(req.getPathInfo()).thenReturn("/2");

        servlet.doPut(req, resp);

        verify(resp).setStatus(403);
        verify(store, never()).updateUser(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void PUT_admin不能變更自己的角色() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"username\":\"admin\",\"role\":\"operator\",\"enabled\":true}";
        when(req.getPathInfo()).thenReturn("/1");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).updateUser(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void PUT_admin不能停用自己() throws Exception {
        loginAs(1L, "admin");
        String json = "{\"username\":\"admin\",\"role\":\"admin\",\"enabled\":false}";
        when(req.getPathInfo()).thenReturn("/1");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        servlet.doPut(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).updateUser(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void DELETE_非admin回傳403() throws Exception {
        loginAs(2L, "operator");
        when(req.getPathInfo()).thenReturn("/3");

        servlet.doDelete(req, resp);

        verify(resp).setStatus(403);
        verify(store, never()).deleteUser(anyLong());
    }

    @Test
    void DELETE_不能刪除自己() throws Exception {
        loginAs(1L, "admin");
        when(req.getPathInfo()).thenReturn("/1");

        servlet.doDelete(req, resp);

        verify(resp).setStatus(400);
        verify(store, never()).deleteUser(anyLong());
    }

    // ─── 強制踢出（session 撤銷）────────────────────────────────────

    /** 一個沒有登入序號的既有 session，帳號被撤銷後應視為失效 */
    private static HttpSession existingSession() {
        return mock(HttpSession.class);
    }

    @Test
    void admin重設他人密碼_踢出該帳號() throws Exception {
        SessionRevocation revocation = new SessionRevocation();
        UserServlet s = new UserServlet(store, revocation);
        loginAs(1L, "admin");
        when(req.getPathInfo()).thenReturn("/2/password");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"password\":\"newpassword\"}")));

        s.doPut(req, resp);

        assertTrue(revocation.isRevoked(2L, existingSession()));
    }

    @Test
    void 自己改密碼_不踢出() throws Exception {
        SessionRevocation revocation = new SessionRevocation();
        UserServlet s = new UserServlet(store, revocation);
        loginAs(2L, "operator");
        when(req.getPathInfo()).thenReturn("/2/password");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"password\":\"newpassword\"}")));

        s.doPut(req, resp);

        assertFalse(revocation.isRevoked(2L, existingSession()));
    }

    @Test
    void admin修改他人帳號_踢出該帳號() throws Exception {
        SessionRevocation revocation = new SessionRevocation();
        UserServlet s = new UserServlet(store, revocation);
        loginAs(1L, "admin");
        when(req.getPathInfo()).thenReturn("/2");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
            "{\"username\":\"bob\",\"role\":\"admin\",\"enabled\":false}")));

        s.doPut(req, resp);

        verify(store).updateUser(2L, "bob", "admin", false);
        assertTrue(revocation.isRevoked(2L, existingSession()));
    }

    @Test
    void admin刪除他人帳號_踢出該帳號() throws Exception {
        SessionRevocation revocation = new SessionRevocation();
        UserServlet s = new UserServlet(store, revocation);
        loginAs(1L, "admin");
        when(req.getPathInfo()).thenReturn("/2");

        s.doDelete(req, resp);

        verify(store).deleteUser(2L);
        assertTrue(revocation.isRevoked(2L, existingSession()));
    }
}
