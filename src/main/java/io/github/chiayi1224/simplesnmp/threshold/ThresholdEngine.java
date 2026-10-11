package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.i18n.StoredText;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.model.DeviceStatus;
import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 閾值引擎：每次輪詢後依「設備＋指標」的閾值評估，狀態改變才寫入告警事件。
 *
 *  - 每次評估都重新讀取該設備的閾值，修改後下一次輪詢立即生效
 *  - 沒有閾值的指標只儲存、不告警；閾值被移除時，未解除的告警記錄解除
 *  - 第一次評估某個「設備＋指標」時，由資料庫最後一筆事件還原未解除的等級（重啟後延續告警狀態）
 *  - 記憶體中有告警等級時，每次評估都與資料庫比對：告警在別處被解除（刪除設備、磁碟過濾變更）時重新同步
 */
public class ThresholdEngine {

    private static final Logger log = LoggerFactory.getLogger(ThresholdEngine.class);

    private final MetricStore store;

    // key = "deviceName::metricName"，例如 "Server-01::cpu"
    private final Map<String, ThresholdState> states = new ConcurrentHashMap<>();

    public ThresholdEngine(MetricStore store) {
        this.store = store;
    }

    public void evaluateCpu(String deviceName, double cpuUsedPct) {
        evaluate(deviceName, ProfileMetric.CPU, cpuUsedPct);
    }

    public void evaluateMemory(String deviceName, double memUsedPct) {
        evaluate(deviceName, ProfileMetric.MEMORY, memUsedPct);
    }

    /** 每個掛載點以 disk:&lt;掛載點&gt; 評估（沒有掛載點覆寫時使用設備的 disk 閾值） */
    public void evaluateDisks(String deviceName, List<DiskInfo> disks) {
        for (DiskInfo disk : disks) {
            evaluate(deviceName, "disk:" + disk.getMountPoint(), disk.getUsedPercent());
        }
    }

    /** 任一指標（含自訂指標）；設備沒有該指標的閾值時只處理未解除告警的解除 */
    public void evaluate(String deviceName, String metricName, double value) {
        ThresholdConfig config = store.loadThreshold(deviceName, metricName);
        ThresholdState state = stateFor(deviceName, metricName);
        AlertEvent event = config == null
            ? state.clear(StoredText.encode(Msg.of("alert.threshold_removed")))
            : state.evaluate(config, value);
        record(event);
    }

    /**
     * 設備連線中斷告警：設定開啟時，OFFLINE／SNMP_ERROR 連續達到次數即告警，恢復 ONLINE 即解除；
     * 設定關閉時，未解除的連線中斷告警記錄解除。
     */
    public void evaluateReachability(DeviceConfig device, DeviceStatus status) {
        String name = device.getName();
        ThresholdState state = stateFor(name, ProfileMetric.DEVICE_UNREACHABLE);
        Severity severity = Severity.parse(device.getUnreachableSeverity());
        if (severity == null) {
            record(state.clear(StoredText.encode(Msg.of("alert.unreachable_disabled"))));
            return;
        }
        boolean down = status == DeviceStatus.OFFLINE || status == DeviceStatus.SNMP_ERROR;
        ThresholdConfig.Builder b = new ThresholdConfig.Builder(ProfileMetric.DEVICE_UNREACHABLE)
            .rearmMargin(0).triggerCount(device.getUnreachableCount()).rearmCount(1);
        ThresholdConfig config = (severity == Severity.CRITICAL ? b.critical(0.5) : b.warning(0.5)).build();
        String message = StoredText.encode(switch (status) {
            case OFFLINE    -> Msg.of("alert.unreachable.ping");
            case SNMP_ERROR -> Msg.of("alert.unreachable.snmp");
            default         -> Msg.of("alert.unreachable.recovered");
        });
        record(state.evaluate(config, down ? 1.0 : 0.0, message));
    }

    private ThresholdState stateFor(String deviceName, String metricName) {
        String key = deviceName + "::" + metricName;
        ThresholdState state = states.get(key);
        if (state == null) {
            state = new ThresholdState(deviceName, metricName, store.openAlertSeverity(deviceName, metricName));
            states.put(key, state);
        } else if (state.getLevel() != null) {
            Severity stored = store.openAlertSeverity(deviceName, metricName);
            if (stored != state.getLevel()) state.restore(stored);
        }
        return state;
    }

    private void record(AlertEvent event) {
        if (event == null) return;
        store.saveAlertEvent(event);
        String line = "{} {} {} {}={} (threshold={})";
        Object[] args = { event.getType(), event.getSeverity(), event.getDeviceName(), event.getMetricName(),
            fmt(event.getValue()), fmt(event.getThresholdVal()) };
        if (event.getType() == AlertEvent.Type.RESOLVED) log.info("Alert " + line, args);
        else log.warn("Alert " + line, args);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
