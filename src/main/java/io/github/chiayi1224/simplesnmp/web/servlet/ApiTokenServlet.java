package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.i18n.Msg;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * API Token 管理
 *
 *   GET    /api/tokens          — 列出所有 token（不含 token 明文）
 *   POST   /api/tokens          — 產生新 token（回傳一次性明文）
 *   POST   /api/tokens/{id}     — 更新描述/啟用狀態 或 {"action":"delete"} 刪除
 *
 * POST /api/tokens body：
 * { "description": "Grafana 查詢用", "expiresAt": "2027-01-01T00:00:00Z" }
 *
 * POST /api/tokens/{id} 更新 body：
 * { "description": "新描述", "enabled": true, "expiresAt": null }
 *
 * POST /api/tokens/{id} 刪除 body：
 * { "action": "delete" }
 */
public class ApiTokenServlet extends BaseJsonServlet {

    private final MetricStore store;

    public ApiTokenServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllApiTokens());
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            if (id < 0) {
                // 新增 token
                String description = requireText(body, "description", resp);
                if (description == null) return;
                String expiresAt = optText(body, "expiresAt", null);

                String token = store.createApiToken(description, expiresAt);
                resp.setStatus(201);
                writeJson(resp, Map.of(
                    "message",     t("token.created"),
                    "token",       token,
                    "description", description
                ));
            } else {
                // 更新或刪除
                String action = optText(body, "action", "update");
                if ("delete".equals(action)) {
                    store.deleteApiToken(id);
                    writeJson(resp, Map.of("message", t("common.deleted")));
                } else {
                    String  description = requireText(body, "description", resp);
                    if (description == null) return;
                    boolean enabled     = optBool(body, "enabled", true);
                    String  expiresAt   = optText(body, "expiresAt", null);
                    store.updateApiToken(id, description, enabled, expiresAt);
                    writeJson(resp, Map.of("message", t("common.updated")));
                }
            }
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }
}
