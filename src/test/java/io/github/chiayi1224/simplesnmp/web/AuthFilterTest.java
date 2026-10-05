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

    /**
     * 模擬容器對請求路徑的正規化：授權判斷使用 getServletPath()+getPathInfo()，
     * 而 getRequestURI() 回傳原始（可能帶編碼）URI。呼叫時把正規化後的路徑放在
     * servletPath，pathInfo 留空（concatenation 結果相同，與真實 servlet 對應無關）。
     */
    private void stubPath(String normalized) {
        stubPath(normalized, normalized);
    }

    private void stubPath(String rawUri, String normalized) {
        when(req.getRequestURI()).thenReturn(rawUri);
        when(req.getServletPath()).thenReturn(normalized);
    }

    // ─── 公開路徑直接放行 ─────────────────────────────────────────

    @Test
    void login頁面不需驗證直接放行() throws Exception {
        stubPath("/login.html");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void loginApi不需驗證直接放行() throws Exception {
        stubPath("/api/auth/login");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void changePassword頁面不需驗證直接放行() throws Exception {
        stubPath("/change-password.html");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    // ─── 有效 Session 放行 ────────────────────────────────────────

    @Test
    void 有效Session放行受保護頁面() throws Exception {
        stubPath("/index.html");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(java.util.Map.of("username", "admin"));

        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void 有效Session放行API請求() throws Exception {
        stubPath("/api/v1/status");
        when(req.getMethod()).thenReturn("POST");
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(Map.of("username", "admin"));

        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    // ─── 未登入攔截 ───────────────────────────────────────────────

    @Test
    void 未登入API請求回401() throws Exception {
        stubPath("/api/v1/status");
        when(req.getSession(false)).thenReturn(null);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void 未登入頁面請求跳轉登入頁() throws Exception {
        stubPath("/index.html");
        when(req.getSession(false)).thenReturn(null);

        filter.doFilter(req, resp, chain);

        verify(resp).sendRedirect("/login.html");
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void Session存在但user屬性為null_視為未登入() throws Exception {
        stubPath("/api/v1/status");
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
        stubPath("/api/admin/devices");
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
        stubPath("/api/v1/status");
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
        stubPath("/api/v1/status");
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

    // ─── 路徑混淆繞過回歸測試 ─────────────────────────────────────
    // 以原始 URI（getRequestURI）判斷 admin 前綴時，下列寫法都能繞過；
    // 改以容器正規化路徑（getServletPath+getPathInfo）判斷後必須全部被擋。

    @Test
    void Token以編碼admin路徑繞過仍被擋() throws Exception {
        // 原始 URI 寫成 /api/%61dmin/...，但容器正規化後是 /api/admin/...
        stubPath("/api/%61dmin/users", "/api/admin/users");
        when(req.getMethod()).thenReturn("GET");
        when(req.getHeader("Authorization")).thenReturn("Bearer snmp_validtoken");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(req, resp);
        // 不得驗證 token、也不得真正進入 servlet
        verify(store, never()).validateApiToken(anyString());
    }

    @Test
    void Token以matrix參數繞過仍被擋() throws Exception {
        // /api/admin;x/users 的 matrix 參數在正規化後被移除，仍是 /api/admin/users
        stubPath("/api/admin;x/users", "/api/admin/users");
        when(req.getMethod()).thenReturn("GET");
        when(req.getHeader("Authorization")).thenReturn("Bearer snmp_validtoken");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void 含編碼斜線的請求回400() throws Exception {
        // %2F 在不同容器正規化行為不一致，在讀取 Authorization 前就一律拒絕
        when(req.getRequestURI()).thenReturn("/api/admin%2Fusers");
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        verify(chain, never()).doFilter(req, resp);
        verify(store, never()).validateApiToken(anyString());
    }
}
