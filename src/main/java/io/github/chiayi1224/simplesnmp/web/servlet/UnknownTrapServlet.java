package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
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
 * DELETE /api/admin/unknown-traps/oids/{id}、/api/admin/unknown-traps/sources/{id} 刪除單筆記錄
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
        if (pathName(req) != null) { writeError(resp, 404, "找不到路徑"); return; }
        try {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("unknownOids",    store.queryUnknownTraps());
            result.put("unknownSources", store.queryUnknownSources());
            writeJson(resp, result);
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    /**
     * DELETE /api/admin/unknown-traps/oids/{id}     — 刪除一筆未定義 Trap
     * DELETE /api/admin/unknown-traps/sources/{id}  — 刪除一筆未登錄來源
     */
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = pathName(req);
            String[] parts = path == null ? new String[0] : path.split("/");
            long id = parts.length == 2 ? parseId(parts[1]) : -1;
            if (id < 0 || !("oids".equals(parts[0]) || "sources".equals(parts[0]))) {
                writeError(resp, 404, "找不到路徑");
                return;
            }
            boolean deleted = "oids".equals(parts[0]) ? store.deleteUnknownTrap(id) : store.deleteUnknownSource(id);
            if (!deleted) { writeError(resp, 404, "找不到記錄 #" + id); return; }
            writeJson(resp, java.util.Map.of("message", "刪除成功", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
