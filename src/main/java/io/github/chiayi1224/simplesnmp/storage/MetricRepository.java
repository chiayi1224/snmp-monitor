package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 輪詢指標歷史（cpu_metrics / mem_metrics / disk_metrics）
 */
final class MetricRepository {

    private static final Logger log = LoggerFactory.getLogger(MetricRepository.class);

    private final Db db;

    MetricRepository(Db db) {
        this.db = db;
    }

    // ─── 寫入 ──────────────────────────────────────────────────────

    void saveCpu(String deviceName, double usedPct) {
        try {
            db.update("INSERT INTO cpu_metrics(ts, device_name, used_pct) VALUES(?,?,?)",
                Db.now(), deviceName, usedPct);
        } catch (SQLException ex) { log.error("saveCpu: {}", ex.getMessage()); }
    }

    void saveMemory(String deviceName, double usedPct, long totalKb, long usedKb) {
        try {
            db.update("INSERT INTO mem_metrics(ts, device_name, used_pct, total_kb, used_kb) VALUES(?,?,?,?,?)",
                Db.now(), deviceName, usedPct, totalKb, usedKb);
        } catch (SQLException ex) { log.error("saveMemory: {}", ex.getMessage()); }
    }

    void saveDisk(String deviceName, List<DiskInfo> disks) {
        String ts = Db.now();
        List<Object[]> rows = new ArrayList<>();
        for (DiskInfo d : disks) {
            rows.add(new Object[] { ts, deviceName, d.getMountPoint(),
                d.getTotalBytes(), d.getUsedBytes(), d.getUsedPercent() });
        }
        try {
            db.batch("INSERT INTO disk_metrics(ts, device_name, mount_point, total_bytes, used_bytes, used_pct) VALUES(?,?,?,?,?,?)", rows);
        } catch (SQLException ex) { log.error("saveDisk: {}", ex.getMessage()); }
    }

    // ─── 查詢（Web API）───────────────────────────────────────────

    List<String> queryDeviceNames() {
        try {
            return db.query("SELECT DISTINCT device_name FROM cpu_metrics ORDER BY device_name",
                rs -> rs.getString("device_name"));
        } catch (SQLException ex) {
            log.error("queryDeviceNames: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    List<Map<String, Object>> queryLatestStatus() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String device : queryDeviceNames()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceName", device);
            row.put("cpu",    queryLatestCpu(device));
            row.put("memory", queryLatestMemory(device));
            row.put("disks",  queryLatestDisks(device));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> queryLatestCpu(String device) {
        try {
            return db.queryOne("SELECT ts, used_pct FROM cpu_metrics WHERE device_name=? ORDER BY ts DESC LIMIT 1",
                rs -> Map.of("usedPct", rs.getDouble("used_pct"), "ts", rs.getString("ts")), device);
        } catch (SQLException ex) {
            log.error("queryLatestCpu: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> queryLatestMemory(String device) {
        try {
            return db.queryOne("SELECT ts, used_pct, total_kb, used_kb FROM mem_metrics WHERE device_name=? ORDER BY ts DESC LIMIT 1",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("usedPct", rs.getDouble("used_pct"));
                    m.put("totalKb", rs.getLong("total_kb"));
                    m.put("usedKb",  rs.getLong("used_kb"));
                    m.put("ts",      rs.getString("ts"));
                    return m;
                }, device);
        } catch (SQLException ex) {
            log.error("queryLatestMemory: {}", ex.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> queryLatestDisks(String device) {
        String sql = "SELECT mount_point, used_pct, total_bytes, used_bytes, ts FROM disk_metrics WHERE device_name=? AND ts=(SELECT MAX(ts) FROM disk_metrics WHERE device_name=?) ORDER BY mount_point";
        try {
            return db.query(sql, rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mountPoint", rs.getString("mount_point"));
                m.put("usedPct",    rs.getDouble("used_pct"));
                m.put("totalBytes", rs.getLong("total_bytes"));
                m.put("usedBytes",  rs.getLong("used_bytes"));
                m.put("ts",         rs.getString("ts"));
                return m;
            }, device, device);
        } catch (SQLException ex) {
            log.error("queryLatestDisks: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }
}
