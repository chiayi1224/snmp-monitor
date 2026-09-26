package com.syscom.snmp.storage;

import com.syscom.snmp.config.OidConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Poll OID 設定（poll_oids）
 */
final class PollOidRepository {

    private static final Logger log = LoggerFactory.getLogger(PollOidRepository.class);

    /** 預設 OID：{oid, name, description, groupName, pollType} */
    private static final String[][] DEFAULTS = {
        { OidConstants.SYS_UPTIME,       "sysUpTime",                "系統運行時間",       "system", "SCALAR" },
        { OidConstants.SYS_NAME,         "sysName",                  "設備名稱",           "system", "SCALAR" },
        { OidConstants.CPU_USER,         "cpuUser",                  "CPU 使用者態百分比", "cpu",    "SCALAR" },
        { OidConstants.CPU_SYSTEM,       "cpuSystem",                "CPU 核心態百分比",   "cpu",    "SCALAR" },
        { OidConstants.CPU_IDLE,         "cpuIdle",                  "CPU 閒置百分比",     "cpu",    "SCALAR" },
        { OidConstants.MEM_TOTAL,        "memTotalSwap",             "記憶體總量 (KB)",    "memory", "SCALAR" },
        { OidConstants.MEM_FREE,         "memAvailSwap",             "可用記憶體 (KB)",    "memory", "SCALAR" },
        { OidConstants.MEM_BUFFER,       "memBuffer",                "Buffer 快取 (KB)",   "memory", "SCALAR" },
        { OidConstants.MEM_CACHE,        "memCached",                "Cached 快取 (KB)",   "memory", "SCALAR" },
        { OidConstants.HR_STORAGE_TYPE,  "hrStorageType",            "磁碟類型",           "disk",   "WALK"   },
        { OidConstants.HR_STORAGE_DESCR, "hrStorageDescr",           "磁碟掛載點",         "disk",   "WALK"   },
        { OidConstants.HR_STORAGE_UNIT,  "hrStorageAllocationUnits", "分配單位 (bytes)",   "disk",   "WALK"   },
        { OidConstants.HR_STORAGE_SIZE,  "hrStorageSize",            "磁碟總量 (units)",   "disk",   "WALK"   },
        { OidConstants.HR_STORAGE_USED,  "hrStorageUsed",            "已用量 (units)",     "disk",   "WALK"   },
    };

    private final Db db;

    PollOidRepository(Db db) {
        this.db = db;
    }

    /** 首次啟動時寫入標準 Poll OID 預設值 */
    void seedDefaults() {
        try {
            if (db.queryLong("SELECT COUNT(*) FROM poll_oids") == 0) {
                for (String[] d : DEFAULTS) add(d[0], d[1], d[2], d[3], d[4]);
                log.info("已寫入預設 Poll OID {} 筆", DEFAULTS.length);
            }
        } catch (Exception ex) {
            log.error("seedDefaultPollOids 失敗: {}", ex.getMessage());
        }
    }

    /** 載入所有啟用的 SCALAR OID 字串（供 SnmpPoller GET 使用） */
    List<String> loadEnabledScalarOids() {
        String sql = "SELECT oid FROM poll_oids WHERE enabled=1 AND poll_type='SCALAR' ORDER BY group_name, name";
        try {
            return db.query(sql, rs -> rs.getString("oid"));
        } catch (SQLException ex) {
            log.error("loadEnabledScalarOids 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 磁碟 WALK 是否啟用（只要 disk group 中有任一筆啟用即視為啟用；查詢失敗時預設啟用） */
    boolean isDiskPollEnabled() {
        try {
            return db.queryLong("SELECT COUNT(*) FROM poll_oids WHERE group_name='disk' AND enabled=1") > 0;
        } catch (SQLException ex) {
            log.error("isDiskPollEnabled 失敗: {}", ex.getMessage());
            return true;
        }
    }

    /** 查詢所有 Poll OID（供 Web API 列表） */
    List<Map<String, Object>> queryAll() {
        try {
            return db.query("SELECT * FROM poll_oids ORDER BY group_name, name", rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",          rs.getLong("id"));
                row.put("oid",         rs.getString("oid"));
                row.put("name",        rs.getString("name"));
                row.put("description", rs.getString("description"));
                row.put("groupName",   rs.getString("group_name"));
                row.put("pollType",    rs.getString("poll_type"));
                row.put("enabled",     Db.bool(rs, "enabled"));
                row.put("created",     rs.getString("created"));
                row.put("updated",     rs.getString("updated"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("queryAllPollOids 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    void add(String oid, String name, String description,
             String groupName, String pollType) throws SQLException {
        String now = Db.now();
        db.update("INSERT INTO poll_oids(oid, name, description, group_name, poll_type, enabled, created, updated) VALUES(?,?,?,?,?,1,?,?)",
            oid, name, description, groupName, pollType, now, now);
    }

    void update(long id, String oid, String name, String description,
                String groupName, String pollType, boolean enabled) throws SQLException {
        db.update("UPDATE poll_oids SET oid=?, name=?, description=?, group_name=?, poll_type=?, enabled=?, updated=? WHERE id=?",
            oid, name, description, groupName, pollType, Db.flag(enabled), Db.now(), id);
    }

    void delete(long id) throws SQLException {
        db.update("DELETE FROM poll_oids WHERE id=?", id);
    }
}
