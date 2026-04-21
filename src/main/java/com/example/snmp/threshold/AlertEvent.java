package com.example.snmp.threshold;

import java.time.Instant;

/**
 * 閾值狀態變化事件（只處理 TRIGGERED / RESOLVED）
 */
public class AlertEvent {

    public enum Type {
        TRIGGERED,  // 告警發生
        RESOLVED    // 告警解除
    }

    private final Instant ts;
    private final String  deviceName;
    private final String  metricName;   // "cpu" | "memory" | "disk:/"
    private final Type    type;
    private final double  value;        // 當時的量測值（%）
    private final double  thresholdVal; // 對應的閾值

    private AlertEvent(Type type, String deviceName, String metricName,
                       double value, double thresholdVal) {
        this.ts           = Instant.now();
        this.type         = type;
        this.deviceName   = deviceName;
        this.metricName   = metricName;
        this.value        = value;
        this.thresholdVal = thresholdVal;
    }

    public static AlertEvent triggered(String device, String metric,
                                       double value, double threshold) {
        return new AlertEvent(Type.TRIGGERED, device, metric, value, threshold);
    }

    public static AlertEvent resolved(String device, String metric,
                                      double value, double rearmValue) {
        return new AlertEvent(Type.RESOLVED, device, metric, value, rearmValue);
    }

    public Instant getTs()           { return ts; }
    public String  getDeviceName()   { return deviceName; }
    public String  getMetricName()   { return metricName; }
    public Type    getType()         { return type; }
    public double  getValue()        { return value; }
    public double  getThresholdVal() { return thresholdVal; }

    @Override
    public String toString() {
        return String.format("[%s] %s | %s | %s = %.1f%% (threshold=%.1f%%)",
            ts, deviceName, type, metricName, value, thresholdVal);
    }
}


