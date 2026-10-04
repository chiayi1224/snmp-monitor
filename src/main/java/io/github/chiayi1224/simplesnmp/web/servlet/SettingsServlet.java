package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 系統設定 API
 *
 * GET  /api/admin/settings          列出所有設定
 * PUT  /api/admin/settings/{key}    更新指定 key 的值，body：{ "value": "..." }
 *
 * 只能更新已存在的 key（不存在回 404）；已知 key 會檢查值的格式與範圍（不符回 400）。
 */
public class SettingsServlet extends BaseJsonServlet {

    /** 整數設定的允許範圍 {最小, 最大} */
    static final Map<String, int[]> INT_RANGES = Map.ofEntries(
        Map.entry("web_port",               new int[]{ 1, 65535 }),
        Map.entry("trap_port",              new int[]{ 1, 65535 }),
        Map.entry("https_port",             new int[]{ 1, 65535 }),
        Map.entry("poll_interval_seconds",  new int[]{ 10, 86400 }),
        Map.entry("snmp_retries",           new int[]{ 0, 10 }),
        Map.entry("snmp_timeout_ms",        new int[]{ 100, 60000 }),
        Map.entry("ping_timeout_ms",        new int[]{ 100, 60000 }),
        Map.entry("retention_metrics_days", new int[]{ 0, 36500 }),
        Map.entry("retention_alerts_days",  new int[]{ 0, 36500 }),
        Map.entry("retention_audit_days",   new int[]{ 0, 36500 })
    );

    /** 只接受 true / false 的設定 */
    static final java.util.Set<String> BOOLEAN_KEYS = java.util.Set.of("ping_enabled", "https_enabled");

    private final MetricStore store;

    public SettingsServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllSettings());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String key = pathName(req);
        if (key == null) { writeError(resp, 400, "請指定 key"); return; }
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String value = body.path("value").asText("").strip();
            if (value.isBlank()) { writeError(resp, 400, "value 不可為空"); return; }
            if (!store.queryAllSettings().containsKey(key)) { writeError(resp, 404, "找不到設定：" + key); return; }
            String error = validate(key, value);
            if (error != null) { writeError(resp, 400, error); return; }
            store.upsertSetting(key, value, "");
            writeJson(resp, Map.of("message", "設定已更新，部分設定需重啟才生效"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    /** 檢查已知 key 的值；合法回傳 null，否則回傳錯誤訊息 */
    static String validate(String key, String value) {
        int[] range = INT_RANGES.get(key);
        if (range != null) {
            try {
                int v = Integer.parseInt(value);
                if (v < range[0] || v > range[1]) {
                    return key + " 必須介於 " + range[0] + " 到 " + range[1] + " 之間";
                }
            } catch (NumberFormatException e) {
                return key + " 必須是整數";
            }
        }
        if (BOOLEAN_KEYS.contains(key) && !value.equals("true") && !value.equals("false")) {
            return key + " 只能是 true 或 false";
        }
        return null;
    }
}
