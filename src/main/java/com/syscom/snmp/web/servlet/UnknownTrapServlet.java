package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * GET /api/unknown-traps
 *
 * 同時回傳兩類未識別記錄：
 *   unknownOids    — 收到但 OID 不在對應表中的 Trap
 *   unknownSources — 來源 IP 不在設備清單中的 Trap
 *
 * Response：
 * {
 *   "unknownOids": [
 *     { "id":1, "trapOid":"1.3...", "firstSeen":"...", "lastSeen":"...",
 *       "deviceName":"192.168.1.10", "sourceIp":"192.168.1.10",
 *       "varbinds":"...", "count":3 }
 *   ],
 *   "unknownSources": [
 *     { "id":1, "sourceIp":"10.0.0.99", "firstSeen":"...", "lastSeen":"...",
 *       "lastTrapOid":"1.3...", "varbinds":"...", "count":2 }
 *   ]
 * }
 */
public class UnknownTrapServlet extends BaseJsonServlet {

    private final MetricStore store;

    public UnknownTrapServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("unknownOids",    store.queryUnknownTraps());
            result.put("unknownSources", store.queryUnknownSources());
            writeJson(resp, result);
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
