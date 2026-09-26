package com.syscom.snmp.trap;

import com.syscom.snmp.config.DeviceConfig;
import com.syscom.snmp.config.TrapOidMapping;
import com.syscom.snmp.config.TrapOidMapping.Mapping;
import com.syscom.snmp.config.V3UserConfig;
import com.syscom.snmp.storage.MetricStore;
import com.syscom.snmp.threshold.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.*;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SNMP Trap Receiver（同時支援 v1 / v2c / v3）
 *
 * 辨識來源設備：
 *   1. 用 peer IP 查 ipToDevice 表（從 DeviceConfig 建立）
 *   2. 找不到 → fallback 用 peer IP 當作設備名稱（未登錄設備也能收）
 *   3. 寫入 alert_events 時 device_name 用設備名稱，不用裸 IP
 *
 * 設備清單可透過 reloadDevices() 動態更新，無需重啟。
 */
public class TrapReceiver implements CommandResponder {

    private static final Logger log = LoggerFactory.getLogger(TrapReceiver.class);

    private static final String SNMP_TRAP_OID_KEY = "1.3.6.1.6.3.1.1.4.1.0";

    private final int                listenPort;
    private final List<V3UserConfig> v3Users;
    private final MetricStore        store;
    private final List<Mapping>      mappings;

    // IP → 設備名稱查表，使用 ConcurrentHashMap 確保執行緒安全
    private final ConcurrentHashMap<String, String> ipToDevice = new ConcurrentHashMap<>();

    // OID 快速查表
    private final Map<String, Mapping> triggeredIndex = new ConcurrentHashMap<>();
    private final Map<String, Mapping> resolvedIndex  = new ConcurrentHashMap<>();

    private Snmp snmp;

    public TrapReceiver(int listenPort,
                        List<V3UserConfig>   v3Users,
                        MetricStore          store,
                        List<Mapping>        mappings,
                        List<DeviceConfig>   devices) {
        this.listenPort = listenPort;
        this.v3Users    = v3Users;
        this.store      = store;
        this.mappings   = mappings;

        // 建立 IP → 名稱查表
        for (DeviceConfig d : devices) {
            ipToDevice.put(d.getHost(), d.getName());
        }

        // 建立 OID 快速查表
        for (Mapping m : mappings) {
            triggeredIndex.put(m.triggeredOid(), m);
            if (m.hasResolved()) resolvedIndex.put(m.resolvedOid(), m);
        }
    }

    // ─── 啟動 ─────────────────────────────────────────────────────

    public void start() throws IOException {
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());

        if (!v3Users.isEmpty()) {
            SecurityModels.getInstance().addSecurityModel(buildUsm());
            dispatcher.addMessageProcessingModel(new MPv3());
            log.info("SNMPv3 USM 已啟用，已登錄 {} 個 user", v3Users.size());
        }

        UdpAddress listenAddress = new UdpAddress(InetAddress.getByName("0.0.0.0"), listenPort);
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(listenAddress);

        snmp = new Snmp(dispatcher, transport);
        snmp.addCommandResponder(this);
        snmp.listen();

