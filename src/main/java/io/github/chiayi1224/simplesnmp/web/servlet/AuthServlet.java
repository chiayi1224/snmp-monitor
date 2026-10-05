package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.web.SessionRevocation;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Map;

/**
 * 認證 API
 *
 *   POST /api/auth/login   — 登入
 *   POST /api/auth/logout  — 登出
 *   GET  /api/auth/me      — 目前登入帳號
 *
 * 登入成功但 mustChange=true 時，回傳 HTTP 202 + mustChange:true，
 * 前端收到後強制跳到改密碼頁面，改完才能進入主畫面。
 */
public class AuthServlet extends BaseJsonServlet {

    private final MetricStore       store;
    private final SessionRevocation revocation;

    public AuthServlet(MetricStore store) {
        this(store, new SessionRevocation());
    }

    public AuthServlet(MetricStore store, SessionRevocation revocation) {
        this.store      = store;
        this.revocation = revocation;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("user") != null) {
            writeJson(resp, session.getAttribute("user"));
        } else {
            writeError(resp, 401, "未登入");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if ("/logout".equals(path)) handleLogout(req, resp);
        else                        handleLogin(req, resp);
    }

    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String ip = getClientIp(req);
        try {
            JsonNode body    = parseBody(req, resp);
            if (body == null) return;
            String username  = requireText(body, "username", resp); if (username == null) return;
            String password  = requireText(body, "password", resp); if (password == null) return;

            Map<String, Object> user = store.authenticate(username, password);
            if (user == null) {
                store.saveAuditLog("LOGIN_FAIL", username, ip, "POST", "/api/auth/login", 401, "帳號或密碼錯誤");
                writeError(resp, 401, "帳號或密碼錯誤");
                return;
            }

            HttpSession old = req.getSession(false);
            if (old != null) old.invalidate();
            HttpSession session = req.getSession(true);
            session.setAttribute("user", user);
            session.setMaxInactiveInterval(60 * 60);
            revocation.markLogin(session);

            store.saveAuditLog("LOGIN_SUCCESS", username, ip, "POST", "/api/auth/login", 200, null);

            boolean mustChange = Boolean.TRUE.equals(user.get("mustChange"));
            if (mustChange) {
                resp.setStatus(202);
                writeJson(resp, Map.of("mustChange", true, "message", "請先修改預設密碼", "username", user.get("username")));
            } else {
                writeJson(resp, Map.of("message", "登入成功", "username", user.get("username"), "role", user.get("role")));
            }
        } catch (Exception e) {
            writeError(resp, 400, "請求格式錯誤");
        }
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String ip = getClientIp(req);
        HttpSession session = req.getSession(false);
        String actor = null;
        if (session != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>) session.getAttribute("user");
            if (user != null) actor = (String) user.get("username");
            session.invalidate();
        }
        store.saveAuditLog("LOGOUT", actor, ip, "POST", "/api/auth/logout", 200, null);
        writeJson(resp, Map.of("message", "已登出"));
    }

    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
