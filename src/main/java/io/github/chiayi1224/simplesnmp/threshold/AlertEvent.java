package io.github.chiayi1224.simplesnmp.threshold;

import java.time.Instant;

/**
 * 告警事件：發生（TRIGGERED）、等級改變（CHANGED）、解除（RESOLVED），每筆都帶等級。
 * RESOLVED 的等級為解除前的等級。
 */
public class AlertEvent {

    public enum Type {
        TRIGGERED,  // 告警發生
        CHANGED,    // 等級改變（升級或降級）
        RESOLVED    // 告警解除
    }

    private final Instant  ts;
    private final String   deviceName;
    private final String   metricName;   // "cpu" | "memory" | "disk:/" | 自訂指標 | "device_unreachable"
    private final Type     type;
    private final Severity severity;
    private final double   value;        // 當時的量測值
    private final double   thresholdVal; // 對應的觸發值或恢復值
    private final String   message;      // 描述文字（Trap varbind、連線中斷原因等）

    private AlertEvent(Type type, Severity severity, String deviceName, String metricName,
                       double value, double thresholdVal, String message) {
        this.ts           = Instant.now();
        this.type         = type;
        this.severity     = severity;
        this.deviceName   = deviceName;
        this.metricName   = metricName;
        this.value        = value;
        this.thresholdVal = thresholdVal;
        this.message      = message != null ? message : "";
    }

    public static AlertEvent triggered(String device, String metric, Severity severity,
                                       double value, double threshold, String message) {
        return new AlertEvent(Type.TRIGGERED, severity, device, metric, value, threshold, message);
    }

    public static AlertEvent changed(String device, String metric, Severity severity,
                                     double value, double threshold, String message) {
        return new AlertEvent(Type.CHANGED, severity, device, metric, value, threshold, message);
    }

    public static AlertEvent resolved(String device, String metric, Severity severity,
                                      double value, double threshold, String message) {
        return new AlertEvent(Type.RESOLVED, severity, device, metric, value, threshold, message);
    }

    public Instant  getTs()           { return ts; }
    public String   getDeviceName()   { return deviceName; }
    public String   getMetricName()   { return metricName; }
    public Type     getType()         { return type; }
    public Severity getSeverity()     { return severity; }
    public double   getValue()        { return value; }
    public double   getThresholdVal() { return thresholdVal; }
    public String   getMessage()      { return message; }

    @Override
    public String toString() {
        String msg = message.isBlank() ? "" : " [" + message + "]";
        return String.format("[%s] %s | %s %s | %s = %.1f (threshold=%.1f)%s",
            ts, deviceName, type, severity, metricName, value, thresholdVal, msg);
    }
}
