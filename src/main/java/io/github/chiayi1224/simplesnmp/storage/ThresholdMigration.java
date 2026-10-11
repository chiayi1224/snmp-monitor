package io.github.chiayi1224.simplesnmp.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * 全域閾值改為每台設備閾值的一次性升級轉換（design D8）
 *
 * 升級前 threshold_settings 的每一筆，為每台既有設備建立一筆 device_thresholds：
 *   觸發值 → Critical、不設定 Warning、恢復寬度 = |觸發值 − 恢復值|、方向與連續次數沿用。
 * 原本「超過觸發值告警、回到恢復值以下解除」與「Critical、無 Warning」的行為完全相同。
 * 以 app_settings 的 thresholds_migrated 標記只執行一次；threshold_settings 保留不刪，以利回滾。
 * 全新安裝時 threshold_settings 為空（不再種入預設值），只會寫入標記。
 */
final class ThresholdMigration {

    private static final Logger log = LoggerFactory.getLogger(ThresholdMigration.class);

    static final String FLAG = "thresholds_migrated";

    private final Db                 db;
    private final SettingsRepository settings;

    ThresholdMigration(Db db, SettingsRepository settings) {
        this.db       = db;
        this.settings = settings;
    }

    void runOnce() {
        if (settings.getBool(FLAG, false)) return;
        try {
            int created = db.update("""
                INSERT OR IGNORE INTO device_thresholds(device_name, metric_name, direction, warning, critical,
                                                        rearm_margin, trigger_count, rearm_count, updated)
                SELECT d.name, t.metric_name, t.direction, NULL, t.threshold,
                       ABS(t.threshold - t.rearm), t.trigger_count, t.rearm_count, ?
                FROM devices d CROSS JOIN threshold_settings t
                WHERE 1=1
                """, Db.now());
            settings.upsert(FLAG, "true", "舊版全域閾值已轉換為每台設備的閾值（請勿修改）");
            if (created > 0) log.info("Migrated global thresholds to per-device thresholds: {} rows created", created);
        } catch (SQLException ex) {
            log.error("Threshold migration failed, will retry on next start: {}", ex.getMessage());
        }
    }
}
