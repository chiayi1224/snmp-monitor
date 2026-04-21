package com.example.snmp.threshold;

import com.example.snmp.model.DiskInfo;
import com.example.snmp.storage.MetricStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 閾值引擎（只處理 CPU / Memory / Disk）
 *
 * 每次 poll 後呼叫對應的 evaluate 方法：
 *  - evaluateCpu()    傳入 cpu 使用率 %
 *  - evaluateMemory() 傳入 memory 使用率 %
 *  - evaluateDisks()  傳入 DiskInfo 清單
 *
 * 狀態改變才寫 DB，不重複。
 */
public class ThresholdEngine {

    private static final Logger log = LoggerFactory.getLogger(ThresholdEngine.class);

    private final MetricStore store;

    // key = "deviceName::metricName"，例如 "Server-01::cpu"
    private final Map<String, ThresholdState> states = new HashMap<>();

    private final ThresholdConfig cpuConfig    = ThresholdConfig.cpu();
    private final ThresholdConfig memoryConfig = ThresholdConfig.memory();

    public ThresholdEngine(MetricStore store) {
        this.store = store;
    }

    // ─── CPU ──────────────────────────────────────────────────────

    public void evaluateCpu(String deviceName, double cpuUsedPct) {
        evaluate(deviceName, cpuConfig, cpuUsedPct);
    }

    // ─── Memory ───────────────────────────────────────────────────

    public void evaluateMemory(String deviceName, double memUsedPct) {
        evaluate(deviceName, memoryConfig, memUsedPct);
    }

    // ─── Disk ─────────────────────────────────────────────────────

    public void evaluateDisks(String deviceName, List<DiskInfo> disks) {
        for (DiskInfo disk : disks) {
            ThresholdConfig diskConfig = ThresholdConfig.disk(disk.getMountPoint());
            evaluate(deviceName, diskConfig, disk.getUsedPercent());
        }
    }

    // ─── 核心狀態機 ───────────────────────────────────────────────

    private void evaluate(String deviceName, ThresholdConfig config, double value) {
        String key = deviceName + "::" + config.getMetricName();

        ThresholdState state = states.computeIfAbsent(
            key, k -> new ThresholdState(deviceName, config)
        );

        AlertEvent event = state.evaluate(value);

        if (event != null) {
            store.saveAlertEvent(event);

            if (event.getType() == AlertEvent.Type.TRIGGERED) {
                log.warn("🔴 TRIGGERED  {} {}={:.1f}% (>{:.1f}%)",
                    deviceName, config.getMetricName(), value, config.getThreshold());
            } else {
                log.info("🟢 RESOLVED   {} {}={:.1f}% (<={:.1f}%)",
                    deviceName, config.getMetricName(), value, config.getRearmValue());
            }
        }
    }
}
