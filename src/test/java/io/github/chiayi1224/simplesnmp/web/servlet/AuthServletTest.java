package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServletTest {

    @Mock MetricStore          store;
    @Mock HttpServletRequest   req;
    @Mock HttpServletResponse  resp;
    @Mock HttpSession          session;

    AuthServlet servlet;
    StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        servlet      = new AuthServlet(store);
        responseBody = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    // ─── GET /api/auth/me ─────────────────────────────────────────

    @Test
    void me_已登入回傳帳號資訊() throws Exception {
        Map<String, Object> user = Map.of("username", "admin", "role", "admin");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(user);

        servlet.doGet(req, resp);

        String body = responseBody.toString();
        assertTrue(body.contains("admin"));
    }

    @Test
    void me_未登入回傳401() throws Exception {
        when(req.getSession(false)).thenReturn(null);

        servlet.doGet(req, resp);

        verify(resp).setStatus(401);
    }

    // ─── POST /api/auth/login ─────────────────────────────────────

    @Test
    void login_成功回傳200和帳號資訊() throws Exception {
        String json = "{\"username\":\"admin\",\"password\":\"admin\"}";
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.authenticate("admin", "admin")).thenReturn(
            Map.of("username", "admin", "role", "admin", "mustChange", false));
        when(req.getSession(false)).thenReturn(null);
        when(req.getSession(true)).thenReturn(session);

        servlet.doPost(req, resp);

        String body = responseBody.toString();
        assertTrue(body.contains("admin"));
        assertTrue(body.contains("登入成功"));
    }

    @Test
    void login_mustChangeTrue_回傳202() throws Exception {
        String json = "{\"username\":\"admin\",\"password\":\"admin\"}";
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.authenticate("admin", "admin")).thenReturn(
            Map.of("username", "admin", "role", "admin", "mustChange", true));
        when(req.getSession(false)).thenReturn(null);
        when(req.getSession(true)).thenReturn(session);

        servlet.doPost(req, resp);

        verify(resp).setStatus(202);
        String body = responseBody.toString();
        assertTrue(body.contains("mustChange"));
    }

    @Test
    void login_帳號或密碼錯誤回傳401() throws Exception {
        String json = "{\"username\":\"admin\",\"password\":\"wrong\"}";
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.authenticate("admin", "wrong")).thenReturn(null);

        servlet.doPost(req, resp);

        verify(resp).setStatus(401);
        String body = responseBody.toString();
        assertTrue(body.contains("帳號或密碼錯誤"));
    }

    @Test
    void login_舊Session存在會先invalidate() throws Exception {
        String json = "{\"username\":\"admin\",\"password\":\"pass\"}";
        when(req.getPathInfo()).thenReturn("/login");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
        when(store.authenticate("admin", "pass")).thenReturn(
            Map.of("username", "admin", "role", "admin", "mustChange", false));
        HttpSession oldSession = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(oldSession);
        when(req.getSession(true)).thenReturn(session);

        servlet.doPost(req, resp);

        verify(oldSession).invalidate();
    }

    // ─── POST /api/auth/logout ────────────────────────────────────

    @Test
    void logout_Session存在則invalidate() throws Exception {
        when(req.getPathInfo()).thenReturn("/logout");
        when(req.getSession(false)).thenReturn(session);

        servlet.doPost(req, resp);

        verify(session).invalidate();
        String body = responseBody.toString();
        assertTrue(body.contains("已登出"));
    }

    @Test
    void logout_無Session不拋例外() throws Exception {
        when(req.getPathInfo()).thenReturn("/logout");
        when(req.getSession(false)).thenReturn(null);

        assertDoesNotThrow(() -> servlet.doPost(req, resp));
        String body = responseBody.toString();
        assertTrue(body.contains("已登出"));
    }
}
