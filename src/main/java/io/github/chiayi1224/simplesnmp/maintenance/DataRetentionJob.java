package io.github.chiayi1224.simplesnmp.maintenance;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 資料保存期限：每天清除超過保留天數的指標、告警與稽核日誌。
 *
 * 保留天數每次執行時從設定讀取（修改後下一次執行即生效）：
 *   retention_metrics_days — CPU / 記憶體 / 磁碟指標（預設 90）
 *   retention_alerts_days  — 告警記錄（預設 365；各指標最後一筆狀態一律保留）
 *   retention_audit_days   — 稽核日誌（預設 365）
 * 設為 0 或負數代表永久保留。
 *
 * 刪除後的空間會由 SQLite 重複利用，DB 檔案大小不會縮小（不執行 VACUUM，避免長時間鎖住資料庫）。
 */
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    static final String METRICS_SETTING = "retention_metrics_days";
    static final String ALERTS_SETTING  = "retention_alerts_days";
    static final String AUDIT_SETTING   = "retention_audit_days";

    static final int DEFAULT_METRICS_DAYS = 90;
    static final int DEFAULT_ALERTS_DAYS  = 365;
    static final int DEFAULT_AUDIT_DAYS   = 365;

    /** 啟動後第一次執行的延遲，避免和啟動時的其他工作搶資源 */
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(5);
    private static final Duration PERIOD        = Duration.ofDays(1);

    /** 單次執行的結果（-1 代表該類資料設定為永久保留而略過） */
    public record Result(int metrics, int alerts, int auditLogs) {}

    private final MetricStore store;
    private final Clock       clock;
    private ScheduledExecutorService scheduler;

    public DataRetentionJob(MetricStore store) {
        this(store, Clock.systemUTC());
    }

    DataRetentionJob(MetricStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "data-retention");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runSafely,
            INITIAL_DELAY.toMinutes(), PERIOD.toMinutes(), TimeUnit.MINUTES);
        log.info("Data retention job scheduled: first run in {} minutes, then daily", INITIAL_DELAY.toMinutes());
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (Exception e) {
            // 排程中的例外若不攔截，ScheduledExecutorService 會停止之後所有執行
            log.error("Data retention purge failed: {}", e.getMessage(), e);
        }
    }

    /** 依目前設定執行一次清除 */
    public Result runOnce() throws SQLException {
        Instant now = clock.instant();
        int metrics = purge(METRICS_SETTING, DEFAULT_METRICS_DAYS, now, store::purgeMetricsBefore);
        int alerts  = purge(ALERTS_SETTING,  DEFAULT_ALERTS_DAYS,  now, store::purgeAlertsBefore);
        int audit   = purge(AUDIT_SETTING,   DEFAULT_AUDIT_DAYS,   now, store::purgeAuditLogsBefore);
        log.info("Data retention purge finished: metrics {}, alerts {}, audit logs {}",
            describe(metrics), describe(alerts), describe(audit));
        return new Result(metrics, alerts, audit);
    }

    @FunctionalInterface
    private interface Purge {
        int before(Instant cutoff) throws SQLException;
    }

    private int purge(String setting, int defaultDays, Instant now, Purge purge) throws SQLException {
        int days = store.getSettingInt(setting, defaultDays);
        if (days <= 0) return -1;
        return purge.before(now.minus(Duration.ofDays(days)));
    }

    private static String describe(int n) {
        return n < 0 ? "(kept forever)" : String.valueOf(n);
    }
}
