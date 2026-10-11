package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.i18n.Msg;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.web.SessionRevocation;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Map;

/**
 * 帳號管理 API（需要登入才能使用）
 *
 *   GET    /api/users               — 取得所有帳號
 *   POST   /api/users               — 新增帳號（僅限 admin）
 *   PUT    /api/users/{id}          — 修改帳號（含停用；僅限 admin，不能改自己的角色或停用自己）
 *   PUT    /api/users/{id}/password — 修改密碼（自己改自己：清除強制改密碼；
 *                                     admin 改他人：設定強制改密碼並踢出該帳號；非 admin 改他人：403）
 *   DELETE /api/users/{id}          — 刪除帳號（僅限 admin，不能刪除自己）
 *
 * 修改、重設密碼或刪除「他人」帳號時，會撤銷該帳號目前所有 session（強制踢出），
 * 讓角色、停用與密碼變更立即生效；自己改自己的密碼不踢出。
 */
public class UserServlet extends BaseJsonServlet {

    private static final String ADMIN_ROLE = "admin";

    private final MetricStore       store;
    private final SessionRevocation revocation;

    public UserServlet(MetricStore store) {
        this(store, new SessionRevocation());
    }

    public UserServlet(MetricStore store, SessionRevocation revocation) {
        this.store      = store;
        this.revocation = revocation;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllUsers());
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            if (requireAdmin(req, resp, Msg.of("user.admin_only_create")) == null) return;

            JsonNode body    = parseBody(req, resp);
            if (body == null) return;

            String username  = requireText(body, "username", resp); if (username == null) return;
            String password  = requireText(body, "password", resp); if (password == null) return;
            String role      = optText(body, "role", ADMIN_ROLE);

            if (password.length() < 6) {
                writeError(resp, 400, Msg.of("user.password_too_short", 6));
                return;
            }

            store.addUser(username, password, role);
            resp.setStatus(201);
            writeJson(resp, Map.of("message", t("common.created"), "username", username));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            // 路徑格式：/{id} 或 /{id}/password
            String   path  = pathName(req);
            String[] parts = path == null ? new String[0] : path.split("/");
            long id = parseId(parts.length > 0 ? parts[0] : null);
            if (id < 0) { writeError(resp, 400, Msg.of("request.id_missing")); return; }
            boolean changePassword = parts.length >= 2 && "password".equals(parts[1]);

            Map<String, Object> actor = requireLogin(req, resp);
            if (actor == null) return;
            boolean self    = actorId(actor) == id;
            boolean isAdmin = ADMIN_ROLE.equals(actor.get("role"));

            // 改自己的密碼任何人皆可；其餘（改他人密碼、修改帳號資料）僅限 admin
            if (!(changePassword && self) && !isAdmin) {
                writeError(resp, 403, changePassword ? Msg.of("user.admin_only_password")
                                                     : Msg.of("user.admin_only_update"));
                return;
            }

            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            if (changePassword) {
                String newPassword = requireText(body, "password", resp);
                if (newPassword == null) return;
                if (newPassword.length() < 6) {
                    writeError(resp, 400, Msg.of("user.password_too_short", 6));
                    return;
                }
                if (self) {
                    // 自己改密碼：清除強制改密碼旗標，不踢出
                    store.changePassword(id, newPassword);
                    writeJson(resp, Map.of("message", t("user.password_updated")));
                } else {
                    // 管理員重設他人密碼：該帳號下次登入須自行修改，並踢出其現有 session
                    store.resetPassword(id, newPassword);
                    revocation.revokeUser(id);
                    writeJson(resp, Map.of("message", t("user.password_reset")));
                }
            } else {
                String  username = requireText(body, "username", resp);
                if (username == null) return;
                String  role     = optText(body, "role",    ADMIN_ROLE);
                boolean enabled  = optBool(body, "enabled", true);
                if (self && !role.equals(actor.get("role"))) {
                    writeError(resp, 400, Msg.of("user.cannot_change_own_role"));
                    return;
                }
                if (self && !enabled) {
                    writeError(resp, 400, Msg.of("user.cannot_disable_self"));
                    return;
                }
                store.updateUser(id, username, role, enabled);
                // 修改他人帳號（角色、停用、名稱）後踢出其現有 session，讓變更立即生效
                if (!self) revocation.revokeUser(id);
                writeJson(resp, Map.of("message", t("common.updated")));
            }
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            if (id < 0) { writeError(resp, 400, Msg.of("request.id_missing")); return; }
            Map<String, Object> actor = requireAdmin(req, resp, Msg.of("user.admin_only_delete"));
            if (actor == null) return;
            if (actorId(actor) == id) {
                writeError(resp, 400, Msg.of("user.cannot_delete_self"));
                return;
            }
            store.deleteUser(id);
            revocation.revokeUser(id);
            writeJson(resp, Map.of("message", t("common.deleted")));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    /** 取得登入使用者；未登入時回 401 並回傳 null */
    private Map<String, Object> requireLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> actor = sessionUser(req);
        if (actor == null || !(actor.get("id") instanceof Number)) {
            writeError(resp, 401, Msg.of("auth.not_logged_in"));
            return null;
        }
        return actor;
    }

    /** 取得登入的 admin 使用者；未登入回 401、非 admin 回 403，並回傳 null */
    private Map<String, Object> requireAdmin(HttpServletRequest req, HttpServletResponse resp,
                                             Msg forbiddenMessage) throws IOException {
        Map<String, Object> actor = requireLogin(req, resp);
        if (actor == null) return null;
        if (!ADMIN_ROLE.equals(actor.get("role"))) {
            writeError(resp, 403, forbiddenMessage);
            return null;
        }
        return actor;
    }

    private static long actorId(Map<String, Object> actor) {
        return ((Number) actor.get("id")).longValue();
    }

    /** 取得目前 Session 的登入使用者；未登入回傳 null */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sessionUser(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        Object user = session.getAttribute("user");
        return user instanceof Map ? (Map<String, Object>) user : null;
    }
}
