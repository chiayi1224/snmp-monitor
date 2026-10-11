package io.github.chiayi1224.simplesnmp.web.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.DiskFilter;
import io.github.chiayi1224.simplesnmp.profile.MetricKind;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.MetricValue;
import io.github.chiayi1224.simplesnmp.profile.ProfileCollector;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.snmp.SnmpException;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 監控範本 API
 *
 *   GET    /api/admin/profiles             — 範本清單（含指標與套用設備數）
 *   GET    /api/admin/profiles/{id}        — 單一範本
 *   POST   /api/admin/profiles             — 新增（201；名稱重複 409；內容不合法 400）
 *   PUT    /api/admin/profiles/{id}        — 整份更新（內建範本 403）
 *   DELETE /api/admin/profiles/{id}        — 刪除（內建範本 403；手動指定此範本的設備改回自動）
 *   POST   /api/admin/profiles/{id}/copy   — 複製（201，名稱加上「（複本）」）
 *   POST   /api/admin/profiles/preview     — 對設備試算一次（不儲存、不告警）
 *
 * 範本 body：
 * {
 *   "name": "Switch-X", "description": "...", "matchPrefix": "1.3.6.1.4.1.99999.1",
 *   "metrics": [
 *     { "metricKey": "cpu", "kind": "VALUE", "params": { "oid": "1.3.6.1..." }, "unit": "%", "enabled": true }
 *   ]
 * }
 *
 * 預覽 body：{ "deviceName": "SW-01", "profileId": 3 } 或 { "deviceName": "SW-01", "profile": { 範本內容（可未儲存）} }
 * 預覽包含停用的指標，回應逐指標列出數值或失敗原因。
 */
public class ProfileServlet extends BaseJsonServlet {

    protected final MetricStore      store;
    private   final ProfileCollector collector;

    public ProfileServlet(MetricStore store) {
        this(store, null);
    }

    /** @param collector 預覽用；null 時預覽回 503 */
    public ProfileServlet(MetricStore store, ProfileCollector collector) {
        this.store     = store;
        this.collector = collector;
    }

