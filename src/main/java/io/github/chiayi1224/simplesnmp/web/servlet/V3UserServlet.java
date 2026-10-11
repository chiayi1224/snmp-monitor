package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.i18n.Msg;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * SNMPv3 Trap 使用者管理 API
 *
 * GET    /api/v3-users          列出所有使用者（不含密碼）
 * POST   /api/v3-users          新增使用者
 * PUT    /api/v3-users/{id}     更新使用者
 * DELETE /api/v3-users/{id}     刪除使用者
 *
 * 增刪改後自動呼叫 trapReceiver.reloadV3Users() 使設定立即生效。
 */
public class V3UserServlet extends BaseJsonServlet {

    private final MetricStore   store;
    private final TrapReceiver  trapReceiver;

    public V3UserServlet(MetricStore store, TrapReceiver trapReceiver) {
        this.store        = store;
        this.trapReceiver = trapReceiver;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllV3Users());
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String username     = text(body, "username");
            String authPassword = text(body, "authPassword", "");
            String authProtocol = text(body, "authProtocol", "SHA");
            String privPassword = text(body, "privPassword", "");
            String privProtocol = text(body, "privProtocol", "AES128");

            if (username == null || username.isBlank()) { writeError(resp, 400, Msg.of("request.field_required", "username")); return; }

            store.addV3User(username, authPassword, authProtocol, privPassword, privProtocol);
            trapReceiver.reloadV3Users(store.loadEnabledV3Users());
            resp.setStatus(201);
            writeJson(resp, Map.of("message", t("common.created")));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, Msg.of("request.id_invalid")); return; }
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String username     = text(body, "username");
            String authPassword = text(body, "authPassword", "");
            String authProtocol = text(body, "authProtocol", "SHA");
            String privPassword = text(body, "privPassword", "");
            String privProtocol = text(body, "privProtocol", "AES128");
            boolean enabled     = body.path("enabled").asBoolean(true);

            if (username == null || username.isBlank()) { writeError(resp, 400, Msg.of("request.field_required", "username")); return; }

            store.updateV3User(id, username, authPassword, authProtocol, privPassword, privProtocol, enabled);
            trapReceiver.reloadV3Users(store.loadEnabledV3Users());
            writeJson(resp, Map.of("message", t("common.updated")));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, Msg.of("request.id_invalid")); return; }
        try {
            store.deleteV3User(id);
            trapReceiver.reloadV3Users(store.loadEnabledV3Users());
            writeJson(resp, Map.of("message", t("common.deleted")));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText().strip();
    }
    private String text(JsonNode node, String field, String def) {
        String v = text(node, field);
        return (v == null || v.isBlank()) ? def : v;
    }
}
