package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import com.syscom.snmp.trap.TrapReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 設備管理 API
 *
 *   GET    /api/devices          — 取得所有設備
 *   GET    /api/devices/{name}   — 取得單台設備
 *   POST   /api/devices          — 新增設備
 *   POST   /api/devices/{name}   — 更新設備（body 含設備欄位）
 *                                  或刪除設備（body 含 "action":"delete"）
 *
 * 不支援 PUT / DELETE HTTP 方法，所有寫入操作皆透過 POST。
 *
 * 新增 body（JSON）：
 * {
 *   "name":      "Server-01",
 *   "host":      "192.168.1.10",
 *   "port":      161,
 *   "community": "public",
 *   "retries":   2,
 *   "timeoutMs": 3000
 * }
 *
 * 更新 body（JSON）：
 * {
 *   "host":      "192.168.1.20",
 *   "port":      161,
 *   "community": "private",
 *   "enabled":   true,
 *   "retries":   2,
 *   "timeoutMs": 3000
 * }
 *
 * 刪除 body（JSON）：
 * { "action": "delete" }
 *
 * 每次增修刪後會呼叫 TrapReceiver.reloadDevices()，
 * 確保 Trap 來源 IP → 設備名稱的對應表即時更新。
 */
public class DeviceServlet extends BaseJsonServlet {

    private final MetricStore   store;
    private final TrapReceiver  trapReceiver;

    public DeviceServlet(MetricStore store, TrapReceiver trapReceiver) {
        this.store        = store;
        this.trapReceiver = trapReceiver;
    }

    // GET /api/devices  或  GET /api/devices/{name}
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String name = pathName(req);
            if (name == null) {
                writeJson(resp, store.queryAllDevices());
            } else {
                Map<String, Object> device = store.queryDeviceByName(name);
                if (device == null) {
                    writeError(resp, 404, "找不到設備：" + name);
                } else {
                    writeJson(resp, device);
                }
            }
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // POST /api/devices          → 新增設備
    // POST /api/devices/{name}   → 更新設備，或 {"action":"delete"} 刪除設備
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String name = pathName(req);
            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            if (name == null) {
                // 新增設備
                String dName     = requireText(body, "name",      resp); if (dName == null) return;
                String host      = requireText(body, "host",      resp); if (host  == null) return;
                int    port      = optInt (body, "port",      161);
                String community = optText(body, "community", "public");
                int    retries   = optInt (body, "retries",   2);
                long   timeoutMs = optLong(body, "timeoutMs", 3000L);

                store.addDevice(dName, host, port, community, retries, timeoutMs);
                trapReceiver.reloadDevices(store.loadEnabledDevices());
                resp.setStatus(201);
                writeJson(resp, Map.of("message", "新增成功", "name", dName));

            } else {
                // 更新或刪除
                String action = optText(body, "action", "update");
                if ("delete".equals(action)) {
                    store.deleteDeviceByName(name);
                    trapReceiver.reloadDevices(store.loadEnabledDevices());
                    writeJson(resp, Map.of("message", "刪除成功", "name", name));
                } else {
                    String  host      = requireText(body, "host", resp); if (host == null) return;
                    int     port      = optInt (body, "port",      161);
                    String  community = optText(body, "community", "public");
                    boolean enabled   = optBool(body, "enabled",   true);
                    int     retries   = optInt (body, "retries",   2);
                    long    timeoutMs = optLong(body, "timeoutMs", 3000L);

                    store.updateDeviceByName(name, host, port, community, enabled, retries, timeoutMs);
                    trapReceiver.reloadDevices(store.loadEnabledDevices());
                    writeJson(resp, Map.of("message", "更新成功", "name", name));
                }
            }
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
