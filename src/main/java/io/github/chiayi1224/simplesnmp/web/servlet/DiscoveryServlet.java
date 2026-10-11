package io.github.chiayi1224.simplesnmp.web.servlet;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.chiayi1224.simplesnmp.i18n.StoredText;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
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
        if (!"scans".equals(pathName(req))) { writeError(resp, 404, Msg.of("request.path_not_found")); return; }
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
                    writeError(resp, 400, Msg.of("scan.subtree_invalid", oid));
                    return;
                }
                subtrees.add(oid);
            }

            DiscoveryService.StartResult r = discovery.start(deviceName, optBool(body, "walk", false), subtrees);
            if (r.alreadyRunning()) {
                resp.setStatus(409);
                writeJson(resp, Map.of("error", t("scan.already_running"), "scanId", r.scanId()));
                return;
            }
            resp.setStatus(202);
            writeJson(resp, Map.of("scanId", r.scanId()));
        } catch (DiscoveryService.DeviceNotFoundException e) {
            writeError(resp, 404, e.getMessage());
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = pathName(req);
            if ("scans".equals(path)) {
                String device = req.getParameter("device");
                List<Map<String, Object>> scans = new ArrayList<>();
                for (Map<String, Object> s : store.queryScans(device == null || device.isBlank() ? null : device)) {
                    Map<String, Object> copy = new LinkedHashMap<>(s);
                    localizeScan(copy);
                    scans.add(copy);
                }
                writeJson(resp, scans);
                return;
            }
            if (path == null || !path.startsWith("scans/")) { writeError(resp, 404, Msg.of("request.path_not_found")); return; }

            String[] parts = path.substring("scans/".length()).split("/");
            long id = parseId(parts[0]);
            if (id < 0 || parts.length > 2 || (parts.length == 2 && !"oids".equals(parts[1]))) {
                writeError(resp, 404, Msg.of("request.path_not_found"));
                return;
            }
            Map<String, Object> scan = store.queryScan(id);
            if (scan == null) { writeError(resp, 404, Msg.of("scan.not_found", String.valueOf(id))); return; }

            if (parts.length == 1) writeScan(resp, scan);
            else writeOids(req, resp, id);
        } catch (Exception e) {
            writeError(resp, 500, reason(e));
        }
    }

    private void writeScan(HttpServletResponse resp, Map<String, Object> scan) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>(scan);
        localizeScan(out);
        Object raw = out.remove("probesJson");
        JsonNode probes = raw == null ? JSON.createArrayNode() : JSON.readTree(raw.toString());
        for (JsonNode p : probes) {
            if (!(p instanceof ObjectNode o)) continue;
            localizeField(o, "name");
            localizeField(o, "error");
            if (o.get("samples") instanceof ArrayNode samples) {
                for (int i = 0; i < samples.size(); i++) {
                    if (samples.get(i).isTextual()) samples.set(i, StoredText.localize(samples.get(i).asText()));
                }
            }
        }
        out.put("probes", probes);
        writeJson(resp, out);
    }

    /** 掃描的進度與錯誤：系統產生的文字依請求語系翻譯 */
    private static void localizeScan(Map<String, Object> scan) {
        for (String f : List.of("progress", "error")) {
            if (scan.get(f) instanceof String s) scan.put(f, StoredText.localize(s));
        }
    }

    private static void localizeField(ObjectNode o, String field) {
        if (o.get(field) != null && o.get(field).isTextual()) o.put(field, StoredText.localize(o.get(field).asText()));
    }

    private void writeOids(HttpServletRequest req, HttpServletResponse resp, long id) throws IOException {
        String prefix = blankToNull(req.getParameter("prefix"));
        if (prefix != null) {
            prefix = prefix.replaceAll("^\\.+|\\.+$", "");
            if (!OID_PATTERN.matcher(prefix).matches()) { writeError(resp, 400, Msg.of("scan.prefix_invalid")); return; }
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
