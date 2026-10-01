package com.syscom.snmp.poller;

import com.syscom.snmp.config.DeviceConfig;
import com.syscom.snmp.config.OidConstants;
import com.syscom.snmp.model.DeviceStatus;
import com.syscom.snmp.model.DiskInfo;
import com.syscom.snmp.storage.MetricStore;
import com.syscom.snmp.threshold.ThresholdEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;

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

    private Snmp snmp;
    private ScheduledExecutorService scheduler;

    public SnmpPoller(MetricStore store, long intervalSeconds) {
        this.store           = store;
        this.intervalSeconds = intervalSeconds;
        this.thresholdEngine = new ThresholdEngine(store);
    }

    // ─── 啟動 / 停止 ─────────────────────────────────────────────

    public void start() throws IOException {
        snmp = new Snmp(new DefaultUdpTransportMapping());
        snmp.listen();

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
        if (snmp != null) {
            try { snmp.close(); } catch (IOException ignored) {}
        }
    }

    // ─── 主流程 ───────────────────────────────────────────────────

    private void pollAll() {
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

        // ── 第二層：SNMP GET（CPU + Memory）──────────────────────
        Map<String, String> scalars = getScalars(device, scalarOids);

        // SNMP 無回應（有 OID 要查但回傳空）→ SNMP_ERROR
        if (!scalarOids.isEmpty() && scalars.isEmpty()) {
            log.warn("[{}] SNMP GET 無回應，標記 SNMP_ERROR", name);
            updateStatus(name, DeviceStatus.SNMP_ERROR);
            return;
        }

        recordCpu(name, scalars);
        recordMemory(name, scalars);

        // ── WALK：Disk ────────────────────────────────────────────
        if (diskEnabled) recordDisks(device);

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

    private Map<String, String> getScalars(DeviceConfig device, List<String> oids) throws IOException {
        if (oids.isEmpty()) return new LinkedHashMap<>();
        CommunityTarget<UdpAddress> target = buildTarget(device);
        PDU pdu = new PDU();
        pdu.setType(PDU.GET);
        for (String oid : oids) {
            pdu.add(new VariableBinding(new OID(oid)));
        }
        ResponseEvent<?> event = snmp.get(pdu, target);
        Map<String, String> result = new LinkedHashMap<>();
        if (event == null || event.getResponse() == null) {
            log.warn("[{}] GET 無回應", device.getName());
            return result;
        }
        for (VariableBinding vb : event.getResponse().getVariableBindings()) {
            result.put(vb.getOid().toString(), vb.getVariable().toString());
        }
        return result;
    }

    // ─── WALK ─────────────────────────────────────────────────────

    private List<DiskInfo> getDiskInfo(DeviceConfig device) throws IOException {
        CommunityTarget<UdpAddress> target = buildTarget(device);
        OID[] columnOids = {
            new OID(OidConstants.HR_STORAGE_TYPE),
            new OID(OidConstants.HR_STORAGE_DESCR),
            new OID(OidConstants.HR_STORAGE_UNIT),
            new OID(OidConstants.HR_STORAGE_SIZE),
            new OID(OidConstants.HR_STORAGE_USED)
        };
        TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory(PDU.GETNEXT));
        List<TableEvent> rows = tableUtils.getTable(target, columnOids, null, null);
        List<DiskInfo> result = new ArrayList<>();
        OID fixedDiskType = new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK);
        for (TableEvent row : rows) {
            if (row.isError()) continue;
            VariableBinding[] cols = row.getColumns();
            if (cols == null || cols.length < 5) continue;
            Variable typeVar = cols[0] != null ? cols[0].getVariable() : null;
            if (typeVar == null || !typeVar.toString().equals(fixedDiskType.toString())) continue;
            String mountPoint = cols[1] != null ? cols[1].getVariable().toString() : "unknown";
            long   allocUnit  = cols[2] != null ? cols[2].getVariable().toLong()   : 1L;
            long   sizeUnits  = cols[3] != null ? cols[3].getVariable().toLong()   : 0L;
            long   usedUnits  = cols[4] != null ? cols[4].getVariable().toLong()   : 0L;
            result.add(new DiskInfo(mountPoint, sizeUnits * allocUnit, usedUnits * allocUnit));
        }
        return result;
    }

    private CommunityTarget<UdpAddress> buildTarget(DeviceConfig device) {
        CommunityTarget<UdpAddress> target = new CommunityTarget<>();
        target.setCommunity(new OctetString(device.getCommunity()));
        target.setAddress((UdpAddress) GenericAddress.parse(device.getUdpAddress()));
        target.setVersion(SnmpConstants.version2c);
        target.setRetries(device.getRetries());
        target.setTimeout(device.getTimeoutMs());
        return target;
    }
}
