package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.profile.BuiltinProfiles;
import io.github.chiayi1224.simplesnmp.i18n.Messages;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
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
            if (p == null) { writeError(resp, 404, Msg.of("profile.not_found_id", path)); return; }
            writeJson(resp, toJson(p, counts));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
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
            writeError(resp, 404, Msg.of("request.path_not_found"));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    // ─── 預覽 ─────────────────────────────────────────────────────

    private void preview(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        JsonNode body = parseBody(req, resp);
        if (body == null) return;
        String deviceName = body.path("deviceName").asText("").trim();
        if (deviceName.isEmpty()) { writeError(resp, 400, Msg.of("request.field_required", "deviceName")); return; }
        DeviceConfig device = store.loadDevice(deviceName);
        if (device == null) { writeError(resp, 404, Msg.of("device.not_found", deviceName)); return; }

        MetricProfile profile;
        if (body.hasNonNull("profileId")) {
            profile = store.loadProfile(body.path("profileId").asLong());
            if (profile == null) { writeError(resp, 404, Msg.of("profile.not_found_id", body.path("profileId").asText())); return; }
        } else if (body.path("profile").isObject()) {
            profile = parseProfile(body.path("profile"), null);
            if (profile.name().isEmpty()) profile = new MetricProfile(null, t("profile.preview_name"), "", profile.matchPrefix(), false, profile.metrics());
            Msg error = profile.validate();
            if (error != null) { writeError(resp, 400, error); return; }
        } else {
            writeError(resp, 400, Msg.of("profile.preview_target_required"));
            return;
        }
        if (collector == null) { writeError(resp, 503, Msg.of("profile.preview_disabled")); return; }

        // 停用的指標也試算，方便啟用前確認
        List<ProfileMetric> all = new ArrayList<>();
        for (ProfileMetric m : profile.metrics()) all.add(new ProfileMetric(m.metricKey(), m.kind(), m.params(), m.unit(), true));
        MetricProfile trial = new MetricProfile(profile.id(), profile.name(), profile.description(), profile.matchPrefix(),
            profile.builtin(), all);

        List<ProfileCollector.MetricResult> results;
        try {
            results = ProfileCollector.calculate(trial, collector.fetch(device, trial), DiskFilter.parse(device.getDiskFilter()));
        } catch (SnmpException e) {
            writeError(resp, 502, Msg.of("device.query_failed", e.msg()));
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
                x.put("error", Messages.get(r.error()));
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
        Msg error = p.validate();
        if (error != null) { writeError(resp, 400, error); return; }
        if (store.profileNameExists(p.name(), -1)) { writeError(resp, 409, Msg.of("profile.name_exists", p.name())); return; }

        long id = store.createProfile(p);
        resp.setStatus(201);
        writeJson(resp, Map.of("message", t("common.created"), "id", id));
    }

    private void copy(long id, HttpServletResponse resp) throws Exception {
        MetricProfile src = store.loadProfile(id);
        if (src == null) { writeError(resp, 404, Msg.of("profile.not_found_id", String.valueOf(id))); return; }

        // 複本以目前語系的顯示名稱與說明為基礎（內建範本會翻譯，自訂範本即原名稱）
        String base = BuiltinProfiles.displayName(src);
        String name = t("profile.copy_name", base);
        for (int n = 2; store.profileNameExists(name, -1); n++) name = t("profile.copy_name_n", base, String.valueOf(n));
        long newId = store.createProfile(new MetricProfile(null, name, BuiltinProfiles.displayDescription(src), src.matchPrefix(),
            false, src.metrics()));
        resp.setStatus(201);
        writeJson(resp, Map.of("message", t("profile.copied"), "id", newId, "name", name));
    }

    // ─── PUT / DELETE ─────────────────────────────────────────────

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            MetricProfile existing = id < 0 ? null : store.loadProfile(id);
            if (existing == null) { writeError(resp, 404, Msg.of("profile.not_found")); return; }
            if (existing.builtin()) { writeError(resp, 403, Msg.of("profile.builtin_readonly")); return; }

            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            MetricProfile p = parseProfile(body, id);
            Msg error = p.validate();
            if (error != null) { writeError(resp, 400, error); return; }
            if (store.profileNameExists(p.name(), id)) { writeError(resp, 409, Msg.of("profile.name_exists", p.name())); return; }

            store.updateProfile(id, p);
            writeJson(resp, Map.of("message", t("common.updated"), "id", id));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            MetricProfile existing = id < 0 ? null : store.loadProfile(id);
            if (existing == null) { writeError(resp, 404, Msg.of("profile.not_found")); return; }
            if (existing.builtin()) { writeError(resp, 403, Msg.of("profile.builtin_undeletable")); return; }

            store.deleteProfile(id);
            writeJson(resp, Map.of("message", t("common.deleted"), "id", id));
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
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
        m.put("displayName",        BuiltinProfiles.displayName(p));
        m.put("displayDescription", BuiltinProfiles.displayDescription(p));
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
