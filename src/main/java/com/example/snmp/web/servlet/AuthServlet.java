package com.example.snmp.web.servlet;

import com.example.snmp.storage.MetricStore;
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

    private final MetricStore store;

    public AuthServlet(MetricStore store) {
        this.store = store;
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
        try {
            JsonNode body     = JSON.readTree(req.getReader());
            String   username = body.get("username").asText();
            String   password = body.get("password").asText();

            Map<String, Object> user = store.authenticate(username, password);
            if (user == null) {
                writeError(resp, 401, "帳號或密碼錯誤");
                return;
            }

            // 建立新 Session（防止 session fixation）
            HttpSession old = req.getSession(false);
            if (old != null) old.invalidate();
            HttpSession session = req.getSession(true);
            session.setAttribute("user", user);
            session.setMaxInactiveInterval(60 * 60); // 1 小時

            boolean mustChange = Boolean.TRUE.equals(user.get("mustChange"));

            if (mustChange) {
                // 202 = 登入成功但需要做額外動作（改密碼）
                resp.setStatus(202);
                writeJson(resp, Map.of(
                    "mustChange", true,
                    "message",    "請先修改預設密碼",
                    "username",   user.get("username")
                ));
            } else {
                writeJson(resp, Map.of(
                    "message",  "登入成功",
                    "username", user.get("username"),
                    "role",     user.get("role")
                ));
            }
        } catch (Exception e) {
            writeError(resp, 400, "請求格式錯誤");
        }
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        writeJson(resp, Map.of("message", "已登出"));
    }
}
