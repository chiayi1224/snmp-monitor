package com.syscom.snmp.threshold;

/**
 * 單一指標的閾值設定
 * 對應 OpenNMS 的 threshold 定義
 */
public class ThresholdConfig {

    public enum Direction {
        HIGH,  // 值超過 threshold 才告警（CPU、Memory、Disk）
        LOW    // 值低於 threshold 才告警（預留，例如剩餘空間）
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
