package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 告警閾值設定 API
 *
 * GET    /api/admin/thresholds          列出所有閾值設定
 * POST   /api/admin/thresholds          新增自訂指標閾值（201；名稱重複 409）
 * PUT    /api/admin/thresholds/{id}     更新指定閾值
 * DELETE /api/admin/thresholds/{id}     刪除自訂指標閾值（cpu／memory／disk 回 403）
 *
 * cpu／memory／disk 為百分比且方向固定 HIGH；自訂指標可設定 direction（HIGH 超過才告警、LOW 低於才告警），
 * 值不限範圍，但恢復值必須在觸發值的安全側。
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
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathName(req) != null) { writeError(resp, 404, "找不到路徑"); return; }
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String metricName = body.path("metricName").asText("").trim();
            if (!ProfileMetric.isValidKey(metricName)) {
                writeError(resp, 400, "指標名稱 '" + metricName + "' 不合法：須為小寫英文開頭，只含小寫英數與底線，2～32 字元");
                return;
            }
            if (!ProfileMetric.isCustomKey(metricName) || store.thresholdExists(metricName)) {
                writeError(resp, 409, "指標 " + metricName + " 的閾值已存在");
                return;
            }
            Values v = Values.parse(body, true);
            if (v.error != null) { writeError(resp, 400, v.error); return; }

            long id = store.createThreshold(metricName, v.direction, v.threshold, v.rearm, v.triggerCount, v.rearmCount,
                body.path("description").asText("").trim());
            resp.setStatus(201);
            writeJson(resp, Map.of("message", "新增成功，下次 poll 立即生效", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, "無效的 id"); return; }
        try {
            String metricName = store.thresholdMetricName(id);
            if (metricName == null) { writeError(resp, 404, "找不到閾值 #" + id); return; }

            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            boolean custom = ProfileMetric.isCustomKey(metricName);
            Values v = Values.parse(body, custom);
            if (v.error != null) { writeError(resp, 400, v.error); return; }
            if (!custom && (v.threshold <= 0 || v.threshold > 100)) { writeError(resp, 400, "threshold 應在 1-100 之間"); return; }
            if (!custom && v.rearm <= 0) { writeError(resp, 400, "rearm 應大於 0"); return; }

            store.updateThreshold(id, v.direction, v.threshold, v.rearm, v.triggerCount, v.rearmCount);
            writeJson(resp, Map.of("message", "閾值已更新，下次 poll 立即生效"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, "無效的 id"); return; }
        try {
            String metricName = store.thresholdMetricName(id);
            if (metricName == null) { writeError(resp, 404, "找不到閾值 #" + id); return; }
            if (!ProfileMetric.isCustomKey(metricName)) { writeError(resp, 403, "內建閾值 " + metricName + " 不可刪除"); return; }

            store.deleteThreshold(id);
            writeJson(resp, Map.of("message", "刪除成功，指標 " + metricName + " 之後只儲存、不告警", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    /** body 中的閾值欄位；內建指標的方向固定為 HIGH */
    private record Values(ThresholdConfig.Direction direction, double threshold, double rearm,
                          int triggerCount, int rearmCount, String error) {

        static Values parse(JsonNode body, boolean allowDirection) {
            ThresholdConfig.Direction direction = allowDirection
                ? ThresholdConfig.Direction.parse(body.path("direction").asText(null))
                : ThresholdConfig.Direction.HIGH;
            double threshold    = body.path("threshold").asDouble(80.0);
            double rearm        = body.path("rearm").asDouble(70.0);
            int    triggerCount = body.path("triggerCount").asInt(3);
            int    rearmCount   = body.path("rearmCount").asInt(2);
            String error = ThresholdConfig.validate(direction, threshold, rearm, triggerCount, rearmCount);
            return new Values(direction, threshold, rearm, triggerCount, rearmCount, error);
        }
    }
}
