package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.i18n.Msg;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每台設備的告警閾值 API（沒有預設值，沒有閾值的指標只儲存、不告警）
 *
 *   GET    /api/admin/device-thresholds              每台設備的閾值筆數 { "counts": { "SW-01": 3 } }
 *   GET    /api/admin/device-thresholds?device=NAME  { "device", "thresholds": [...], "availableMetrics": [...] }
 *   PUT    /api/admin/device-thresholds              以 (device, metricName) 新增或取代
 *   DELETE /api/admin/device-thresholds/{id}         刪除一筆（未解除的告警於下一次輪詢解除）
 *   POST   /api/admin/device-thresholds/copy         { "from": "A", "to": ["B","C"] } 複製閾值與連線中斷告警設定
 *
 * PUT body：
 * {
 *   "device": "SW-01", "metricName": "cpu",          ← cpu / memory / disk / disk:&lt;掛載點&gt; / 自訂指標
 *   "direction": "HIGH",                             ← cpu、memory、disk 固定 HIGH
 *   "warning": 80, "critical": 95,                   ← 可為 null，至少一個
 *   "rearmMargin": 5, "triggerCount": 3, "rearmCount": 2
 * }
 */
public class DeviceThresholdServlet extends BaseJsonServlet {

    private static final String DISK_PREFIX = "disk:";
    private static final int MAX_METRIC_NAME = 200;

    private final MetricStore store;

    public DeviceThresholdServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            if (pathName(req) != null) { writeError(resp, 404, Msg.of("request.path_not_found")); return; }
            String device = req.getParameter("device");
            if (device == null || device.isBlank()) {
                writeJson(resp, Map.of("counts", store.countDeviceThresholds()));
                return;
            }
            Map<String, Object> d = store.queryDeviceByName(device);
            if (d == null) { writeError(resp, 404, Msg.of("device.not_found", device)); return; }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("device", device);
            out.put("thresholds", store.queryDeviceThresholds(device));
            out.put("availableMetrics", availableMetrics(device, d));
            writeJson(resp, out);
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    /** 可設定閾值的指標：cpu、memory、disk、最近一次回報的掛載點、套用範本中的自訂指標 */
    private List<String> availableMetrics(String device, Map<String, Object> deviceRow) {
        Set<String> out = new LinkedHashSet<>(List.of(ProfileMetric.CPU, ProfileMetric.MEMORY, ProfileMetric.DISK));
        for (Map<String, Object> status : store.queryLatestStatus()) {
            if (!device.equals(status.get("deviceName"))) continue;
            if (status.get("disks") instanceof List<?> disks) {
                for (Object disk : disks) {
                    if (disk instanceof Map<?, ?> m && m.get("mountPoint") != null) out.add(DISK_PREFIX + m.get("mountPoint"));
                }
            }
        }
        if (deviceRow.get("appliedProfileId") instanceof Long id) {
            MetricProfile profile = store.loadProfile(id);
            if (profile != null) {
                for (ProfileMetric m : profile.metrics()) if (m.isCustom()) out.add(m.metricKey());
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            if (pathName(req) != null) { writeError(resp, 404, Msg.of("request.path_not_found")); return; }
            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            String device = requireText(body, "device", resp); if (device == null) return;
            if (store.queryDeviceByName(device) == null) { writeError(resp, 404, Msg.of("device.not_found", device)); return; }

            String metricName = body.path("metricName").asText("").trim();
            Msg nameError = validateMetricName(metricName);
            if (nameError != null) { writeError(resp, 400, nameError); return; }

            ThresholdConfig.Direction direction = ThresholdConfig.Direction.parse(optText(body, "direction", null));
            Double warning, critical;
            try {
                warning  = nullableNumber(body, "warning");
                critical = nullableNumber(body, "critical");
            } catch (IllegalArgumentException e) {
                writeError(resp, 400, e.getMessage());
                return;
            }
            double rearmMargin  = body.path("rearmMargin").asDouble(0);
            int    triggerCount = body.path("triggerCount").asInt(3);
            int    rearmCount   = body.path("rearmCount").asInt(2);

            Msg error = ThresholdConfig.validate(metricName, direction, warning, critical, rearmMargin, triggerCount, rearmCount);
            if (error != null) { writeError(resp, 400, error); return; }

            long id = store.upsertDeviceThreshold(device, new ThresholdConfig.Builder(metricName)
                .direction(direction).warning(warning).critical(critical)
                .rearmMargin(rearmMargin).triggerCount(triggerCount).rearmCount(rearmCount).build());
            writeJson(resp, Map.of("message", t("threshold.saved"), "id", id));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = pathId(req);
        if (id < 0) { writeError(resp, 400, Msg.of("request.id_invalid")); return; }
        try {
            Map<String, Object> row = store.queryDeviceThreshold(id);
            if (row == null) { writeError(resp, 404, Msg.of("threshold.not_found", String.valueOf(id))); return; }
            store.deleteDeviceThreshold(id);
            writeJson(resp, Map.of("message", t("threshold.deleted", row.get("metricName")), "id", id));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"copy".equals(pathName(req))) { writeError(resp, 404, Msg.of("request.path_not_found")); return; }
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String from = requireText(body, "from", resp); if (from == null) return;
            if (store.queryDeviceByName(from) == null) { writeError(resp, 404, Msg.of("threshold.copy_source_not_found", from)); return; }

            List<String> to = new ArrayList<>();
            for (JsonNode n : body.path("to")) {
                String name = n.asText("").trim();
                if (!name.isEmpty() && !name.equals(from) && !to.contains(name)) to.add(name);
            }
            if (to.isEmpty()) { writeError(resp, 400, Msg.of("threshold.copy_target_required")); return; }
            for (String name : to) {
                if (store.queryDeviceByName(name) == null) { writeError(resp, 400, Msg.of("threshold.copy_target_not_found", name)); return; }
            }
            store.copyDeviceThresholds(from, to);
            writeJson(resp, Map.of("message", t("threshold.copied", to.size()), "to", to));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    /** 合法回傳 null：cpu、memory、disk、disk:&lt;掛載點&gt; 或合法的自訂指標名稱，且不是保留名稱 */
    static Msg validateMetricName(String name) {
        if (name.isEmpty()) return Msg.of("threshold.metric_required");
        if (ProfileMetric.isReservedKey(name)) return Msg.of("metric.key_reserved", name);
        if (name.startsWith(DISK_PREFIX)) {
            String mount = name.substring(DISK_PREFIX.length());
            if (mount.isBlank() || name.length() > MAX_METRIC_NAME) return Msg.of("threshold.mount_invalid", name);
            return null;
        }
        if (!ProfileMetric.isValidKey(name)) {
            return Msg.of("metric.key_invalid", name);
        }
        return null;
    }

    /** 欄位不存在或為 null 時回傳 null；不是數字時拋出 IllegalArgumentException */
    private static Double nullableNumber(JsonNode body, String field) {
        JsonNode n = body.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        if (!n.isNumber()) throw new IllegalArgumentException(t("threshold.value_not_number", field));
        return n.asDouble();
    }
}
