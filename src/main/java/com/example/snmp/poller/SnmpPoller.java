package com.example.snmp.poller;

import com.example.snmp.config.DeviceConfig;
import com.example.snmp.config.OidConstants;
import com.example.snmp.model.DiskInfo;
import com.example.snmp.storage.MetricStore;
import com.example.snmp.threshold.ThresholdEngine;
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
        // 每次 poll 前從 DB 重新讀取設備清單，立即反映 Web 的設定異動
        List<DeviceConfig> devices = store.loadEnabledDevices();

        if (devices.isEmpty()) {
            log.debug("沒有啟用的設備，略過此次 poll");
            return;
        }

        log.debug("開始 poll，共 {} 台設備", devices.size());

        for (DeviceConfig device : devices) {
            try {
                pollDevice(device);
            } catch (Exception e) {
                log.error("[{}] poll 失敗: {}", device.getName(), e.getMessage());
            }
        }
    }

    private void pollDevice(DeviceConfig device) throws IOException {
        String name = device.getName();

        // ── GET：CPU + Memory ──────────────────────────────────────
        Map<String, String> scalars = getScalars(device);

        // CPU
        String idleStr = scalars.get(OidConstants.CPU_IDLE);
        if (idleStr != null) {
            double cpuUsed = 100.0 - Double.parseDouble(idleStr);
            store.saveCpu(name, cpuUsed);
            thresholdEngine.evaluateCpu(name, cpuUsed);
            log.info("[{}] CPU {:.1f}%", name, cpuUsed);
        }

        // Memory
        String totalStr  = scalars.get(OidConstants.MEM_TOTAL);
        String freeStr   = scalars.get(OidConstants.MEM_FREE);
        String bufferStr = scalars.get(OidConstants.MEM_BUFFER);
        String cacheStr  = scalars.get(OidConstants.MEM_CACHE);
        if (totalStr != null && freeStr != null && bufferStr != null && cacheStr != null) {
            long totalKb  = Long.parseLong(totalStr);
            long freeKb   = Long.parseLong(freeStr);
            long bufferKb = Long.parseLong(bufferStr);
            long cacheKb  = Long.parseLong(cacheStr);
            long usedKb   = totalKb - freeKb - bufferKb - cacheKb;
            double memPct = totalKb > 0 ? (double) usedKb / totalKb * 100.0 : 0.0;
            store.saveMemory(name, memPct, totalKb, usedKb);
            thresholdEngine.evaluateMemory(name, memPct);
            log.info("[{}] Memory {:.1f}%", name, memPct);
        }

        // ── WALK：Disk ────────────────────────────────────────────
        List<DiskInfo> disks = getDiskInfo(device);
        store.saveDisk(name, disks);
        thresholdEngine.evaluateDisks(name, disks);
        for (DiskInfo d : disks) log.info("[{}] Disk {}", name, d);
    }

    // ─── GET ──────────────────────────────────────────────────────

    private Map<String, String> getScalars(DeviceConfig device) throws IOException {
        CommunityTarget<UdpAddress> target = buildTarget(device);
        PDU pdu = new PDU();
        pdu.setType(PDU.GET);
        for (String oid : OidConstants.scalarOids()) {
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
        target.setAddress(GenericAddress.parse(device.getUdpAddress()));
        target.setVersion(SnmpConstants.version2c);
        target.setRetries(device.getRetries());
        target.setTimeout(device.getTimeoutMs());
        return target;
    }
}
