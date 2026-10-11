package io.github.chiayi1224.simplesnmp.poller;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.DiskFilter;
import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.model.DeviceStatus;
import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.MetricValue;
import io.github.chiayi1224.simplesnmp.profile.ProfileCollector;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import io.github.chiayi1224.simplesnmp.profile.ProfileResolver;
import io.github.chiayi1224.simplesnmp.snmp.SnmpException;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.ThresholdEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.smi.Variable;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定時 SNMP Poller：依每台設備套用的監控範本收集指標
 *
 * 每次 poll 前從 DB 重新讀取設備清單與範本，所以在 Web 新增/修改設備或範本後，
 * 下一個 poll 週期立即生效，不需要重啟程式。
 *
 * 每台設備的流程（Ping → GET／WALK 與計算由 {@link ProfileCollector} 負責 → 儲存與評估）：
 *  1. 範本所需的 OID 與 sysObjectID 合併成一次 GET；sysObjectID 改變時同一週期內改用新範本
 *  2. cpu／memory／disk 寫入既有資料表並走既有閾值，其他指標寫入 custom_metrics
 *  3. 單一指標計算失敗只記警告（每台設備每個指標每小時最多一次），不影響其他指標與設備狀態
 */
public class SnmpPoller {

    private static final Logger log = LoggerFactory.getLogger(SnmpPoller.class);

    private final MetricStore     store;
    private final long            intervalSeconds;
    private final ThresholdEngine thresholdEngine;

    /** 指標失敗警告的最短間隔 */
    private static final long WARN_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    /** 設備::指標 → 上次記錄失敗警告的時間 */
    private final Map<String, Long> failureLoggedAt = new ConcurrentHashMap<>();

    private SnmpSession      session;
    private ProfileCollector collector;
    private ScheduledExecutorService scheduler;

    public SnmpPoller(MetricStore store, long intervalSeconds) {
        this(store, intervalSeconds, null);
    }

    /** 測試用：注入已建立的 SnmpSession */
    SnmpPoller(MetricStore store, long intervalSeconds, SnmpSession session) {
        this.store           = store;
        this.intervalSeconds = intervalSeconds;
        this.thresholdEngine = new ThresholdEngine(store);
        this.session         = session;
        this.collector       = session == null ? null : new ProfileCollector(session);
    }

    // ─── 啟動 / 停止 ─────────────────────────────────────────────

    public void start() throws IOException {
        if (session == null) {
            session   = new SnmpSession();
            collector = new ProfileCollector(session);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snmp-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pollAll, 0, intervalSeconds, TimeUnit.SECONDS);
        log.info("Poller 啟動，每 {} 秒 poll 一次", intervalSeconds);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdown();
        if (session != null) {
            try { session.close(); } catch (IOException ignored) {}
        }
    }

    // ─── 主流程 ───────────────────────────────────────────────────

    /** 執行一輪輪詢（排程呼叫；測試可直接呼叫） */
    void pollAll() {
        // 每次 poll 前從 DB 重新讀取設備清單與範本，立即反映 Web 的設定異動
        List<DeviceConfig> devices = store.loadEnabledDevices();
        if (devices.isEmpty()) {
            log.debug("沒有啟用的設備，略過此次 poll");
            return;
        }
        ProfileResolver resolver = new ProfileResolver(store.loadProfiles());

        boolean pingEnabled  = store.getSettingBool("ping_enabled", true);
        int     pingTimeout  = store.getSettingInt("ping_timeout_ms", 1000);

        log.debug("開始 poll，共 {} 台設備，ping={}", devices.size(), pingEnabled);

        for (DeviceConfig device : devices) {
            try {
                pollDevice(device, resolver, pingEnabled, pingTimeout);
            } catch (Exception e) {
                log.error("[{}] poll 失敗: {}", device.getName(), e.getMessage());
            }
        }
    }

