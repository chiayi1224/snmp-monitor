package com.example.snmp.web.servlet;

import com.example.snmp.storage.MetricStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 設備 CRUD API
 *
 *   GET    /api/devices          — 取得所有設備
 *   POST   /api/devices          — 新增設備
 *   PUT    /api/devices/{id}     — 更新設備
 *   DELETE /api/devices/{id}     — 刪除設備
 *
 * POST / PUT body（JSON）：
 * {
 *   "name":      "Server-01",
 *   "host":      "192.168.1.10",
 *   "port":      161,
 *   "community": "public",
 *   "enabled":   true
 * }
 */
public class DeviceServlet extends BaseJsonServlet {

    private final MetricStore store;

    public DeviceServlet(MetricStore store) {
        this.store = store;
    }

    // GET /api/devices
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllDevices());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // POST /api/devices
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JsonNode body = JSON.readTree(req.getReader());
            String name      = body.get("name").asText();
            String host      = body.get("host").asText();
            int    port      = body.has("port")      ? body.get("port").asInt()        : 161;
            String community = body.has("community") ? body.get("community").asText()  : "public";

            if (name.isBlank() || host.isBlank()) {
                writeError(resp, 400, "name 和 host 為必填");
                return;
            }

            store.addDevice(name, host, port, community);
            resp.setStatus(201);
            writeJson(resp, Map.of("message", "新增成功", "name", name));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // PUT /api/devices/{id}
    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = extractId(req.getPathInfo());
            if (id < 0) { writeError(resp, 400, "缺少設備 ID"); return; }

            JsonNode body = JSON.readTree(req.getReader());
            String  name      = body.get("name").asText();
            String  host      = body.get("host").asText();
            int     port      = body.has("port")      ? body.get("port").asInt()       : 161;
            String  community = body.has("community") ? body.get("community").asText() : "public";
            boolean enabled   = !body.has("enabled")  || body.get("enabled").asBoolean();

            store.updateDevice(id, name, host, port, community, enabled);
            writeJson(resp, Map.of("message", "更新成功", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // DELETE /api/devices/{id}
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = extractId(req.getPathInfo());
            if (id < 0) { writeError(resp, 400, "缺少設備 ID"); return; }

            store.deleteDevice(id);
            writeJson(resp, Map.of("message", "刪除成功", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    /** 從 pathInfo（如 "/5"）取出 ID，失敗回 -1 */
    private long extractId(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/")) return -1;
        try {
            return Long.parseLong(pathInfo.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
