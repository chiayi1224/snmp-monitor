package com.example.snmp.web.servlet;

import com.example.snmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * GET /api/alerts
 *
 * 查詢 alert_events 記錄
 *
 * Query params：
 *   device={name}   過濾特定設備（optional）
 *   limit={n}       最多回傳幾筆，預設 50
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

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String device = req.getParameter("device");  // null = 全部
            int limit = 50;
            String limitParam = req.getParameter("limit");
            if (limitParam != null) {
                try { limit = Integer.parseInt(limitParam); } catch (NumberFormatException ignored) {}
            }
            writeJson(resp, store.queryAlerts(device, limit));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
