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
 * 強制改密碼（must_change）：
 *   帳號若被標記為必須改密碼，其 session 僅視為「半認證」，在伺服器端
 *   只放行「取得自身帳號」「登出」與「修改自己的密碼」，其餘請求一律拒絕，
 *   成功修改自己密碼後才清除 must_change 旗標。此把關不可只靠前端導向。
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

    // 修改自己密碼的路徑：PUT /api/admin/users/{id}/password
    private static final java.util.regex.Pattern OWN_PASSWORD_PATH =
        java.util.regex.Pattern.compile("^/api/admin/users/(\\d+)/password$");

    private final MetricStore store;

    public AuthFilter(MetricStore store) {
        this.store = store;
    }

    /**
     * 判斷請求是否為「修改自己密碼」：路徑須完全符合 /api/admin/users/{id}/password，
     * 且 {id} 必須等於 session 中登入帳號的 id（不得改別人的密碼）。
     */
    private boolean isOwnPasswordChangePath(String path, Object sessionUserId) {
        if (path == null || !(sessionUserId instanceof Number)) return false;
        java.util.regex.Matcher m = OWN_PASSWORD_PATH.matcher(path);
        if (!m.matches()) return false;
        try {
            return Long.parseLong(m.group(1)) == ((Number) sessionUserId).longValue();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String method = request.getMethod();
        String ip     = getClientIp(request);

        // ── 路徑正規化 ────────────────────────────────────────────
        // 安全要點：授權判斷必須使用「與實際路由一致」的路徑，否則會被繞過。
        // 原本用 request.getRequestURI()（未解碼、未正規化的原始 URI）判斷 /api/admin/
        // 前綴，但 Jetty 分派 servlet 時用的是正規化後的路徑，兩者解讀不一致，導致
        // 以 /api/%61dmin/、/api/admin;x/、/api/admin%2F… 等寫法即可繞過「Token 不可
        // 存取管理介面」的檢查。改用容器已解碼並正規化的 getServletPath()+getPathInfo()。
        //
        // 編碼斜線（%2F / %5C）在不同容器的正規化行為不一致，本系統路徑中也沒有合法用途，
        // 一律直接拒絕，避免殘留的路徑混淆空間。
        String rawUri = request.getRequestURI();
        String lowerRaw = rawUri == null ? "" : rawUri.toLowerCase();
        if (lowerRaw.contains("%2f") || lowerRaw.contains("%5c")) {
            sendError(response, 400, "路徑格式不正確");
            return;
        }
        String servletPath = request.getServletPath();
        String pathInfo    = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath)
                    + (pathInfo    == null ? "" : pathInfo);

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
                // 稽核記錄原始 URI（rawUri）而非正規化後的 path，保留攻擊者的編碼痕跡
                store.saveAuditLog("TOKEN_FAIL", null, ip, method, rawUri, 403, "Token 不可存取管理介面");
                sendError(response, 403, "管理介面僅允許 Session 登入");
                return;
            }

            Map<String, Object> tokenInfo = store.validateApiToken(token);
            if (tokenInfo != null) {
                String actor = "token:" + tokenInfo.get("description");
                request.setAttribute("audit_actor", actor);
                store.saveAuditLog("TOKEN_USED", actor, ip, method, rawUri, 200, null);
                chain.doFilter(req, res);
                return;
            }

            store.saveAuditLog("TOKEN_FAIL", null, ip, method, rawUri, 401, "無效的 API Token");
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

            // ── 強制改密碼（must_change）伺服器端強制 ──────────────────
            // 安全要點：must_change 必須在伺服器端把關，不能只靠前端導向。
            // 被標記為必須改密碼的 session 僅視為「半認證」，只放行：
            //   GET  /api/auth/me                       （改密碼頁取得自身帳號）
            //   POST /api/auth/logout                   （登出）
            //   PUT  /api/admin/users/{自己id}/password （修改自己的密碼）
            // 其餘請求一律拒絕，避免預設密碼帳號在未改密碼前即可呼叫其他管理 API。
            if (Boolean.TRUE.equals(user.get("mustChange"))) {
                boolean ownPasswordChange = "PUT".equalsIgnoreCase(method)
                        && isOwnPasswordChangePath(path, user.get("id"));
                boolean allowed = ownPasswordChange
                        || ("GET".equalsIgnoreCase(method)  && "/api/auth/me".equals(path))
                        || ("POST".equalsIgnoreCase(method) && "/api/auth/logout".equals(path));
                if (!allowed) {
                    if (path.startsWith("/api/")) {
                        store.saveAuditLog("MUST_CHANGE_BLOCKED", actor, ip, method, rawUri, 403, "尚未修改預設密碼");
                        sendError(response, 403, "請先修改預設密碼後再使用其他功能");
                    } else {
                        response.sendRedirect("/change-password.html");
                    }
                    return;
                }
                if (ownPasswordChange) {
                    store.saveAuditLog("API_WRITE", actor, ip, method, rawUri, 200, null);
                }
                chain.doFilter(req, res);
                // 僅在成功修改自己密碼後，才清除 session 的 must_change 旗標
                if (ownPasswordChange && response.getStatus() >= 200 && response.getStatus() < 300) {
                    Map<String, Object> cleared = new java.util.LinkedHashMap<>(user);
                    cleared.put("mustChange", Boolean.FALSE);
                    session.setAttribute("user", cleared);
                }
                return;
            }

            // 記錄 admin 寫入操作
            if (!"GET".equalsIgnoreCase(method) && path.startsWith("/api/admin/")) {
                store.saveAuditLog("API_WRITE", actor, ip, method, rawUri, 200, null);
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
