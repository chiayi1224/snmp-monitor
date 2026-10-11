package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.i18n.StoredText;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OID 掃描結果（device_scans / device_scan_results）
 */
final class DiscoveryRepository {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryRepository.class);

    private final Db db;

    DiscoveryRepository(Db db) {
        this.db = db;
    }

    /** 系統重啟時，前一次執行中斷的掃描標記為失敗 */
    void failInterruptedScans() {
        try {
            int n = db.update("UPDATE device_scans SET status='FAILED', error=?, finished=? WHERE status='RUNNING'",
                StoredText.encode(Msg.of("scan.interrupted_by_restart")), Db.now());
            if (n > 0) log.warn("{} unfinished scans marked as failed", n);
        } catch (SQLException ex) {
            log.error("Failed to mark interrupted scans: {}", ex.getMessage());
        }
    }

    long create(String deviceName, boolean walk) throws SQLException {
        db.update("INSERT INTO device_scans(device_name, started, status, progress, walk) VALUES(?,?,'RUNNING',?,?)",
            deviceName, Db.now(), StoredText.encode(Msg.of("scan.progress.queued")), Db.flag(walk));
        return db.queryLong("SELECT last_insert_rowid()");
    }

    void updateProgress(long scanId, String progress) throws SQLException {
        db.update("UPDATE device_scans SET progress=? WHERE id=?", progress, scanId);
    }

    void saveSystemInfo(long scanId, String descr, String objectId, String name, String uptime, String vendor)
            throws SQLException {
        db.update("UPDATE device_scans SET sys_descr=?, sys_object_id=?, sys_name=?, sys_uptime=?, vendor=? WHERE id=?",
            descr, objectId, name, uptime, vendor, scanId);
    }

    void saveProbes(long scanId, String probesJson) throws SQLException {
        db.update("UPDATE device_scans SET probes_json=? WHERE id=?", probesJson, scanId);
    }

    /** 批次寫入 OID；每列為 {oid, type, value} */
    void addResults(long scanId, List<String[]> rows) throws SQLException {
        if (rows.isEmpty()) return;
        List<Object[]> params = new ArrayList<>(rows.size());
        for (String[] r : rows) params.add(new Object[]{ scanId, r[0], r[1], r[2] });
        db.batch("INSERT INTO device_scan_results(scan_id, oid, type, value) VALUES(?,?,?,?)", params);
    }

    void finish(long scanId, String status, String error, boolean truncated) throws SQLException {
        db.update("""
            UPDATE device_scans SET status=?, error=?, truncated=?, finished=?, progress='',
                   oid_count=(SELECT COUNT(*) FROM device_scan_results WHERE scan_id=?)
            WHERE id=?
            """, status, error, Db.flag(truncated), Db.now(), scanId, scanId);
    }

    /** 每台設備只保留最近 keep 次掃描 */
    void prune(String deviceName, int keep) throws SQLException {
        List<Long> old = db.query(
            "SELECT id FROM device_scans WHERE device_name=? ORDER BY id DESC LIMIT -1 OFFSET ?",
            rs -> rs.getLong(1), deviceName, keep);
        for (long id : old) {
            db.update("DELETE FROM device_scan_results WHERE scan_id=?", id);
            db.update("DELETE FROM device_scans WHERE id=?", id);
        }
    }

    /** 掃描清單（不含探測結果與 OID 明細）；deviceName 為 null 時列出全部 */
    List<Map<String, Object>> list(String deviceName) {
        try {
            String sql = "SELECT * FROM device_scans" + (deviceName != null ? " WHERE device_name=?" : "") + " ORDER BY id DESC";
            return deviceName != null
                ? db.query(sql, rs -> toRow(rs, false), deviceName)
                : db.query(sql, rs -> toRow(rs, false));
        } catch (SQLException ex) {
            log.error("Failed to query scans: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 單次掃描（含 probesJson 原始字串）；查無資料回傳 null */
    Map<String, Object> get(long scanId) {
        try {
            return db.queryOne("SELECT * FROM device_scans WHERE id=?", rs -> toRow(rs, true), scanId);
        } catch (SQLException ex) {
            log.error("Failed to query scan: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * OID 明細分頁查詢。
     *
     * @param prefix OID 前綴（只含數字與點，呼叫端負責驗證）；null 代表不篩選
     * @param keyword 值的關鍵字（不分大小寫）；null 代表不篩選
     * @return {total, items}
     */
    Map<String, Object> queryOids(long scanId, String prefix, String keyword, int offset, int limit) {
        StringBuilder where = new StringBuilder(" WHERE scan_id=?");
        List<Object> params = new ArrayList<>(List.of(scanId));
        if (prefix != null) {
            where.append(" AND (oid=? OR oid LIKE ?)");
            params.add(prefix);
            params.add(prefix + ".%");
        }
        if (keyword != null) {
            where.append(" AND value LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(keyword) + "%");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("total", db.queryLong("SELECT COUNT(*) FROM device_scan_results" + where, params.toArray()));
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(limit);
            pageParams.add(offset);
            result.put("items", db.query(
                "SELECT oid, type, value FROM device_scan_results" + where + " ORDER BY rowid LIMIT ? OFFSET ?",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("oid",   rs.getString("oid"));
                    m.put("type",  rs.getString("type"));
                    m.put("value", rs.getString("value"));
                    return m;
                }, pageParams.toArray()));
        } catch (SQLException ex) {
            log.error("Failed to query scan OIDs: {}", ex.getMessage());
            result.put("total", 0L);
            result.put("items", new ArrayList<>());
        }
        return result;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Map<String, Object> toRow(ResultSet rs, boolean withProbes) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          rs.getLong("id"));
        m.put("deviceName",  rs.getString("device_name"));
        m.put("started",     rs.getString("started"));
        m.put("finished",    rs.getString("finished"));
        m.put("status",      rs.getString("status"));
        m.put("progress",    rs.getString("progress"));
        m.put("error",       rs.getString("error"));
        m.put("walk",        Db.bool(rs, "walk"));
        m.put("sysDescr",    rs.getString("sys_descr"));
        m.put("sysObjectId", rs.getString("sys_object_id"));
        m.put("sysName",     rs.getString("sys_name"));
        m.put("sysUpTime",   rs.getString("sys_uptime"));
        m.put("vendor",      rs.getString("vendor"));
        m.put("oidCount",    rs.getLong("oid_count"));
        m.put("truncated",   Db.bool(rs, "truncated"));
        if (withProbes) m.put("probesJson", rs.getString("probes_json"));
        return m;
    }
}
