package io.github.chiayi1224.simplesnmp.poller;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.model.DeviceStatus;
import io.github.chiayi1224.simplesnmp.model.DiskInfo;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定時 SNMP Poller（CPU / Memory / Disk）
 *
 * 每次 poll 前從 DB 重新讀取設備清單（loadEnabledDevices），
 * 所以在 Web 新增/修改/停用設備後，下一個 poll 週期立即生效，
 * 不需要重啟程式。
 */
public class SnmpPoller {

    private static final Logger log = LoggerFactory.getLogger(SnmpPoller.class);

    private final MetricStore     store;
    private final long            intervalSeconds;
    private final ThresholdEngine thresholdEngine;

    /** hrStorage 表格最多讀取的筆數（每列 5 個欄位） */
    private static final int STORAGE_MAX_ROWS = 5_000;

    private SnmpSession session;
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
    }

    // ─── 啟動 / 停止 ─────────────────────────────────────────────

    public void start() throws IOException {
        if (session == null) session = new SnmpSession();

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
        // 每次 poll 前從 DB 重新讀取設備清單與 OID 設定，立即反映 Web 的設定異動
        List<DeviceConfig> devices    = store.loadEnabledDevices();
        List<String>       scalarOids = store.loadEnabledScalarOids();
        boolean            diskEnabled = store.isDiskPollEnabled();

        if (devices.isEmpty()) {
            log.debug("沒有啟用的設備，略過此次 poll");
            return;
        }

        boolean pingEnabled  = store.getSettingBool("ping_enabled", true);
        int     pingTimeout  = store.getSettingInt("ping_timeout_ms", 1000);

        log.debug("開始 poll，共 {} 台設備，scalar OID {} 個，disk={}，ping={}", devices.size(), scalarOids.size(), diskEnabled, pingEnabled);

        for (DeviceConfig device : devices) {
            try {
                pollDevice(device, scalarOids, diskEnabled, pingEnabled, pingTimeout);
            } catch (Exception e) {
                log.error("[{}] poll 失敗: {}", device.getName(), e.getMessage());
            }
        }
    }

    private void pollDevice(DeviceConfig device, List<String> scalarOids, boolean diskEnabled,
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

        // ── 第二層：SNMP GET（CPU + Memory）與 WALK（Disk）────────
        // 設備無回應、v3 認證失敗等 → SNMP_ERROR（SnmpSession 會清除 v3 engine ID 快取）
        try {
            Map<String, String> scalars = getScalars(device, scalarOids);
            recordCpu(name, scalars);
            recordMemory(name, scalars);
            if (diskEnabled) recordDisks(device);
        } catch (SnmpException e) {
            log.warn("[{}] SNMP 查詢失敗，標記 SNMP_ERROR：{}", name, e.getMessage());
            updateStatus(name, DeviceStatus.SNMP_ERROR);
            return;
        }

        // Ping + SNMP 均正常
        updateStatus(name, DeviceStatus.ONLINE);
    }

    private void recordCpu(String name, Map<String, String> scalars) {
        Double cpuUsed = PollParser.cpuUsedPct(scalars);
        if (cpuUsed == null) return;
        store.saveCpu(name, cpuUsed);
        thresholdEngine.evaluateCpu(name, cpuUsed);
        log.info("[{}] CPU {}%", name, String.format("%.1f", cpuUsed));
    }

    private void recordMemory(String name, Map<String, String> scalars) {
        PollParser.Memory mem = PollParser.memory(scalars);
        if (mem == null) return;
        store.saveMemory(name, mem.usedPct(), mem.totalKb(), mem.usedKb());
        thresholdEngine.evaluateMemory(name, mem.usedPct());
        log.info("[{}] Memory {}%", name, String.format("%.1f", mem.usedPct()));
    }

    private void recordDisks(DeviceConfig device) throws IOException {
        String name = device.getName();
        List<DiskInfo> disks = getDiskInfo(device);
        store.saveDisk(name, disks);
        thresholdEngine.evaluateDisks(name, disks);
        for (DiskInfo d : disks) log.info("[{}] Disk {}", name, d);
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

    // ─── GET ──────────────────────────────────────────────────────

    /** GET 指定 OID，回傳 OID → 值字串；設備不支援的 OID 不會出現在結果中 */
    private Map<String, String> getScalars(DeviceConfig device, List<String> oids) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Variable> e : session.get(device, oids).entrySet()) {
            result.put(e.getKey(), e.getValue().toString());
        }
        return result;
    }

    // ─── WALK ─────────────────────────────────────────────────────

    private List<DiskInfo> getDiskInfo(DeviceConfig device) throws IOException {
        SnmpSession.WalkResult walk = session.walk(device, OidConstants.HR_STORAGE_ENTRY, STORAGE_MAX_ROWS, 0);
        if (walk.error() != null) log.warn("[{}] 磁碟表讀取中斷：{}", device.getName(), walk.error());
        return PollParser.disks(walk.rows());
    }
}
