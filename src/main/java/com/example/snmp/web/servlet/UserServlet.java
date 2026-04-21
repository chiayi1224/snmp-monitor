package com.example.snmp.web.servlet;

import com.example.snmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 帳號管理 API（需要登入才能使用）
 *
 *   GET    /api/users           — 取得所有帳號
 *   POST   /api/users           — 新增帳號
 *   PUT    /api/users/{id}      — 修改帳號（含停用）
 *   PUT    /api/users/{id}/password — 修改密碼
 *   DELETE /api/users/{id}      — 刪除帳號
 */
public class UserServlet extends BaseJsonServlet {

    private final MetricStore store;

    public UserServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllUsers());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JsonNode body     = JSON.readTree(req.getReader());
            String   username = body.get("username").asText().trim();
            String   password = body.get("password").asText();
            String   role     = body.has("role") ? body.get("role").asText() : "admin";

            if (username.isBlank() || password.isBlank()) {
                writeError(resp, 400, "帳號和密碼為必填");
                return;
            }
            if (password.length() < 6) {
                writeError(resp, 400, "密碼長度至少 6 字元");
                return;
            }

            store.addUser(username, password, role);
            resp.setStatus(201);
            writeJson(resp, Map.of("message", "新增成功", "username", username));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String pathInfo = req.getPathInfo();  // "/{id}" 或 "/{id}/password"
            if (pathInfo == null) { writeError(resp, 400, "缺少 ID"); return; }

            String[] parts = pathInfo.substring(1).split("/");
            long id = Long.parseLong(parts[0]);

            JsonNode body = JSON.readTree(req.getReader());

            if (parts.length >= 2 && "password".equals(parts[1])) {
                // 修改密碼
                String newPassword = body.get("password").asText();
                if (newPassword.length() < 6) {
                    writeError(resp, 400, "密碼長度至少 6 字元");
                    return;
                }
                store.changePassword(id, newPassword);
                writeJson(resp, Map.of("message", "密碼已更新"));
            } else {
                // 修改帳號資訊
                String  username = body.get("username").asText().trim();
                String  role     = body.has("role")    ? body.get("role").asText()        : "admin";
                boolean enabled  = !body.has("enabled") || body.get("enabled").asBoolean();
                store.updateUser(id, username, role, enabled);
                writeJson(resp, Map.of("message", "更新成功"));
            }
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                writeError(resp, 400, "缺少 ID"); return;
            }
            long id = Long.parseLong(pathInfo.substring(1));
            store.deleteUser(id);
            writeJson(resp, Map.of("message", "刪除成功"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
