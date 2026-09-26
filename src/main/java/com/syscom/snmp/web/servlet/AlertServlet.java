package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 告警查詢 API
 *
 *   GET  /api/alerts                          — 查詢最近 50 筆告警（無過濾）
 *   POST /api/alerts                          — 查詢告警，以 JSON body 傳遞過濾條件
 *
 * POST body（JSON，欄位均可省略）：
 * {
 *   "device": "Server-01",   ← 過濾特定設備（省略 = 全部）
 *   "limit":  50             ← 最多回傳幾筆（省略 = 50）
 * }
 *
 * Response 範例：
 * [
 *   {
 *     "id": 5,
 *     "ts": "2026-03-28T10:04:00Z",
 *     "deviceName": "Server-01",
 *     "metricName": "cpu",
 *     "eventType": "TRIGGERED",
 *     "value": 91.0,
 *     "thresholdVal": 90.0
 *   }
 * ]
 */
public class AlertServlet extends BaseJsonServlet {

    private final MetricStore store;

    public AlertServlet(MetricStore store) {
        this.store = store;
    }

    /** GET /api/alerts — 不帶過濾，回傳最近 50 筆 */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAlerts(null, 50));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    /** POST /api/alerts — 以 JSON body 傳遞 device / limit 過濾條件 */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String device = null;
            int    limit  = 50;

            String bodyStr = req.getReader().lines()
                .collect(java.util.stream.Collectors.joining());

            if (!bodyStr.isBlank()) {
                JsonNode json = JSON.readTree(bodyStr);
                if (json.has("device") && json.get("device").isTextual()) {
                    String d = json.get("device").asText();
                    if (!d.isBlank()) device = d;
                }
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
