package io.github.chiayi1224.simplesnmp.web;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * 認證 Filter
 *
 * 路徑規則：
 *   PUBLIC_PATHS    → 直接放行
 *   /api/v1/*       → Session 或 Bearer Token 皆可（外部 API）
 *   /api/admin/*    → 僅 Session（Bearer Token 回 403，管理介面不對外開放）
 *   /api/auth/*     → 僅 Session（login 已在 PUBLIC_PATHS 放行）
 *
 * 稽核日誌：
 *   LOGIN_SUCCESS / LOGIN_FAIL / LOGOUT → 由 AuthServlet 記錄
 *   TOKEN_USED / TOKEN_FAIL             → 本 Filter 記錄
 *   API_WRITE（admin POST/PUT/DELETE）  → 本 Filter 記錄
 */
public class AuthFilter implements Filter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/login.html",
        "/change-password.html",
        "/api/auth/login",
        "/api/info"
    );

    private final MetricStore store;

    public AuthFilter(MetricStore store) {
        this.store = store;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path   = request.getRequestURI();
        String method = request.getMethod();
        String ip     = getClientIp(request);

        // ── 公開路徑 ──────────────────────────────────────────────
        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }

        // ── Bearer Token 認證 ─────────────────────────────────────
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();

            // admin 路徑禁止 Token 存取
            if (path.startsWith("/api/admin/")) {
                store.saveAuditLog("TOKEN_FAIL", null, ip, method, path, 403, "Token 不可存取管理介面");
                sendError(response, 403, "管理介面僅允許 Session 登入");
                return;
            }

            Map<String, Object> tokenInfo = store.validateApiToken(token);
            if (tokenInfo != null) {
                String actor = "token:" + tokenInfo.get("description");
                request.setAttribute("audit_actor", actor);
                store.saveAuditLog("TOKEN_USED", actor, ip, method, path, 200, null);
                chain.doFilter(req, res);
                return;
            }

            store.saveAuditLog("TOKEN_FAIL", null, ip, method, path, 401, "無效的 API Token");
            sendError(response, 401, "無效的 API Token");
            return;
        }

        // ── Session 認證 ──────────────────────────────────────────
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("user") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>) session.getAttribute("user");
            String actor = (String) user.get("username");
            request.setAttribute("audit_actor", actor);
            // 記錄 admin 寫入操作
            if (!"GET".equalsIgnoreCase(method) && path.startsWith("/api/admin/")) {
                store.saveAuditLog("API_WRITE", actor, ip, method, path, 200, null);
            }
            chain.doFilter(req, res);
            return;
        }

        // ── 未認證 ────────────────────────────────────────────────
        if (path.startsWith("/api/")) {
            sendError(response, 401, "未登入");
        } else {
            response.sendRedirect("/login.html");
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setContentType("application/json; charset=UTF-8");
        response.setStatus(status);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    @Override public void init(FilterConfig config) {}
    @Override public void destroy() {}
}
