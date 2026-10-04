package io.github.chiayi1224.simplesnmp.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 稽核日誌（audit_logs）
 */
final class AuditLogRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditLogRepository.class);

    private final Db db;

    AuditLogRepository(Db db) {
        this.db = db;
    }

    /** 寫入稽核日誌（不拋例外，失敗只 log） */
    void save(String eventType, String actor, String sourceIp,
              String method, String path, int status, String detail) {
        try {
            db.update("INSERT INTO audit_logs(ts, event_type, actor, source_ip, method, path, status, detail) VALUES(?,?,?,?,?,?,?,?)",
                Db.now(), eventType, actor, sourceIp, method, path, status, detail);
        } catch (SQLException ex) {
            log.error("saveAuditLog 失敗: {}", ex.getMessage());
        }
    }

    /** 刪除 cutoff 之前的稽核日誌，回傳刪除筆數 */
    int purgeBefore(String cutoff) throws SQLException {
        return db.update("DELETE FROM audit_logs WHERE ts < ?", cutoff);
    }

    /** 查詢稽核日誌；eventType 精確比對、actor 模糊比對，空值代表不篩選 */
    List<Map<String, Object>> query(String eventType, String actor, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, ts, event_type, actor, source_ip, method, path, status, detail FROM audit_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type=?"); params.add(eventType);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor LIKE ?"); params.add("%" + actor + "%");
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        params.add(limit);

        try {
            return db.query(sql.toString(), rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",        rs.getLong("id"));
                row.put("ts",        rs.getString("ts"));
                row.put("eventType", rs.getString("event_type"));
                row.put("actor",     rs.getString("actor"));
                row.put("sourceIp",  rs.getString("source_ip"));
                row.put("method",    rs.getString("method"));
                row.put("path",      rs.getString("path"));
                row.put("status",    rs.getInt("status"));
                row.put("detail",    rs.getString("detail"));
                return row;
            }, params.toArray());
        } catch (SQLException ex) {
            log.error("queryAuditLogs 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }
}
