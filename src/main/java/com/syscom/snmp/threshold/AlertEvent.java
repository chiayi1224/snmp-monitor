package com.syscom.snmp.threshold;

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
    private final String  message;      // Trap varbind 描述文字（polling 觸發時為空）

    private AlertEvent(Type type, String deviceName, String metricName,
                       double value, double thresholdVal, String message) {
        this.ts           = Instant.now();
        this.type         = type;
        this.deviceName   = deviceName;
        this.metricName   = metricName;
        this.value        = value;
        this.thresholdVal = thresholdVal;
        this.message      = message != null ? message : "";
    }

    /** Polling 觸發（無描述文字） */
    public static AlertEvent triggered(String device, String metric,
                                       double value, double threshold) {
        return new AlertEvent(Type.TRIGGERED, device, metric, value, threshold, "");
    }

    /** Trap 觸發（含描述文字） */
    public static AlertEvent triggered(String device, String metric,
                                       double value, double threshold, String message) {
        return new AlertEvent(Type.TRIGGERED, device, metric, value, threshold, message);
    }

    /** Polling 恢復（無描述文字） */
    public static AlertEvent resolved(String device, String metric,
                                      double value, double rearmValue) {
        return new AlertEvent(Type.RESOLVED, device, metric, value, rearmValue, "");
    }

    /** Trap 恢復（含描述文字） */
    public static AlertEvent resolved(String device, String metric,
                                      double value, double rearmValue, String message) {
        return new AlertEvent(Type.RESOLVED, device, metric, value, rearmValue, message);
    }

    public Instant getTs()           { return ts; }
    public String  getDeviceName()   { return deviceName; }
    public String  getMetricName()   { return metricName; }
    public Type    getType()         { return type; }
    public double  getValue()        { return value; }
    public double  getThresholdVal() { return thresholdVal; }
    public String  getMessage()      { return message; }

    @Override
    public String toString() {
        String msg = message.isBlank() ? "" : " [" + message + "]";
        return String.format("[%s] %s | %s | %s = %.1f%% (threshold=%.1f%%)%s",
            ts, deviceName, type, metricName, value, thresholdVal, msg);
    }
}
