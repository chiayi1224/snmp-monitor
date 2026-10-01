package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 稽核日誌查詢 API
 *
 *   GET  /api/audit-logs          — 最近 100 筆
 *   POST /api/audit-logs          — 帶過濾條件查詢
 *
 * POST body（JSON，欄位均可省略）：
 * {
 *   "eventType": "LOGIN_FAIL",
 *   "actor":     "admin",
 *   "limit":     200
 * }
 */
public class AuditLogServlet extends BaseJsonServlet {

    private final MetricStore store;

    public AuditLogServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAuditLogs(null, null, 100));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String eventType = null;
            String actor     = null;
            int    limit     = 100;

            String bodyStr = req.getReader().lines()
                .collect(java.util.stream.Collectors.joining());
            if (!bodyStr.isBlank()) {
                JsonNode json = JSON.readTree(bodyStr);
                eventType = optText(json, "eventType", null);
                actor     = optText(json, "actor",     null);
                if (json.has("limit") && json.get("limit").isNumber()) {
                    int l = json.get("limit").asInt();
                    if (l > 0) limit = l;
                }
            }

            writeJson(resp, store.queryAuditLogs(eventType, actor, limit));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
