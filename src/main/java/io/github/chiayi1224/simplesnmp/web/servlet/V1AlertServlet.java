package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * POST /api/v1/alerts — 查詢告警記錄（新到舊）
 *
 * body（JSON，欄位均可省略）：
 * {
 *   "device":   "Router-01",
 *   "severity": "CRITICAL",   ← WARNING / CRITICAL，不合法回 400
 *   "active":   true,         ← 只回傳未解除的告警（每台設備每個指標的最後一筆，且不是 RESOLVED）
 *   "limit":    50            ← 1～1000
 * }
 *
 * 每筆回應含 severity（RESOLVED 為解除前的等級）。供外部系統以 Bearer Token 或 Session 呼叫。
 */
public class V1AlertServlet extends BaseJsonServlet {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT     = 1000;

    private final MetricStore store;

    public V1AlertServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String   device   = null;
            Severity severity = null;
            boolean  active   = false;
            int      limit    = DEFAULT_LIMIT;

            String bodyStr = req.getReader().lines()
                .collect(java.util.stream.Collectors.joining());
            if (!bodyStr.isBlank()) {
                JsonNode json = JSON.readTree(bodyStr);
                device = optText(json, "device", null);
                String rawSeverity = optText(json, "severity", null);
                if (rawSeverity != null) {
                    severity = Severity.parse(rawSeverity);
                    if (severity == null) { writeError(resp, 400, "severity 只能是 WARNING 或 CRITICAL"); return; }
                }
                active = optBool(json, "active", false);
                if (json.has("limit") && json.get("limit").isNumber()) {
                    int l = json.get("limit").asInt();
                    if (l > 0) limit = Math.min(l, MAX_LIMIT);
                }
            }

            writeJson(resp, store.queryAlerts(device, severity, active, limit));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
