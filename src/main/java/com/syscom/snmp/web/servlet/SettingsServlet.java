package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 系統設定 API
 *
 * GET  /api/settings          列出所有設定
 * PUT  /api/settings/{key}    更新指定 key 的值
 */
public class SettingsServlet extends BaseJsonServlet {

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
            store.upsertSetting(key, value, "");
            writeJson(resp, Map.of("message", "設定已更新，部分設定需重啟才生效"));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
