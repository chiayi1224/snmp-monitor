package io.github.chiayi1224.simplesnmp.threshold;

/**
 * 單一指標的閾值設定
 * 對應 OpenNMS 的 threshold 定義
 */
public class ThresholdConfig {

    public enum Direction {
        HIGH,  // 值超過 threshold 才告警（CPU、Memory、Disk）
        LOW;   // 值低於 threshold 才告警（例如剩餘空間、可用埠數）

        /** 大小寫不敏感解析；null 或空白回傳 HIGH，不合法回傳 null */
        public static Direction parse(String s) {
            if (s == null || s.isBlank()) return HIGH;
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    private final String   metricName;    // 識別用，例如 "cpu", "memory", "disk:/"
    private final Direction direction;
    private final double   threshold;     // 觸發值
    private final double   rearmValue;    // 恢復值（HIGH 模式下應低於 threshold）
    private final int      triggerCount;  // 連續幾次超過才 TRIGGERED
    private final int      rearmCount;    // 連續幾次恢復才 RESOLVED

    private ThresholdConfig(Builder b) {
        this.metricName   = b.metricName;
        this.direction    = b.direction;
        this.threshold    = b.threshold;
        this.rearmValue   = b.rearmValue;
        this.triggerCount = b.triggerCount;
        this.rearmCount   = b.rearmCount;
    }

    public String    getMetricName()   { return metricName; }
    public Direction getDirection()    { return direction; }
    public double    getThreshold()    { return threshold; }
    public double    getRearmValue()   { return rearmValue; }
    public int       getTriggerCount() { return triggerCount; }
    public int       getRearmCount()   { return rearmCount; }

    /** 判斷某個值是否「超過閾值」 */
    public boolean isExceeded(double value) {
        return direction == Direction.HIGH
            ? value > threshold
            : value < threshold;
    }

    /** 判斷某個值是否「已恢復」 */
    public boolean isRearmed(double value) {
        return direction == Direction.HIGH
            ? value <= rearmValue
            : value >= rearmValue;
    }

    /** 合法回傳 null，否則回傳錯誤訊息（恢復值須在觸發值「安全的一側」） */
    public static String validate(Direction direction, double threshold, double rearm,
                                  int triggerCount, int rearmCount) {
        if (direction == null) return "direction 只能是 HIGH 或 LOW";
        if (!Double.isFinite(threshold) || !Double.isFinite(rearm)) return "threshold 與 rearm 必須是數字";
        if (direction == Direction.HIGH && rearm >= threshold) return "方向為 HIGH 時 rearm 應小於 threshold";
        if (direction == Direction.LOW && rearm <= threshold) return "方向為 LOW 時 rearm 應大於 threshold";
        if (triggerCount < 1 || rearmCount < 1) return "count 至少為 1";
        return null;
    }

    // ─── 常用預設值 ───────────────────────────────────────────────

    /** CPU 使用率超過 90%（連續 3 次），低於 80% 恢復 */
    public static ThresholdConfig cpu() {
        return new Builder("cpu")
            .threshold(90.0).rearmValue(80.0)
            .triggerCount(3).rearmCount(2)
            .build();
    }

    /** 記憶體使用率超過 85%（連續 3 次），低於 75% 恢復 */
    public static ThresholdConfig memory() {
        return new Builder("memory")
            .threshold(85.0).rearmValue(75.0)
            .triggerCount(3).rearmCount(2)
            .build();
    }

    /** 磁碟使用率超過 80%（連續 2 次），低於 70% 恢復 */
    public static ThresholdConfig disk(String mountPoint) {
        return new Builder("disk:" + mountPoint)
            .threshold(80.0).rearmValue(70.0)
            .triggerCount(2).rearmCount(2)
            .build();
    }

    // ─── Builder ──────────────────────────────────────────────────

    public static class Builder {
        private final String metricName;
        private Direction direction    = Direction.HIGH;
        private double    threshold    = 80.0;
        private double    rearmValue   = 70.0;
        private int       triggerCount = 3;
        private int       rearmCount   = 2;

        public Builder(String metricName) { this.metricName = metricName; }
        public Builder direction(Direction d)  { this.direction    = d;  return this; }
        public Builder threshold(double v)     { this.threshold    = v;  return this; }
        public Builder rearmValue(double v)    { this.rearmValue   = v;  return this; }
        public Builder triggerCount(int v)     { this.triggerCount = v;  return this; }
        public Builder rearmCount(int v)       { this.rearmCount   = v;  return this; }
        public ThresholdConfig build()         { return new ThresholdConfig(this); }
    }
}
