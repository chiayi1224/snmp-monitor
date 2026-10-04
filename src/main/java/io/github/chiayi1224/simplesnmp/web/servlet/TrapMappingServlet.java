package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * Trap OID 對應管理 API
 *
 *   GET    /api/trap-mappings          — 取得所有對應
 *   POST   /api/trap-mappings          — 新增對應
 *   PUT    /api/trap-mappings/{id}     — 更新對應
 *   DELETE /api/trap-mappings/{id}     — 刪除對應
 *
 * POST / PUT body（JSON）：
 * {
 *   "metricName":   "cpu",
 *   "triggeredOid": "1.3.6.1.4.1.9999.1.1",
 *   "resolvedOid":  "1.3.6.1.4.1.9999.1.2",   ← 可省略（無 resolved trap 時）
 *   "description":  "CPU 使用率過高",
 *   "enabled":      true
 * }
 *
 * POST 可另帶 "fromUnknownId"：建立成功後刪除該筆未定義 Trap 記錄。
 * 觸發 OID 已被其他對應使用時回 409。
 *
 * 每次增修刪後呼叫 TrapReceiver.reloadMappings() 立即生效，無需重啟。
 */
public class TrapMappingServlet extends BaseJsonServlet {

    private final MetricStore  store;
    private final TrapReceiver trapReceiver;

    public TrapMappingServlet(MetricStore store, TrapReceiver trapReceiver) {
        this.store        = store;
        this.trapReceiver = trapReceiver;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryAllTrapMappings());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            String metricName   = requireText(body, "metricName",   resp); if (metricName   == null) return;
            String triggeredOid = requireText(body, "triggeredOid", resp); if (triggeredOid == null) return;
            String resolvedOid  = optText(body, "resolvedOid",  null);
            String description  = optText(body, "description",  "");
            long   fromUnknownId = optLong(body, "fromUnknownId", -1);

            if (store.trapTriggeredOidExists(triggeredOid, -1)) {
                writeError(resp, 409, "觸發 OID 已有對應：" + triggeredOid);
                return;
            }
            store.addTrapMapping(metricName, triggeredOid, resolvedOid, description);
            if (fromUnknownId > 0) store.deleteUnknownTrap(fromUnknownId);
            trapReceiver.reloadMappings(store.loadTrapMappings());
            resp.setStatus(201);
            writeJson(resp, Map.of("message", "新增成功", "metricName", metricName));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            if (id < 0) { writeError(resp, 400, "缺少 ID"); return; }

            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            String metricName   = requireText(body, "metricName",   resp); if (metricName   == null) return;
            String triggeredOid = requireText(body, "triggeredOid", resp); if (triggeredOid == null) return;
            String resolvedOid  = optText(body, "resolvedOid",  null);
            String description  = optText(body, "description",  "");
            boolean enabled     = optBool(body, "enabled", true);

            if (store.trapTriggeredOidExists(triggeredOid, id)) {
                writeError(resp, 409, "觸發 OID 已有對應：" + triggeredOid);
                return;
            }
            store.updateTrapMapping(id, metricName, triggeredOid, resolvedOid, description, enabled);
            trapReceiver.reloadMappings(store.loadTrapMappings());
            writeJson(resp, Map.of("message", "更新成功", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            long id = pathId(req);
            if (id < 0) { writeError(resp, 400, "缺少 ID"); return; }

            store.deleteTrapMapping(id);
            trapReceiver.reloadMappings(store.loadTrapMappings());
            writeJson(resp, Map.of("message", "刪除成功", "id", id));
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