        log.info("Trap Receiver 啟動，port={}，已知設備 {} 台，OID 對應 {} 組",
            listenPort, ipToDevice.size(), mappings.size());
    }

    public void stop() {
        if (snmp != null) {
            try { snmp.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 重新載入設備清單（設備增修刪後呼叫此方法，無需重啟）
     */
    public void reloadDevices(List<DeviceConfig> devices) {
        ipToDevice.clear();
        for (DeviceConfig d : devices) {
            ipToDevice.put(d.getHost(), d.getName());
        }
        log.info("Trap Receiver 設備清單已重新載入，共 {} 台", ipToDevice.size());
    }

    /**
     * 重新載入 SNMPv3 使用者（USM）
     * 注意：需要重建 USM 並重新建立 SNMP 監聽器才能完全生效。
     * 此方法更新記憶體中的使用者清單；完全生效需重啟。
     * 對於 Web 管理顯示用途已足夠。
     */
    public void reloadV3Users(List<V3UserConfig> newUsers) {
        SecurityModel secModel = SecurityModels.getInstance()
            .getSecurityModel(new Integer32(SecurityModel.SECURITY_MODEL_USM));
        if (secModel instanceof USM usm) {
            for (V3UserConfig user : newUsers) {
                usm.addUser(buildUsmUser(user));
            }
        }
        log.info("SNMPv3 USM 使用者已重新載入，共 {} 個", newUsers.size());
    }

    /**
     * 重新載入 Trap OID 對應表（OID 設定增修刪後呼叫此方法，無需重啟）
     */
    public void reloadMappings(List<Mapping> newMappings) {
        triggeredIndex.clear();
        resolvedIndex.clear();
        for (Mapping m : newMappings) {
            triggeredIndex.put(m.triggeredOid(), m);
            if (m.hasResolved()) resolvedIndex.put(m.resolvedOid(), m);
        }
        log.info("Trap Receiver OID 對應表已重新載入，共 {} 組", newMappings.size());
    }

    // ─── 收到 Trap ────────────────────────────────────────────────

    @Override
    public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
        if (event.getPDU() == null) return;

        // ① 取得 peer IP
        String peerIp = extractIp(event.getPeerAddress() != null
            ? event.getPeerAddress().toString() : "unknown");

        // ② 查表取得設備名稱；找不到則用 IP 當名稱，並標記為未登錄來源
        boolean knownSource = ipToDevice.containsKey(peerIp);
        String deviceName = knownSource ? ipToDevice.get(peerIp) : peerIp;

        String version = snmpVersion(event.getMessageProcessingModel());
        log.debug("收到 Trap from={} ({}) version={}", deviceName, peerIp, version);

        // ③ 收集 VarBind
        Map<String, String> bindings = new ConcurrentHashMap<>();
        for (VariableBinding vb : event.getPDU().getVariableBindings()) {
            bindings.put(vb.getOid().toString(), vb.getVariable().toString());
        }

        // ④ 取出 trap OID（v1 沒有這個欄位）
        String trapOid = bindings.get(SNMP_TRAP_OID_KEY);
        if (trapOid == null) {
            log.info("收到 v1 trap from={} ({})，無 snmpTrapOID，略過", deviceName, peerIp);
            event.setProcessed(true);
            return;
        }

        // ⑤ 組合 varbind 描述（排除 snmpTrapOID.0 本身）
        StringBuilder sb = new StringBuilder();
        bindings.forEach((oid, val) -> {
            if (!SNMP_TRAP_OID_KEY.equals(oid)) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(oid).append("=").append(val);
            }
        });
        String message = sb.toString();

        // ⑥ 未登錄來源 IP：記錄到 unknown_sources（不影響後續 OID 處理）
        if (!knownSource) {
            log.info("未登錄來源 IP={} trapOid={}", peerIp, trapOid);
            store.saveUnknownSource(peerIp, trapOid, message);
        }

        // ⑦ 查 OID 對應表
        if (triggeredIndex.containsKey(trapOid)) {
            handleTriggered(deviceName, triggeredIndex.get(trapOid), message);
        } else if (resolvedIndex.containsKey(trapOid)) {
            handleResolved(deviceName, resolvedIndex.get(trapOid), message);
        } else {
            // 未定義 OID：完整記錄到 DB，同一 OID 只保留一筆（計次更新）
            log.info("未定義 trap from={} ({}) trapOid={}", deviceName, peerIp, trapOid);
            store.saveUnknownTrap(trapOid, deviceName, peerIp, message);
        }

        event.setProcessed(true);
    }

    // ─── TRIGGERED ────────────────────────────────────────────────

    private void handleTriggered(String deviceName, Mapping mapping, String message) {
        String metric = mapping.metricName();

        if (store.hasOpenAlert(deviceName, metric)) {
            log.debug("TRIGGERED trap {} {} 已有開放告警，略過", deviceName, metric);
            return;
        }

        log.warn("🔴 TRIGGERED trap {} metric={} msg={}", deviceName, metric, message);
        store.saveAlertEvent(AlertEvent.triggered(deviceName, metric, 0, 0, message));
    }

    // ─── RESOLVED ─────────────────────────────────────────────────

    private void handleResolved(String deviceName, Mapping mapping, String message) {
        String metric = mapping.metricName();

        if (!store.hasOpenAlert(deviceName, metric)) {
            log.debug("RESOLVED trap {} {} 無對應告警，略過", deviceName, metric);
            return;
        }

        log.info("🟢 RESOLVED trap {} metric={} msg={}", deviceName, metric, message);
        store.saveAlertEvent(AlertEvent.resolved(deviceName, metric, 0, 0, message));
    }

    // ─── USM（v3）────────────────────────────────────────────────

    private USM buildUsm() {
        byte[] localEngineId = MPv3.createLocalEngineID();
        USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(localEngineId), 0);
        for (V3UserConfig user : v3Users) {
            usm.addUser(buildUsmUser(user));
            log.info("USM 登錄 user={} auth={} priv={}",
                user.getUsername(),
                user.hasAuth() ? "yes" : "no",
                user.hasPriv() ? "yes" : "no");
        }
        return usm;
    }

    private UsmUser buildUsmUser(V3UserConfig cfg) {
        OctetString username = new OctetString(cfg.getUsername());
        if (!cfg.hasAuth()) {
            return new UsmUser(username, null, null, null, null);
        }
        OctetString authPass = new OctetString(cfg.getAuthPassword());
        if (!cfg.hasPriv()) {
            return new UsmUser(username, cfg.getAuthProtocolOid(), authPass, null, null);
        }
        return new UsmUser(username,
            cfg.getAuthProtocolOid(), authPass,
            cfg.getPrivProtocolOid(), new OctetString(cfg.getPrivPassword()));
    }

    // ─── 工具 ─────────────────────────────────────────────────────

    /**
     * 從 SNMP4J 的 address 字串取出純 IP
     * 例如 "192.168.1.10/45123" → "192.168.1.10"
     *      "192.168.1.10"       → "192.168.1.10"
     */
    private String extractIp(String address) {
        int slash = address.lastIndexOf('/');
        return slash >= 0 ? address.substring(0, slash) : address;
    }

    private String snmpVersion(int model) {
        return switch (model) {
            case 0  -> "v1";
            case 1  -> "v2c";
            case 3  -> "v3";
            default -> "unknown(" + model + ")";
        };
    }
}
