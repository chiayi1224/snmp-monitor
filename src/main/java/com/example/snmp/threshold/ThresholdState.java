package com.example.snmp.threshold;

/**
 * 單一 device + metric 的閾值狀態機（存在記憶體中）
 *
 * 狀態轉換：
 *
 *   NORMAL
 *     │  連續 triggerCount 次 exceeded
 *     ▼
 *   TRIGGERING ──── 未達到 triggerCount ────► NORMAL（reset）
 *     │  達到 triggerCount
 *     ▼
 *   TRIGGERED（寫 DB 記錄）
 *     │  連續 rearmCount 次 rearmed
 *     ▼
 *   REARMING ──── 未達到 rearmCount ────► TRIGGERED（reset rearm計數）
 *     │  達到 rearmCount
 *     ▼
 *   NORMAL（寫 DB 記錄 RESOLVED）
 */
public class ThresholdState {

    public enum State {
        NORMAL,      // 正常
        TRIGGERING,  // 超過閾值中，還沒到 triggerCount
        TRIGGERED,   // 已觸發告警
        REARMING     // 恢復中，還沒到 rearmCount
    }

    private final String deviceName;
    private final ThresholdConfig config;

    private State state            = State.NORMAL;
    private int   exceedCount      = 0;  // 連續超過次數
    private int   rearmExceedCount = 0;  // 連續恢復次數
    private double lastValue       = 0;

    public ThresholdState(String deviceName, ThresholdConfig config) {
        this.deviceName = deviceName;
        this.config     = config;
    }

    /**
     * 輸入最新的量測值，回傳需要記錄到 DB 的事件（null = 無事發生）
     */
    public AlertEvent evaluate(double value) {
        this.lastValue = value;

        return switch (state) {
            case NORMAL, TRIGGERING -> evaluateFromNormal(value);
            case TRIGGERED, REARMING -> evaluateFromTriggered(value);
        };
    }

    // ─── 狀態機邏輯 ───────────────────────────────────────────────

    private AlertEvent evaluateFromNormal(double value) {
        if (config.isExceeded(value)) {
            exceedCount++;
            state = State.TRIGGERING;

            if (exceedCount >= config.getTriggerCount()) {
                // 連續超過次數達標 → 真正觸發
                state = State.TRIGGERED;
                exceedCount = 0;
                rearmExceedCount = 0;
                return AlertEvent.triggered(deviceName, config.getMetricName(), value, config.getThreshold());
            }
        } else {
            // 還沒觸發就恢復，reset 計數
            exceedCount = 0;
            state = State.NORMAL;
        }
        return null;
    }

    private AlertEvent evaluateFromTriggered(double value) {
        if (config.isRearmed(value)) {
            rearmExceedCount++;
            state = State.REARMING;

            if (rearmExceedCount >= config.getRearmCount()) {
                // 連續恢復次數達標 → 真正解除
                state = State.NORMAL;
                exceedCount = 0;
                rearmExceedCount = 0;
                return AlertEvent.resolved(deviceName, config.getMetricName(), value, config.getRearmValue());
            }
        } else {
            // 持續超過，reset rearm 計數
            rearmExceedCount = 0;
            state = State.TRIGGERED;
        }
        return null;
    }

    // ─── Getter ───────────────────────────────────────────────────

    public State  getState()      { return state; }
    public double getLastValue()  { return lastValue; }
    public String getDeviceName() { return deviceName; }
    public String getMetricName() { return config.getMetricName(); }
}
