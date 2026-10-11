package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.i18n.Msg;
/**
 * 單一設備單一指標的閾值設定：Warning／Critical 兩段觸發值（至少一個）與共用的恢復寬度。
 *
 *   方向 HIGH：數值 &gt; 觸發值即超過；數值 &lt;= 觸發值 − 恢復寬度才算自該等級恢復
 *   方向 LOW ：數值 &lt; 觸發值即超過；數值 &gt;= 觸發值 + 恢復寬度才算自該等級恢復
 */
public class ThresholdConfig {

    public enum Direction {
        HIGH,  // 值超過觸發值才告警（CPU、記憶體、磁碟）
        LOW;   // 值低於觸發值才告警（例如剩餘空間、可用埠數）

        /** 大小寫不敏感解析；null 或空白回傳 HIGH，不合法回傳 null */
        public static Direction parse(String s) {
            if (s == null || s.isBlank()) return HIGH;
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    private static final String DISK_PREFIX = "disk:";

    private final String    metricName;    // 例如 "cpu"、"disk:/"、自訂指標
    private final Direction direction;
    private final Double    warning;       // null 代表不設定 Warning
    private final Double    critical;      // null 代表不設定 Critical
    private final double    rearmMargin;   // 恢復寬度
    private final int       triggerCount;  // 連續幾次超過才觸發或升級
    private final int       rearmCount;    // 連續幾次恢復才降級或解除

    private ThresholdConfig(Builder b) {
        this.metricName   = b.metricName;
        this.direction    = b.direction;
        this.warning      = b.warning;
        this.critical     = b.critical;
        this.rearmMargin  = b.rearmMargin;
        this.triggerCount = b.triggerCount;
        this.rearmCount   = b.rearmCount;
    }

    public String    getMetricName()   { return metricName; }
    public Direction getDirection()    { return direction; }
    public Double    getWarning()      { return warning; }
    public Double    getCritical()     { return critical; }
    public double    getRearmMargin()  { return rearmMargin; }
    public int       getTriggerCount() { return triggerCount; }
    public int       getRearmCount()   { return rearmCount; }

    /** 指定等級的觸發值；未設定回傳 null */
    public Double thresholdFor(Severity level) {
        return level == Severity.CRITICAL ? critical : level == Severity.WARNING ? warning : null;
    }

    /** 數值目前達到的最高等級；都未超過回傳 null */
    public Severity rawLevel(double value) {
        if (critical != null && exceeds(value, critical)) return Severity.CRITICAL;
        if (warning != null && exceeds(value, warning)) return Severity.WARNING;
        return null;
    }

    /** 數值是否已自指定等級恢復（該等級未設定時視為已恢復） */
    public boolean recoveredFrom(Severity level, double value) {
        if (thresholdFor(level) == null) return true;
        double r = recoveryValue(level);
        return direction == Direction.HIGH ? value <= r : value >= r;
    }

    /** 指定等級的恢復值（HIGH 為觸發值 − 寬度，LOW 為觸發值 + 寬度）；未設定回傳 0 */
    public double recoveryValue(Severity level) {
        Double t = thresholdFor(level);
        if (t == null) return 0.0;
        return direction == Direction.HIGH ? t - rearmMargin : t + rearmMargin;
    }

    private boolean exceeds(double value, double threshold) {
        return direction == Direction.HIGH ? value > threshold : value < threshold;
    }

    /** cpu、memory、disk 與 disk:&lt;掛載點&gt; 為百分比指標：方向固定 HIGH、觸發值限 0～100 */
    public static boolean isPercentMetric(String metricName) {
        return "cpu".equals(metricName) || "memory".equals(metricName) || "disk".equals(metricName)
            || (metricName != null && metricName.startsWith(DISK_PREFIX));
    }

    /** 合法回傳 null，否則回傳錯誤訊息（不檢查指標名稱本身是否合法） */
    public static Msg validate(String metricName, Direction direction, Double warning, Double critical,
                                  double rearmMargin, int triggerCount, int rearmCount) {
        if (direction == null) return Msg.of("threshold.direction_invalid");
        if (warning == null && critical == null) return Msg.of("threshold.level_required");
        if ((warning != null && !Double.isFinite(warning)) || (critical != null && !Double.isFinite(critical))) {
            return Msg.of("threshold.level_not_number");
        }
        if (isPercentMetric(metricName)) {
            if (direction != Direction.HIGH) return Msg.of("threshold.direction_fixed", metricName);
            if (outOfPercent(warning) || outOfPercent(critical)) return Msg.of("threshold.percent_range", metricName);
        }
        if (warning != null && critical != null) {
            if (direction == Direction.HIGH && warning >= critical) return Msg.of("threshold.warning_below_critical");
            if (direction == Direction.LOW && warning <= critical) return Msg.of("threshold.warning_above_critical");
        }
        if (!Double.isFinite(rearmMargin) || rearmMargin < 0) return Msg.of("threshold.margin_negative");
        if (triggerCount < 1 || rearmCount < 1) return Msg.of("threshold.count_invalid");
        return null;
    }

    private static boolean outOfPercent(Double v) {
        return v != null && (v <= 0 || v > 100);
    }

    // ─── Builder ──────────────────────────────────────────────────

    public static class Builder {
        private final String metricName;
        private Direction direction    = Direction.HIGH;
        private Double    warning      = null;
        private Double    critical     = null;
        private double    rearmMargin  = 0.0;
        private int       triggerCount = 1;
        private int       rearmCount   = 1;

        public Builder(String metricName) { this.metricName = metricName; }
        public Builder direction(Direction d)  { this.direction    = d;  return this; }
        public Builder warning(Double v)       { this.warning      = v;  return this; }
        public Builder critical(Double v)      { this.critical     = v;  return this; }
        public Builder rearmMargin(double v)   { this.rearmMargin  = v;  return this; }
        public Builder triggerCount(int v)     { this.triggerCount = v;  return this; }
        public Builder rearmCount(int v)       { this.rearmCount   = v;  return this; }
        public ThresholdConfig build()         { return new ThresholdConfig(this); }
    }
}