    private void pollDevice(DeviceConfig device, ProfileResolver resolver,
                            boolean pingEnabled, int pingTimeoutMs) throws IOException {
        String name = device.getName();

        // ── 第一層：Ping 確認主機可達 ─────────────────────────────
        if (pingEnabled) {
            if (!isReachable(device.getHost(), pingTimeoutMs)) {
                log.warn("[{}] Ping {} 失敗，標記 OFFLINE，跳過 SNMP poll", name, device.getHost());
                updateStatus(name, DeviceStatus.OFFLINE);
                return;
            }
            log.debug("[{}] Ping 成功", name);
        }

        // ── 第二層：依範本 GET 與 WALK ────────────────────────────
        // GET 無回應、v3 認證失敗等 → SNMP_ERROR（SnmpSession 會清除 v3 engine ID 快取）
        MetricProfile profile = resolver.resolve(device.getProfileId(), device.getSysObjectId());
        ProfileCollector.Fetched fetched;
        try {
            ProfileCollector.Scalars scalars = collector.get(device, profile, List.of(OidConstants.SYS_OBJECT_ID));

            // sysObjectID 改變（含首次取得）：保存並在同一週期改用新範本
            Variable sysOid = scalars.values().get(OidConstants.SYS_OBJECT_ID);
            String sysObjectId = SnmpSession.isMissing(sysOid) ? "" : sysOid.toString();
            if (!sysObjectId.isEmpty() && !sysObjectId.equals(device.getSysObjectId())) {
                store.updateDeviceSysObjectId(name, sysObjectId);
                MetricProfile resolved = resolver.resolve(device.getProfileId(), sysObjectId);
                if (!Objects.equals(resolved.id(), profile.id())) {
                    log.info("[{}] sysObjectID={}，套用監控範本「{}」", name, sysObjectId, resolved.name());
                    profile = resolved;
                }
            }
            // 範本確定後才補查缺少的 OID 並走訪表格
            fetched = collector.complete(device, profile, scalars);
        } catch (SnmpException e) {
            log.warn("[{}] SNMP 查詢失敗，標記 SNMP_ERROR：{}", name, e.getMessage());
            updateStatus(name, DeviceStatus.SNMP_ERROR);
            return;
        }

        // ── 第三層：逐指標儲存與評估 ─────────────────────────────
        for (ProfileCollector.MetricResult r : ProfileCollector.calculate(profile, fetched, DiskFilter.parse(device.getDiskFilter()))) {
            String key = r.metric().metricKey();
            if (r.ok()) {
                record(name, r.metric(), r.values());
                failureLoggedAt.remove(name + "::" + key);
            } else {
                warnLimited(name, key, r.error());
            }
        }

        // Ping + SNMP 均正常
        updateStatus(name, DeviceStatus.ONLINE);
    }

    /** 依指標名稱分派：cpu／memory／disk 寫既有資料表，其他寫 custom_metrics */
    private void record(String name, ProfileMetric metric, List<MetricValue> values) {
        switch (metric.metricKey()) {
            case ProfileMetric.CPU -> {
                double pct = values.get(0).value();
                store.saveCpu(name, pct);
                thresholdEngine.evaluateCpu(name, pct);
                log.info("[{}] CPU {}%", name, String.format("%.1f", pct));
            }
            case ProfileMetric.MEMORY -> {
                MetricValue v = values.get(0);
                long totalKb = v.totalBytes() == null ? 0 : v.totalBytes() / 1024;
                long usedKb  = v.usedBytes()  == null ? 0 : v.usedBytes()  / 1024;
                store.saveMemory(name, v.value(), totalKb, usedKb);
                thresholdEngine.evaluateMemory(name, v.value());
                log.info("[{}] Memory {}%", name, String.format("%.1f", v.value()));
            }
            case ProfileMetric.DISK -> {
                List<DiskInfo> disks = new ArrayList<>();
                for (MetricValue v : values) {
                    disks.add(new DiskInfo(v.instance() == null ? "unknown" : v.instance(),
                        v.totalBytes() == null ? 0 : v.totalBytes(), v.usedBytes() == null ? 0 : v.usedBytes()));
                }
                store.saveDisk(name, disks);
                thresholdEngine.evaluateDisks(name, disks);
                for (DiskInfo d : disks) log.info("[{}] Disk {}", name, d);
            }
            default -> {
                double value = values.get(0).value();
                store.saveCustomMetric(name, metric.metricKey(), value, metric.unit());
                thresholdEngine.evaluate(name, metric.metricKey(), value);
                log.info("[{}] {} {}{}", name, metric.metricKey(), String.format("%.2f", value), metric.unit());
            }
        }
    }

    /** 同一設備同一指標的失敗警告每小時最多記錄一次，其餘降為 debug */
    private void warnLimited(String device, String metricKey, String reason) {
        String key = device + "::" + metricKey;
        long now = System.currentTimeMillis();
        Long last = failureLoggedAt.get(key);
        if (last == null || now - last >= WARN_INTERVAL_MS) {
            failureLoggedAt.put(key, now);
            log.warn("[{}] 指標 {} 本週期無數值：{}（同一指標每小時最多記錄一次）", device, metricKey, reason);
        } else {
            log.debug("[{}] 指標 {} 本週期無數值：{}", device, metricKey, reason);
        }
    }

    private void updateStatus(String name, DeviceStatus status) {
        store.updateDeviceStatus(name, status.name());
    }

    // ─── Ping ─────────────────────────────────────────────────────

    /**
     * 以 InetAddress.isReachable() 進行 ICMP/TCP 連通測試。
     * Linux 環境不需 root 即可使用（內核 3.x+ 支援 non-root ICMP）。
     */
    private boolean isReachable(String host, int timeoutMs) {
        try {
            return InetAddress.getByName(host).isReachable(timeoutMs);
        } catch (IOException e) {
            log.debug("isReachable 例外 host={}: {}", host, e.getMessage());
            return false;
        }
    }
}
