package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
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
            JsonNode body    = parseBody(req, resp);
            if (body == null) return;

            String username  = requireText(body, "username", resp); if (username == null) return;
            String password  = requireText(body, "password", resp); if (password == null) return;
            String role      = optText(body, "role", "admin");

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
            String pathInfo = req.getPathInfo();
            if (pathInfo == null) { writeError(resp, 400, "缺少 ID"); return; }

            String[] parts = pathInfo.substring(1).split("/");
            long id = Long.parseLong(parts[0]);

            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            if (parts.length >= 2 && "password".equals(parts[1])) {
                String newPassword = requireText(body, "password", resp);
                if (newPassword == null) return;
                if (newPassword.length() < 6) {
                    writeError(resp, 400, "密碼長度至少 6 字元");
                    return;
                }
                store.changePassword(id, newPassword);
                writeJson(resp, Map.of("message", "密碼已更新"));
            } else {
                String  username = requireText(body, "username", resp);
                if (username == null) return;
                String  role     = optText(body, "role",    "admin");
                boolean enabled  = optBool(body, "enabled", true);
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
