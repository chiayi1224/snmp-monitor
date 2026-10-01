package io.github.chiayi1224.simplesnmp.web;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthFilterTest {

    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;
    @Mock FilterChain         chain;
    @Mock HttpSession         session;
    @Mock MetricStore         store;

    AuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthFilter(store);
    }

    // ─── 公開路徑直接放行 ─────────────────────────────────────────

    @Test
    void login頁面不需驗證直接放行() throws Exception {
        when(req.getRequestURI()).thenReturn("/login.html");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void loginApi不需驗證直接放行() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/auth/login");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void changePassword頁面不需驗證直接放行() throws Exception {
        when(req.getRequestURI()).thenReturn("/change-password.html");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    // ─── 有效 Session 放行 ────────────────────────────────────────

    @Test
    void 有效Session放行受保護頁面() throws Exception {
        when(req.getRequestURI()).thenReturn("/index.html");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(java.util.Map.of("username", "admin"));

        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void 有效Session放行API請求() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/status");
        when(req.getMethod()).thenReturn("POST");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(Map.of("username", "admin"));

        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    // ─── 未登入攔截 ───────────────────────────────────────────────

    @Test
    void 未登入API請求回401() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/status");
        when(req.getSession(false)).thenReturn(null);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void 未登入頁面請求跳轉登入頁() throws Exception {
        when(req.getRequestURI()).thenReturn("/index.html");
        when(req.getSession(false)).thenReturn(null);

        filter.doFilter(req, resp, chain);

        verify(resp).sendRedirect("/login.html");
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void Session存在但user屬性為null_視為未登入() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/status");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(null);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, resp);
    }

    // ─── Bearer Token 測試 ────────────────────────────────────────

    @Test
    void BearerToken存取admin路徑回403() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/admin/devices");
        when(req.getMethod()).thenReturn("POST");
        when(req.getHeader("Authorization")).thenReturn("Bearer snmp_testtoken123");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(store).saveAuditLog(eq("TOKEN_FAIL"), isNull(), any(), any(), any(), eq(403), any());
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void 有效BearerToken存取v1路徑放行() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/status");
        when(req.getMethod()).thenReturn("POST");
        when(req.getHeader("Authorization")).thenReturn("Bearer snmp_validtoken");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(store.validateApiToken("snmp_validtoken"))
            .thenReturn(Map.of("description", "外部系統"));

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(store).saveAuditLog(eq("TOKEN_USED"), any(), any(), any(), any(), eq(200), isNull());
    }

    @Test
    void 無效BearerToken回401() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/status");
        when(req.getMethod()).thenReturn("POST");
        when(req.getHeader("Authorization")).thenReturn("Bearer snmp_invalidtoken");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(store.validateApiToken("snmp_invalidtoken")).thenReturn(null);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, resp);
    }
}
