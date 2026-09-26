package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 告警閾值設定 API
 *
 * GET  /api/thresholds          列出所有閾值設定
 * PUT  /api/thresholds/{id}     更新指定閾值
 */
public class ThresholdServlet extends BaseJsonServlet {

    private final MetricStore store;

    public ThresholdServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllThresholds());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, "無效的 id"); return; }
        try {
            JsonNode body = JSON.readTree(req.getReader());
            double threshold    = body.path("threshold").asDouble(80.0);
            double rearm        = body.path("rearm").asDouble(70.0);
            int    triggerCount = body.path("triggerCount").asInt(3);
            int    rearmCount   = body.path("rearmCount").asInt(2);

            if (threshold <= 0 || threshold > 100) { writeError(resp, 400, "threshold 應在 1-100 之間"); return; }
            if (rearm <= 0 || rearm >= threshold)   { writeError(resp, 400, "rearm 應小於 threshold"); return; }
            if (triggerCount < 1 || rearmCount < 1) { writeError(resp, 400, "count 至少為 1"); return; }

            store.updateThreshold(id, threshold, rearm, triggerCount, rearmCount);
            writeJson(resp, Map.of("message", "閾值已更新，下次 poll 立即生效"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

}
