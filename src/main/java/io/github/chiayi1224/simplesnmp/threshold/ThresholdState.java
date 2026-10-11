package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.i18n.StoredText;
import io.github.chiayi1224.simplesnmp.i18n.Msg;

/**
 * 單一設備單一指標的多等級告警狀態機（存在記憶體中，閾值設定於每次評估時傳入）
 *
 * 目前等級 level：null（無告警）、WARNING、CRITICAL。
 *
 *   升級：原始等級高於目前等級，連續 triggerCount 次 → 移到這段期間每次都達到的最低等級
 *         （無告警 → TRIGGERED；已有告警 → CHANGED）
 *   恢復：連續 rearmCount 次都自目前等級恢復 →
 *         CRITICAL 依最後一次數值降為 WARNING（CHANGED）或解除；WARNING 直接解除（RESOLVED）
 *   升級與恢復的計數互斥，任一條推進時另一條歸零。
 *
 * 設定變更後若目前等級的觸發值已被移除，下一次評估直接依新設定重新定位，不套用連續次數。
 */
public class ThresholdState {

    private final String deviceName;
    private final String metricName;

    private Severity level;
    private int      escalateCount;
    private Severity escalateLevel;   // 升級期間每次都達到的最低等級
    private int      recoverCount;
    private double   lastValue;

    public ThresholdState(String deviceName, String metricName) {
        this(deviceName, metricName, null);
    }

    /** initialLevel：重新啟動後由資料庫還原的未解除等級（null 代表無告警） */
    public ThresholdState(String deviceName, String metricName, Severity initialLevel) {
        this.deviceName = deviceName;
        this.metricName = metricName;
        this.level      = initialLevel;
    }

    public AlertEvent evaluate(ThresholdConfig config, double value) {
        return evaluate(config, value, "");
    }

    /** 輸入最新的量測值，回傳需要記錄的事件（null = 無事發生）；message 會帶入產生的事件 */
    public AlertEvent evaluate(ThresholdConfig config, double value, String message) {
        lastValue = value;
        Severity raw = config.rawLevel(value);

        // 設定已不含目前等級的觸發值：直接重新定位
        if (level != null && config.thresholdFor(level) == null) {
            return relocate(config, raw, value, message);
        }

        // 升級路徑
        if (raw != null && (level == null || raw.compareTo(level) > 0)) {
            recoverCount  = 0;
            escalateCount++;
            escalateLevel = escalateLevel == null ? raw : Severity.min(escalateLevel, raw);
            if (escalateCount < config.getTriggerCount()) return null;

            Severity from = level;
            level = escalateLevel;
            resetCounts();
            double threshold = config.thresholdFor(level);
            return from == null
                ? AlertEvent.triggered(deviceName, metricName, level, value, threshold, message)
                : AlertEvent.changed(deviceName, metricName, level, value, threshold, message);
        }
        escalateCount = 0;
        escalateLevel = null;

        // 恢復路徑
        if (level == null) return null;
        if (!config.recoveredFrom(level, value)) {
            recoverCount = 0;
            return null;
        }
        if (++recoverCount < config.getRearmCount()) return null;
        recoverCount = 0;

        Severity from = level;
        if (from == Severity.CRITICAL && config.getWarning() != null && !config.recoveredFrom(Severity.WARNING, value)) {
            level = Severity.WARNING;
            return AlertEvent.changed(deviceName, metricName, level, value, config.getWarning(), message);
        }
        level = null;
        return AlertEvent.resolved(deviceName, metricName, from, value, config.recoveryValue(from), message);
    }

    private AlertEvent relocate(ThresholdConfig config, Severity raw, double value, String message) {
        Severity from = level;
        resetCounts();
        if (raw == null) {
            level = null;
            return AlertEvent.resolved(deviceName, metricName, from, value, 0.0,
                message.isEmpty() ? StoredText.encode(Msg.of("alert.threshold_changed")) : message);
        }
        level = raw;
        return AlertEvent.changed(deviceName, metricName, raw, value, config.thresholdFor(raw), message);
    }

    /** 不再有閾值（被刪除、設定關閉）：有未解除告警時回傳解除事件並歸零，否則回傳 null */
    public AlertEvent clear(String message) {
        resetCounts();
        if (level == null) return null;
        Severity from = level;
        level = null;
        return AlertEvent.resolved(deviceName, metricName, from, lastValue, 0.0, message);
    }

    /** 以外部（資料庫）的等級覆蓋目前等級，計數歸零；用於告警在別處被解除後重新同步 */
    public void restore(Severity newLevel) {
        level = newLevel;
        resetCounts();
    }

    private void resetCounts() {
        escalateCount = 0;
        escalateLevel = null;
        recoverCount  = 0;
    }

    public Severity getLevel()      { return level; }
    public double   getLastValue()  { return lastValue; }
    public String   getDeviceName() { return deviceName; }
    public String   getMetricName() { return metricName; }
}
