package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * Poll OID 管理 API
 *
 * GET    /api/poll-oids          列出所有 OID
 * POST   /api/poll-oids          新增自訂 OID
 * PUT    /api/poll-oids/{id}     更新（含 enabled 切換）
 * DELETE /api/poll-oids/{id}     刪除
 */
public class PollOidServlet extends BaseJsonServlet {

    private final MetricStore store;

    public PollOidServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllPollOids());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String oid         = text(body, "oid");
            String name        = text(body, "name");
            String description = text(body, "description", "");
            String groupName   = text(body, "groupName",   "custom");
            String pollType    = text(body, "pollType",    "SCALAR");

            if (oid == null || oid.isBlank())  { writeError(resp, 400, "oid 不可為空");  return; }
            if (name == null || name.isBlank()) { writeError(resp, 400, "name 不可為空"); return; }

            store.addPollOid(oid, name, description, groupName, pollType);
            resp.setStatus(201);
            writeJson(resp, Map.of("message", "新增成功"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, "無效的 id"); return; }
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String oid         = text(body, "oid");
            String name        = text(body, "name");
            String description = text(body, "description", "");
            String groupName   = text(body, "groupName",   "custom");
            String pollType    = text(body, "pollType",    "SCALAR");
            boolean enabled    = body.path("enabled").asBoolean(true);

            if (oid == null || oid.isBlank())  { writeError(resp, 400, "oid 不可為空");  return; }
            if (name == null || name.isBlank()) { writeError(resp, 400, "name 不可為空"); return; }

            store.updatePollOid(id, oid, name, description, groupName, pollType, enabled);
            writeJson(resp, Map.of("message", "更新成功"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, "無效的 id"); return; }
        try {
            store.deletePollOid(id);
            writeJson(resp, Map.of("message", "刪除成功"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // ─── 工具 ─────────────────────────────────────────────────────

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText().strip();
    }

    private String text(JsonNode node, String field, String def) {
        String v = text(node, field);
        return (v == null || v.isBlank()) ? def : v;
    }

}
