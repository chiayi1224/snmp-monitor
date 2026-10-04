package io.github.chiayi1224.simplesnmp.web.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.chiayi1224.simplesnmp.discovery.DiscoveryService;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OID 掃描 API
 *
 *   POST /api/admin/discovery/scans              — 啟動掃描（202 / 404 / 409）
 *        body：{ "deviceName":"SW-01", "walk":true, "subtrees":["1.3.6.1.2.1"] }
 *   GET  /api/admin/discovery/scans?device=      — 掃描清單（不含 OID 明細）
 *   GET  /api/admin/discovery/scans/{id}         — 單次掃描資訊與 MIB 探測結果
 *   GET  /api/admin/discovery/scans/{id}/oids    — OID 明細分頁
 *        query：prefix（OID 前綴）、q（值關鍵字）、offset、limit（上限 500）
 */
public class DiscoveryServlet extends BaseJsonServlet {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT     = 500;

    private static final Pattern OID_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

    private final MetricStore      store;
    private final DiscoveryService discovery;

    public DiscoveryServlet(MetricStore store, DiscoveryService discovery) {
        this.store     = store;
        this.discovery = discovery;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"scans".equals(pathName(req))) { writeError(resp, 404, "找不到路徑"); return; }
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;
            String deviceName = requireText(body, "deviceName", resp);
            if (deviceName == null) return;

            List<String> subtrees = new ArrayList<>();
            for (JsonNode n : body.path("subtrees")) {
                String oid = n.asText().trim();
                if (oid.isEmpty()) continue;
                if (!OID_PATTERN.matcher(oid).matches()) {
                    writeError(resp, 400, "子樹 OID 格式不正確：" + oid);
                    return;
                }
                subtrees.add(oid);
            }

            DiscoveryService.StartResult r = discovery.start(deviceName, optBool(body, "walk", false), subtrees);
            if (r.alreadyRunning()) {
                resp.setStatus(409);
                writeJson(resp, Map.of("error", "此設備已有進行中的掃描", "scanId", r.scanId()));
                return;
            }
            resp.setStatus(202);
            writeJson(resp, Map.of("scanId", r.scanId()));
        } catch (DiscoveryService.DeviceNotFoundException e) {
            writeError(resp, 404, e.getMessage());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = pathName(req);
            if ("scans".equals(path)) {
                String device = req.getParameter("device");
                writeJson(resp, store.queryScans(device == null || device.isBlank() ? null : device));
                return;
            }
            if (path == null || !path.startsWith("scans/")) { writeError(resp, 404, "找不到路徑"); return; }

            String[] parts = path.substring("scans/".length()).split("/");
            long id = parseId(parts[0]);
            if (id < 0 || parts.length > 2 || (parts.length == 2 && !"oids".equals(parts[1]))) {
                writeError(resp, 404, "找不到路徑");
                return;
            }
            Map<String, Object> scan = store.queryScan(id);
            if (scan == null) { writeError(resp, 404, "找不到掃描 #" + id); return; }

            if (parts.length == 1) writeScan(resp, scan);
            else writeOids(req, resp, id);
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    private void writeScan(HttpServletResponse resp, Map<String, Object> scan) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(scan);
        Object raw = out.remove("probesJson");
        out.put("probes", raw == null ? List.of() : JSON.readTree(raw.toString()));
        writeJson(resp, out);
    }

    private void writeOids(HttpServletRequest req, HttpServletResponse resp, long id) throws IOException {
        String prefix = blankToNull(req.getParameter("prefix"));
        if (prefix != null) {
            prefix = prefix.replaceAll("^\\.+|\\.+$", "");
            if (!OID_PATTERN.matcher(prefix).matches()) { writeError(resp, 400, "OID 前綴格式不正確"); return; }
        }
        int offset = Math.max(0, intParam(req, "offset", 0));
        int limit  = Math.min(MAX_LIMIT, Math.max(1, intParam(req, "limit", DEFAULT_LIMIT)));
        Map<String, Object> page = store.queryScanOids(id, prefix, blankToNull(req.getParameter("q")), offset, limit);
        page.put("offset", offset);
        page.put("limit", limit);
        writeJson(resp, page);
    }

    private static int intParam(HttpServletRequest req, String name, int def) {
        String v = req.getParameter(name);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