    // ─── GET ──────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Map<Long, Long> counts = deviceCounts();
            String path = pathName(req);
            if (path == null) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (MetricProfile p : store.loadProfiles()) out.add(toJson(p, counts));
                writeJson(resp, out);
                return;
            }
            MetricProfile p = store.loadProfile(parseId(path));
            if (p == null) { writeError(resp, 404, "找不到範本：" + path); return; }
            writeJson(resp, toJson(p, counts));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // ─── POST：新增 / 複製 ────────────────────────────────────────

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = pathName(req);
            if (path == null) { create(req, resp); return; }
            if ("preview".equals(path)) { preview(req, resp); return; }
            String[] parts = path.split("/");
            if (parts.length == 2 && "copy".equals(parts[1])) { copy(parseId(parts[0]), resp); return; }
            writeError(resp, 404, "找不到路徑");
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // ─── 預覽 ─────────────────────────────────────────────────────

    private void preview(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        JsonNode body = parseBody(req, resp);
        if (body == null) return;
        String deviceName = body.path("deviceName").asText("").trim();
        if (deviceName.isEmpty()) { writeError(resp, 400, "請指定 deviceName"); return; }
        DeviceConfig device = store.loadDevice(deviceName);
        if (device == null) { writeError(resp, 404, "找不到設備：" + deviceName); return; }

        MetricProfile profile;
        if (body.hasNonNull("profileId")) {
            profile = store.loadProfile(body.path("profileId").asLong());
            if (profile == null) { writeError(resp, 404, "找不到範本 #" + body.path("profileId").asText()); return; }
        } else if (body.path("profile").isObject()) {
            profile = parseProfile(body.path("profile"), null);
            if (profile.name().isEmpty()) profile = new MetricProfile(null, "（預覽）", "", profile.matchPrefix(), false, profile.metrics());
            String error = profile.validate();
            if (error != null) { writeError(resp, 400, error); return; }
        } else {
            writeError(resp, 400, "請指定 profileId 或 profile");
            return;
        }
        if (collector == null) { writeError(resp, 503, "預覽功能未啟用"); return; }

        // 停用的指標也試算，方便啟用前確認
        List<ProfileMetric> all = new ArrayList<>();
        for (ProfileMetric m : profile.metrics()) all.add(new ProfileMetric(m.metricKey(), m.kind(), m.params(), m.unit(), true));
        MetricProfile trial = new MetricProfile(profile.id(), profile.name(), profile.description(), profile.matchPrefix(),
            profile.builtin(), all);

        List<ProfileCollector.MetricResult> results;
        try {
            results = ProfileCollector.calculate(trial, collector.fetch(device, trial), DiskFilter.parse(device.getDiskFilter()));
        } catch (SnmpException e) {
            writeError(resp, 502, "設備無法查詢：" + e.getMessage());
            return;
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            ProfileCollector.MetricResult r = results.get(i);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("metricKey", r.metric().metricKey());
            x.put("kind",      r.metric().kind().name());
            x.put("unit",      r.metric().unit());
            x.put("enabled",   profile.metrics().get(i).enabled());
            x.put("ok",        r.ok());
            if (r.ok()) {
                List<Map<String, Object>> values = new ArrayList<>();
                for (MetricValue v : r.values()) {
                    Map<String, Object> mv = new LinkedHashMap<>();
                    mv.put("instance",   v.instance());
                    mv.put("value",      v.value());
                    mv.put("totalBytes", v.totalBytes());
                    mv.put("usedBytes",  v.usedBytes());
                    values.add(mv);
                }
                x.put("values", values);
            } else {
                x.put("error", r.error());
            }
            out.add(x);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceName",  deviceName);
        result.put("profileName", profile.name());
        result.put("results",     out);
        writeJson(resp, result);
    }

    private void create(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        JsonNode body = parseBody(req, resp);
        if (body == null) return;
        MetricProfile p = parseProfile(body, null);
        String error = p.validate();
        if (error != null) { writeError(resp, 400, error); return; }
        if (store.profileNameExists(p.name(), -1)) { writeError(resp, 409, "範本名稱已存在：" + p.name()); return; }

        long id = store.createProfile(p);
        resp.setStatus(201);
        writeJson(resp, Map.of("message", "新增成功", "id", id));
    }

    private void copy(long id, HttpServletResponse resp) throws Exception {
        MetricProfile src = store.loadProfile(id);
        if (src == null) { writeError(resp, 404, "找不到範本 #" + id); return; }

        String name = src.name() + "（複本）";
        for (int n = 2; store.profileNameExists(name, -1); n++) name = src.name() + "（複本 " + n + "）";
        long newId = store.createProfile(new MetricProfile(null, name, src.description(), src.matchPrefix(),
            false, src.metrics()));
        resp.setStatus(201);
        writeJson(resp, Map.of("message", "複製成功", "id", newId, "name", name));
    }

    // ─── PUT / DELETE ─────────────────────────────────────────────

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            MetricProfile existing = id < 0 ? null : store.loadProfile(id);
            if (existing == null) { writeError(resp, 404, "找不到範本"); return; }
            if (existing.builtin()) { writeError(resp, 403, "內建範本不可修改，請先複製再編輯"); return; }

            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            MetricProfile p = parseProfile(body, id);
            String error = p.validate();
            if (error != null) { writeError(resp, 400, error); return; }
            if (store.profileNameExists(p.name(), id)) { writeError(resp, 409, "範本名稱已存在：" + p.name()); return; }

            store.updateProfile(id, p);
            writeJson(resp, Map.of("message", "更新成功", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            MetricProfile existing = id < 0 ? null : store.loadProfile(id);
            if (existing == null) { writeError(resp, 404, "找不到範本"); return; }
            if (existing.builtin()) { writeError(resp, 403, "內建範本不可刪除"); return; }

            store.deleteProfile(id);
            writeJson(resp, Map.of("message", "刪除成功", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // ─── 轉換 ─────────────────────────────────────────────────────

    /** 由 request body 組出範本（不做驗證） */
    static MetricProfile parseProfile(JsonNode body, Long id) {
        List<ProfileMetric> metrics = new ArrayList<>();
        for (JsonNode m : body.path("metrics")) {
            Map<String, String> params = new LinkedHashMap<>();
            m.path("params").fields().forEachRemaining(e -> {
                String v = e.getValue().asText().trim();
                if (!v.isEmpty()) params.put(e.getKey(), v);
            });
            metrics.add(new ProfileMetric(
                m.path("metricKey").asText("").trim(),
                MetricKind.parse(m.path("kind").asText(null)),
                params,
                m.path("unit").asText("").trim(),
                m.path("enabled").asBoolean(true)));
        }
        return new MetricProfile(id, body.path("name").asText("").trim(), body.path("description").asText("").trim(),
            body.path("matchPrefix").asText("").trim(), false, metrics);
    }

    static Map<String, Object> toJson(MetricProfile p, Map<Long, Long> deviceCounts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          p.id());
        m.put("name",        p.name());
        m.put("description", p.description());
        m.put("matchPrefix", p.matchPrefix());
        m.put("builtin",     p.builtin());
        m.put("deviceCount", deviceCounts.getOrDefault(p.id(), 0L));
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (ProfileMetric pm : p.metrics()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("metricKey", pm.metricKey());
            x.put("kind",      pm.kind() == null ? null : pm.kind().name());
            x.put("params",    pm.params());
            x.put("unit",      pm.unit());
            x.put("enabled",   pm.enabled());
            metrics.add(x);
        }
        m.put("metrics", metrics);
        return m;
    }

    /** 範本編號 → 實際套用的設備數 */
    private Map<Long, Long> deviceCounts() {
        Map<Long, Long> counts = new HashMap<>();
        Function<Object, Long> asLong = o -> o == null ? null : ((Number) o).longValue();
        for (Map<String, Object> d : store.queryAllDevices()) {
            Long id = asLong.apply(d.get("appliedProfileId"));
            if (id != null) counts.merge(id, 1L, Long::sum);
        }
        return counts;
    }
}
