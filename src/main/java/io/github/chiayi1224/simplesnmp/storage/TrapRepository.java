package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.config.TrapOidMapping;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trap 相關資料：OID 對應（trap_oid_mappings）、
 * 未定義 Trap（unknown_traps）、未登錄來源 IP（unknown_sources）
 */
final class TrapRepository {

    private static final Logger log = LoggerFactory.getLogger(TrapRepository.class);

    private final Db db;

    TrapRepository(Db db) {
        this.db = db;
    }

    // ─── Trap OID Mapping ─────────────────────────────────────────

    /** 首次啟動時寫入預設 Trap OID 對應 */
    void seedDefaults() {
        try {
            if (db.queryLong("SELECT COUNT(*) FROM trap_oid_mappings") == 0) {
                for (TrapOidMapping.Mapping m : TrapOidMapping.defaults()) {
                    addMapping(m.metricName(), m.triggeredOid(), m.resolvedOid(), m.description(), m.severity());
                }
                log.info("Seeded {} default trap OID mappings", TrapOidMapping.defaults().size());
            }
        } catch (SQLException ex) {
            log.error("Failed to seed default trap mappings: {}", ex.getMessage());
        }
    }

    /** 載入所有啟用的 Trap OID 對應（供 TrapReceiver 使用） */
    List<TrapOidMapping.Mapping> loadMappings() {
        String sql = "SELECT metric_name, triggered_oid, resolved_oid, description, severity FROM trap_oid_mappings WHERE enabled=1";
        try {
            return db.query(sql, rs -> new TrapOidMapping.Mapping(
                rs.getString("triggered_oid"),
                rs.getString("resolved_oid"),
                rs.getString("metric_name"),
                rs.getString("description"),
                Severity.parse(rs.getString("severity"))));
        } catch (SQLException ex) {
            log.error("Failed to load trap mappings: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 查詢所有 Trap OID 對應（含 id，供 Web API 使用） */
    List<Map<String, Object>> queryAllMappings() {
        try {
            return db.query("SELECT * FROM trap_oid_mappings ORDER BY metric_name", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("metricName",   rs.getString("metric_name"));
                row.put("triggeredOid", rs.getString("triggered_oid"));
                row.put("resolvedOid",  rs.getString("resolved_oid"));
                row.put("description",  rs.getString("description"));
                row.put("severity",     severityOf(rs.getString("severity")).name());
                row.put("enabled",      Db.bool(rs, "enabled"));
                row.put("created",      rs.getString("created"));
                row.put("updated",      rs.getString("updated"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("Failed to query trap mappings: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    void addMapping(String metricName, String triggeredOid,
                    String resolvedOid, String description, Severity severity) throws SQLException {
        String now = Db.now();
        db.update("INSERT INTO trap_oid_mappings(metric_name, triggered_oid, resolved_oid, description, severity, enabled, created, updated) VALUES(?,?,?,?,?,1,?,?)",
            metricName, triggeredOid, blankToNull(resolvedOid), description, severityOf(severity).name(), now, now);
        log.info("Added trap OID mapping: {} triggered={} severity={}", metricName, triggeredOid, severityOf(severity));
    }

    void updateMapping(long id, String metricName, String triggeredOid,
                       String resolvedOid, String description, Severity severity, boolean enabled) throws SQLException {
        db.update("UPDATE trap_oid_mappings SET metric_name=?, triggered_oid=?, resolved_oid=?, description=?, severity=?, enabled=?, updated=? WHERE id=?",
            metricName, triggeredOid, blankToNull(resolvedOid), description, severityOf(severity).name(), Db.flag(enabled), Db.now(), id);
    }

    private static Severity severityOf(Severity s) {
        return s == null ? Severity.CRITICAL : s;
    }

    private static Severity severityOf(String s) {
        return severityOf(Severity.parse(s));
    }

    void deleteMapping(long id) throws SQLException {
        db.update("DELETE FROM trap_oid_mappings WHERE id=?", id);
    }

    // ─── 未定義 Trap 探索 ─────────────────────────────────────────

    /** 記錄未定義的 Trap OID（同一 OID 只保留一筆，重複出現則更新計次與最後來源） */
    void saveUnknownTrap(String trapOid, String deviceName, String sourceIp, String varbinds) {
        String now = Db.now();
        String sql = """
            INSERT INTO unknown_traps(trap_oid, first_seen, last_seen, device_name, source_ip, varbinds, count)
            VALUES(?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(trap_oid) DO UPDATE SET
                last_seen   = excluded.last_seen,
                device_name = excluded.device_name,
                source_ip   = excluded.source_ip,
                varbinds    = excluded.varbinds,
                count       = count + 1
            """;
        try {
            db.update(sql, trapOid, now, now, deviceName, sourceIp, varbinds);
        } catch (SQLException ex) { log.error("Failed to save unknown trap: {}", ex.getMessage()); }
    }

    /** 查詢所有未定義 Trap 記錄，依最後出現時間降冪排列 */
    List<Map<String, Object>> queryUnknownTraps() {
        try {
            return db.query("SELECT * FROM unknown_traps ORDER BY last_seen DESC", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",         rs.getLong("id"));
                row.put("trapOid",    rs.getString("trap_oid"));
                row.put("firstSeen",  rs.getString("first_seen"));
                row.put("lastSeen",   rs.getString("last_seen"));
                row.put("deviceName", rs.getString("device_name"));
                row.put("sourceIp",   rs.getString("source_ip"));
                row.put("varbinds",   rs.getString("varbinds"));
                row.put("count",      rs.getInt("count"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("Failed to query unknown traps: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    // ─── 未登錄來源 IP 探索 ───────────────────────────────────────

    /** 記錄未登錄來源 IP（同一 IP 只保留一筆，重複出現則更新計次） */
    void saveUnknownSource(String sourceIp, String lastTrapOid, String varbinds) {
        String now = Db.now();
        String sql = """
            INSERT INTO unknown_sources(source_ip, first_seen, last_seen, last_trap_oid, varbinds, count)
            VALUES(?, ?, ?, ?, ?, 1)
            ON CONFLICT(source_ip) DO UPDATE SET
                last_seen     = excluded.last_seen,
                last_trap_oid = excluded.last_trap_oid,
                varbinds      = excluded.varbinds,
                count         = count + 1
            """;
        try {
            db.update(sql, sourceIp, now, now, lastTrapOid, varbinds);
        } catch (SQLException ex) { log.error("Failed to save unknown source: {}", ex.getMessage()); }
    }

    /** 查詢所有未登錄來源記錄，依最後出現時間降冪 */
    List<Map<String, Object>> queryUnknownSources() {
        try {
            return db.query("SELECT * FROM unknown_sources ORDER BY last_seen DESC", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",          rs.getLong("id"));
                row.put("sourceIp",    rs.getString("source_ip"));
                row.put("firstSeen",   rs.getString("first_seen"));
                row.put("lastSeen",    rs.getString("last_seen"));
                row.put("lastTrapOid", rs.getString("last_trap_oid"));
                row.put("varbinds",    rs.getString("varbinds"));
                row.put("count",       rs.getInt("count"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("Failed to query unknown sources: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 刪除一筆未定義 Trap 記錄；回傳是否有刪到 */
    boolean deleteUnknownTrap(long id) throws SQLException {
        return db.update("DELETE FROM unknown_traps WHERE id=?", id) > 0;
    }

    /** 刪除一筆未登錄來源記錄；回傳是否有刪到 */
    boolean deleteUnknownSource(long id) throws SQLException {
        return db.update("DELETE FROM unknown_sources WHERE id=?", id) > 0;
    }

    /** 依來源 IP 刪除未登錄來源記錄（該 IP 已新增為設備時使用） */
    void deleteUnknownSourceByIp(String sourceIp) throws SQLException {
        db.update("DELETE FROM unknown_sources WHERE source_ip=?", sourceIp);
    }

    /** 觸發 OID 是否已存在於其他對應（excludeId 為更新中的那一筆，新增時傳 -1） */
    boolean triggeredOidExists(String triggeredOid, long excludeId) throws SQLException {
        return db.queryLong("SELECT COUNT(*) FROM trap_oid_mappings WHERE triggered_oid=? AND id != ?",
            triggeredOid, excludeId) > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
