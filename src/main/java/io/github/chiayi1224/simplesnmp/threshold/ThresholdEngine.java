package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 閾值引擎
 *
 * 每次 poll 後呼叫對應的 evaluate 方法：
 *  - evaluateCpu()    傳入 cpu 使用率 %
 *  - evaluateMemory() 傳入 memory 使用率 %
 *  - evaluateDisks()  傳入 DiskInfo 清單
 *  - evaluate()       自訂指標；沒有設定閾值時略過（只儲存、不告警）
 *
 * 每次評估都重新讀取閾值設定，修改後下次 poll 立即生效。狀態改變才寫 DB，不重複。
 */
public class ThresholdEngine {

    private static final Logger log = LoggerFactory.getLogger(ThresholdEngine.class);

    private final MetricStore store;

    // key = "deviceName::metricName"，例如 "Server-01::cpu"
    private final Map<String, ThresholdState> states = new HashMap<>();

    public ThresholdEngine(MetricStore store) {
        this.store = store;
    }

    // ─── CPU ──────────────────────────────────────────────────────

    public void evaluateCpu(String deviceName, double cpuUsedPct) {
        evaluate(deviceName, store.loadThresholdConfig("cpu"), cpuUsedPct);
    }

    // ─── Memory ───────────────────────────────────────────────────

    public void evaluateMemory(String deviceName, double memUsedPct) {
        evaluate(deviceName, store.loadThresholdConfig("memory"), memUsedPct);
    }

    // ─── Disk ─────────────────────────────────────────────────────

    public void evaluateDisks(String deviceName, List<DiskInfo> disks) {
        for (DiskInfo disk : disks) {
            ThresholdConfig diskConfig = store.loadThresholdConfig("disk:" + disk.getMountPoint());
            evaluate(deviceName, diskConfig, disk.getUsedPercent());
        }
    }

    // ─── 自訂指標 ─────────────────────────────────────────────────

    public void evaluate(String deviceName, String metricKey, double value) {
        ThresholdConfig config = store.loadThresholdConfig(metricKey);
        if (config == null) {
            // 閾值被刪除或從未設定：丟棄狀態，重新設定後從頭計數
            states.remove(deviceName + "::" + metricKey);
            return;
        }
        evaluate(deviceName, config, value);
    }

    // ─── 核心狀態機 ───────────────────────────────────────────────

    private void evaluate(String deviceName, ThresholdConfig config, double value) {
        String key = deviceName + "::" + config.getMetricName();

        ThresholdState state = states.computeIfAbsent(
            key, k -> new ThresholdState(deviceName, config)
        );
        state.updateConfig(config);

        AlertEvent event = state.evaluate(value);

        if (event != null) {
            store.saveAlertEvent(event);

            boolean high = config.getDirection() == ThresholdConfig.Direction.HIGH;
            if (event.getType() == AlertEvent.Type.TRIGGERED) {
                log.warn("🔴 TRIGGERED  {} {}={} ({}{})", deviceName, config.getMetricName(),
                    fmt(value), high ? ">" : "<", fmt(config.getThreshold()));
            } else {
                log.info("🟢 RESOLVED   {} {}={} ({}{})", deviceName, config.getMetricName(),
                    fmt(value), high ? "<=" : ">=", fmt(config.getRearmValue()));
            }
        }
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
