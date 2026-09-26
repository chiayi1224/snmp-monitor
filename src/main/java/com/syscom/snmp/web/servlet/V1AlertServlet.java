package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * POST /api/v1/alerts — 查詢告警記錄
 *
 * body（JSON，欄位均可省略）：
 * {
 *   "device": "Router-01",
 *   "limit":  50
 * }
 *
 * 供外部系統以 Bearer Token 或 Session 呼叫。
 */
public class V1AlertServlet extends BaseJsonServlet {

    private final MetricStore store;

    public V1AlertServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String device = null;
            int    limit  = 50;

            String bodyStr = req.getReader().lines()
                .collect(java.util.stream.Collectors.joining());
            if (!bodyStr.isBlank()) {
                JsonNode json = JSON.readTree(bodyStr);
                device = optText(json, "device", null);
                if (json.has("limit") && json.get("limit").isNumber()) {
                    int l = json.get("limit").asInt();
                    if (l > 0) limit = l;
                }
            }

            writeJson(resp, store.queryAlerts(device, limit));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
