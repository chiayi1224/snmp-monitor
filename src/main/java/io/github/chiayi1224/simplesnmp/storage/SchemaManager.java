package io.github.chiayi1224.simplesnmp.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * 資料表建立與既有 DB 的欄位遷移
 */
final class SchemaManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);

    private static final String[] DDL = {
        // 管理帳號
        """
        CREATE TABLE IF NOT EXISTS users (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            username     TEXT    NOT NULL UNIQUE,
            password_hash TEXT   NOT NULL,
            role         TEXT    NOT NULL DEFAULT 'admin',
            enabled      INTEGER NOT NULL DEFAULT 1,
            must_change  INTEGER NOT NULL DEFAULT 0,
            created      TEXT    NOT NULL,
            updated      TEXT    NOT NULL
        )
        """,

        // 設備清單
        """
        CREATE TABLE IF NOT EXISTS devices (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            name      TEXT    NOT NULL UNIQUE,
            host      TEXT    NOT NULL,
            port      INTEGER NOT NULL DEFAULT 161,
            community TEXT    NOT NULL DEFAULT 'public',
            enabled   INTEGER NOT NULL DEFAULT 1,
            created   TEXT    NOT NULL,
            updated   TEXT    NOT NULL
        )
        """,

        """
        CREATE TABLE IF NOT EXISTS cpu_metrics (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            ts          TEXT    NOT NULL,
            device_name TEXT    NOT NULL,
            used_pct    REAL    NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_cpu ON cpu_metrics(device_name, ts)",

        """
        CREATE TABLE IF NOT EXISTS mem_metrics (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            ts          TEXT    NOT NULL,
            device_name TEXT    NOT NULL,
            used_pct    REAL    NOT NULL,
            total_kb    INTEGER NOT NULL,
            used_kb     INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_mem ON mem_metrics(device_name, ts)",

        """
        CREATE TABLE IF NOT EXISTS disk_metrics (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            ts          TEXT    NOT NULL,
            device_name TEXT    NOT NULL,
            mount_point TEXT    NOT NULL,
            total_bytes INTEGER NOT NULL,
            used_bytes  INTEGER NOT NULL,
            used_pct    REAL    NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_disk ON disk_metrics(device_name, ts)",

        """
        CREATE TABLE IF NOT EXISTS alert_events (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            ts            TEXT    NOT NULL,
            device_name   TEXT    NOT NULL,
            metric_name   TEXT    NOT NULL,
            event_type    TEXT    NOT NULL,
            value         REAL    NOT NULL,
            threshold_val REAL    NOT NULL,
            message       TEXT    NOT NULL DEFAULT ''
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_alert ON alert_events(device_name, ts)",

        """
        CREATE TABLE IF NOT EXISTS trap_oid_mappings (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            metric_name   TEXT    NOT NULL,
            triggered_oid TEXT    NOT NULL UNIQUE,
            resolved_oid  TEXT,
            description   TEXT    NOT NULL DEFAULT '',
            enabled       INTEGER NOT NULL DEFAULT 1,
            created       TEXT    NOT NULL,
            updated       TEXT    NOT NULL
        )
        """,

        // 未定義 Trap 探索表：同一 trap_oid 只保留一筆，計次並記錄最後來源
        """
        CREATE TABLE IF NOT EXISTS unknown_traps (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            trap_oid    TEXT    NOT NULL UNIQUE,
            first_seen  TEXT    NOT NULL,
            last_seen   TEXT    NOT NULL,
            device_name TEXT    NOT NULL,
            source_ip   TEXT    NOT NULL,
            varbinds    TEXT    NOT NULL DEFAULT '',
            count       INTEGER NOT NULL DEFAULT 1
        )
        """,

        // 未登錄來源 IP 探索表：同一 source_ip 只保留一筆，計次
        """
        CREATE TABLE IF NOT EXISTS unknown_sources (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            source_ip     TEXT    NOT NULL UNIQUE,
            first_seen    TEXT    NOT NULL,
            last_seen     TEXT    NOT NULL,
            last_trap_oid TEXT    NOT NULL DEFAULT '',
            varbinds      TEXT    NOT NULL DEFAULT '',
            count         INTEGER NOT NULL DEFAULT 1
        )
        """,

        // Poll OID 管理：scalar GET + disk WALK 的 OID 設定
        // group_name: system / cpu / memory / disk
        // poll_type:  SCALAR（單次 GET）/ WALK（SNMP Table Walk）
        """
        CREATE TABLE IF NOT EXISTS poll_oids (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            oid         TEXT    NOT NULL UNIQUE,
            name        TEXT    NOT NULL,
            description TEXT    NOT NULL DEFAULT '',
            group_name  TEXT    NOT NULL,
            poll_type   TEXT    NOT NULL DEFAULT 'SCALAR',
            enabled     INTEGER NOT NULL DEFAULT 1,
            created     TEXT    NOT NULL,
            updated     TEXT    NOT NULL
        )
        """,

        // 系統設定（key-value）
        """
        CREATE TABLE IF NOT EXISTS app_settings (
            key         TEXT NOT NULL PRIMARY KEY,
            value       TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            updated     TEXT NOT NULL
        )
        """,

        // 告警閾值設定
        """
        CREATE TABLE IF NOT EXISTS threshold_settings (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            metric_name   TEXT    NOT NULL UNIQUE,
            threshold     REAL    NOT NULL,
            rearm         REAL    NOT NULL,
            trigger_count INTEGER NOT NULL,
            rearm_count   INTEGER NOT NULL,
            description   TEXT    NOT NULL DEFAULT '',
            updated       TEXT    NOT NULL
        )
        """,

        // SNMPv3 Trap 使用者
        """
        CREATE TABLE IF NOT EXISTS snmp_v3_users (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            username      TEXT    NOT NULL UNIQUE,
            auth_password TEXT    NOT NULL DEFAULT '',
            auth_protocol TEXT    NOT NULL DEFAULT 'SHA',
            priv_password TEXT    NOT NULL DEFAULT '',
            priv_protocol TEXT    NOT NULL DEFAULT 'AES128',
            enabled       INTEGER NOT NULL DEFAULT 1,
            created       TEXT    NOT NULL,
            updated       TEXT    NOT NULL
        )
        """,

        // API Token
        """
        CREATE TABLE IF NOT EXISTS api_tokens (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            token        TEXT    NOT NULL UNIQUE,
            description  TEXT    NOT NULL,
            enabled      INTEGER NOT NULL DEFAULT 1,
            expires_at   TEXT,
            created_at   TEXT    NOT NULL,
            last_used_at TEXT
        )
        """,

        // 稽核日誌
        """
        CREATE TABLE IF NOT EXISTS audit_logs (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            ts         TEXT NOT NULL,
            event_type TEXT NOT NULL,
            actor      TEXT,
            source_ip  TEXT,
            method     TEXT,
            path       TEXT,
            status     INTEGER,
            detail     TEXT
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_logs(ts DESC)",

        // OID 掃描：每次掃描一筆，probes_json 存 MIB 探測結果
        """
        CREATE TABLE IF NOT EXISTS device_scans (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            device_name   TEXT    NOT NULL,
            started       TEXT    NOT NULL,
            finished      TEXT,
            status        TEXT    NOT NULL,
            progress      TEXT    NOT NULL DEFAULT '',
            error         TEXT,
            walk          INTEGER NOT NULL DEFAULT 0,
            sys_descr     TEXT,
            sys_object_id TEXT,
            sys_name      TEXT,
            sys_uptime    TEXT,
            vendor        TEXT,
            probes_json   TEXT,
            oid_count     INTEGER NOT NULL DEFAULT 0,
            truncated     INTEGER NOT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_scan_device ON device_scans(device_name, id)",

        // 掃描到的 OID（依走訪順序寫入，以 rowid 排序即為 OID 數字順序）
        """
        CREATE TABLE IF NOT EXISTS device_scan_results (
            scan_id INTEGER NOT NULL,
            oid     TEXT    NOT NULL,
            type    TEXT    NOT NULL,
            value   TEXT    NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_scan_result ON device_scan_results(scan_id, oid)",
    };

    /** 既有 DB 的欄位補丁（向下相容）；欄位已存在時 ALTER 會失敗，直接忽略 */
    private static final String[] MIGRATIONS = {
        "ALTER TABLE alert_events ADD COLUMN message TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE devices ADD COLUMN retries INTEGER NOT NULL DEFAULT 2",
        "ALTER TABLE devices ADD COLUMN timeout_ms INTEGER NOT NULL DEFAULT 3000",
        "ALTER TABLE devices ADD COLUMN status TEXT NOT NULL DEFAULT 'UNKNOWN'",
        "ALTER TABLE devices ADD COLUMN last_seen TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE devices ADD COLUMN snmp_version TEXT NOT NULL DEFAULT '2c'",
        "ALTER TABLE devices ADD COLUMN v3_user TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE devices ADD COLUMN v3_auth_protocol TEXT NOT NULL DEFAULT 'SHA'",
        "ALTER TABLE devices ADD COLUMN v3_auth_password TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE devices ADD COLUMN v3_priv_protocol TEXT NOT NULL DEFAULT 'AES128'",
        "ALTER TABLE devices ADD COLUMN v3_priv_password TEXT NOT NULL DEFAULT ''",
    };

    private final Db db;

    SchemaManager(Db db) {
        this.db = db;
    }

    void initSchema() throws SQLException {
        try (Statement st = db.connection().createStatement()) {
            for (String ddl : DDL) st.execute(ddl);
        }
    }

    void migrateSchema() {
        for (String sql : MIGRATIONS) {
            if (db.executeQuietly(sql)) log.info("schema 遷移：{}", sql);
        }
    }
}
