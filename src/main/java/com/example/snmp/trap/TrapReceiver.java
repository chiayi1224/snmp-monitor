package com.example.snmp.trap;

import com.example.snmp.config.DeviceConfig;
import com.example.snmp.config.TrapOidMapping;
import com.example.snmp.config.TrapOidMapping.Mapping;
import com.example.snmp.config.V3UserConfig;
import com.example.snmp.storage.MetricStore;
import com.example.snmp.threshold.AlertEvent;
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
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SNMP Trap Receiver（同時支援 v1 / v2c / v3）
 *
 * 辨識來源設備：
 *   1. 用 peer IP 查 ipToDevice 表（從 DeviceConfig 建立）
 *   2. 找不到 → fallback 用 peer IP 當作設備名稱（未登錄設備也能收）
 *   3. 寫入 alert_events 時 device_name 用設備名稱，不用裸 IP
 */
public class TrapReceiver implements CommandResponder {

    private static final Logger log = LoggerFactory.getLogger(TrapReceiver.class);

    private static final String SNMP_TRAP_OID_KEY = "1.3.6.1.6.3.1.1.4.1.0";

    private final int                listenPort;
    private final List<V3UserConfig> v3Users;
    private final MetricStore        store;
    private final List<Mapping>      mappings;

    // IP → 設備名稱查表，例如 "192.168.1.10" → "Server-01"
    private final Map<String, String> ipToDevice = new HashMap<>();

    // OID 快速查表
    private final Map<String, Mapping> triggeredIndex = new HashMap<>();
    private final Map<String, Mapping> resolvedIndex  = new HashMap<>();

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

    // ─── 收到 Trap ────────────────────────────────────────────────

    @Override
    public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
        if (event.getPDU() == null) return;

        // ① 取得 peer IP
        String peerIp = extractIp(event.getPeerAddress() != null
            ? event.getPeerAddress().toString() : "unknown");

        // ② 查表取得設備名稱，找不到就用 IP（未登錄設備也能收到，只是沒有名稱）
        String deviceName = ipToDevice.getOrDefault(peerIp, peerIp);

        String version = snmpVersion(event.getMessageProcessingModel());
        log.debug("收到 Trap from={} ({}) version={}", deviceName, peerIp, version);

        // ③ 收集 VarBind
        Map<String, String> bindings = new HashMap<>();
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

        // ⑤ 查 OID 對應表
        if (triggeredIndex.containsKey(trapOid)) {
            handleTriggered(deviceName, triggeredIndex.get(trapOid));
        } else if (resolvedIndex.containsKey(trapOid)) {
            handleResolved(deviceName, resolvedIndex.get(trapOid));
        } else {
            log.info("未識別 trap from={} ({}) trapOid={}", deviceName, peerIp, trapOid);
        }

        event.setProcessed(true);
    }

    // ─── TRIGGERED ────────────────────────────────────────────────

    private void handleTriggered(String deviceName, Mapping mapping) {
        String metric = mapping.metricName();

        if (store.hasOpenAlert(deviceName, metric)) {
            log.debug("TRIGGERED trap {} {} 已有開放告警，略過", deviceName, metric);
            return;
        }

        log.warn("🔴 TRIGGERED trap {} metric={}", deviceName, metric);
        store.saveAlertEvent(AlertEvent.triggered(deviceName, metric, 0, 0));
    }

    // ─── RESOLVED ─────────────────────────────────────────────────

    private void handleResolved(String deviceName, Mapping mapping) {
        String metric = mapping.metricName();

        if (!store.hasOpenAlert(deviceName, metric)) {
            log.debug("RESOLVED trap {} {} 無對應告警，略過", deviceName, metric);
            return;
        }

        log.info("🟢 RESOLVED trap {} metric={}", deviceName, metric);
        store.saveAlertEvent(AlertEvent.resolved(deviceName, metric, 0, 0));
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


import com.example.snmp.config.TrapOidMapping;
import com.example.snmp.config.TrapOidMapping.Mapping;
import com.example.snmp.config.V3UserConfig;
import com.example.snmp.storage.MetricStore;
import com.example.snmp.threshold.AlertEvent;
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
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SNMP Trap Receiver（同時支援 v1 / v2c / v3）
 *
 * 收到 trap 後：
 *  1. 從 snmpTrapOID.0 取出 trap OID
 *  2. 查 TrapOidMapping，判斷是 TRIGGERED 還是 RESOLVED
 *  3. 去重複後寫 alert_events
 *  4. 未識別的 trap 只 log，不寫 DB
 */
public class TrapReceiver implements CommandResponder {

    private static final Logger log = LoggerFactory.getLogger(TrapReceiver.class);

    // snmpTrapOID.0 — 所有 v2c/v3 trap 都會帶這個 VarBind
    private static final String SNMP_TRAP_OID_KEY = "1.3.6.1.6.3.1.1.4.1.0";

    private final int              listenPort;
    private final List<V3UserConfig> v3Users;
    private final MetricStore      store;
    private final List<Mapping>    mappings;

    // OID → Mapping 的快速查表（啟動時建立）
    private final Map<String, Mapping> triggeredIndex = new HashMap<>();
    private final Map<String, Mapping> resolvedIndex  = new HashMap<>();

    private Snmp snmp;

    public TrapReceiver(int listenPort, List<V3UserConfig> v3Users,
                        MetricStore store, List<Mapping> mappings) {
        this.listenPort = listenPort;
        this.v3Users    = v3Users;
        this.store      = store;
        this.mappings   = mappings;

        // 建立 OID 快速查表
        for (Mapping m : mappings) {
            triggeredIndex.put(m.triggeredOid(), m);
            if (m.hasResolved()) {
                resolvedIndex.put(m.resolvedOid(), m);
            }
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

        log.info("Trap Receiver 啟動，監聽 UDP port {}（v1/v2c{}），已設定 {} 組 OID 對應",
            listenPort,
            v3Users.isEmpty() ? "" : "/v3",
            mappings.size());
    }

    public void stop() {
        if (snmp != null) {
            try { snmp.close(); } catch (IOException ignored) {}
        }
    }

    // ─── 收到 Trap ────────────────────────────────────────────────

    @Override
    public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
        if (event.getPDU() == null) return;

        String peer    = stripPort(event.getPeerAddress() != null
            ? event.getPeerAddress().toString() : "unknown");
        String version = snmpVersion(event.getMessageProcessingModel());

        // 收集所有 VarBind
        Map<String, String> bindings = new HashMap<>();
        for (VariableBinding vb : event.getPDU().getVariableBindings()) {
            bindings.put(vb.getOid().toString(), vb.getVariable().toString());
        }

        log.debug("收到 Trap from={} version={} varbinds={}", peer, version, bindings.size());

        // 取出 trap OID
        String trapOid = bindings.get(SNMP_TRAP_OID_KEY);
        if (trapOid == null) {
            // v1 trap 沒有 snmpTrapOID.0，只 log
            log.info("收到 v1 trap from={} (無 snmpTrapOID，略過)", peer);
            event.setProcessed(true);
            return;
        }

        // 查表：是否為已知的 TRIGGERED
        if (triggeredIndex.containsKey(trapOid)) {
            handleTriggered(peer, triggeredIndex.get(trapOid));
            event.setProcessed(true);
            return;
        }

        // 查表：是否為已知的 RESOLVED
        if (resolvedIndex.containsKey(trapOid)) {
            handleResolved(peer, resolvedIndex.get(trapOid));
            event.setProcessed(true);
            return;
        }

        // 未識別的 trap，只 log
        log.info("未識別 trap from={} version={} trapOid={}", peer, version, trapOid);
        event.setProcessed(true);
    }

    // ─── TRIGGERED 處理 ───────────────────────────────────────────

    private void handleTriggered(String peer, Mapping mapping) {
        String metric = mapping.metricName();

        if (store.hasOpenAlert(peer, metric)) {
            log.debug("TRIGGERED trap from={} metric={} 已有開放告警，略過", peer, metric);
            return;
        }

        log.warn("🔴 TRIGGERED trap from={} metric={}", peer, metric);
        store.saveAlertEvent(AlertEvent.triggered(peer, metric, 0, 0));
    }

    // ─── RESOLVED 處理 ────────────────────────────────────────────

    private void handleResolved(String peer, Mapping mapping) {
        String metric = mapping.metricName();

        if (!store.hasOpenAlert(peer, metric)) {
            log.debug("RESOLVED trap from={} metric={} 無對應告警，略過", peer, metric);
            return;
        }

        log.info("🟢 RESOLVED trap from={} metric={}", peer, metric);
        store.saveAlertEvent(AlertEvent.resolved(peer, metric, 0, 0));
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

    private String snmpVersion(int model) {
        return switch (model) {
            case 0  -> "v1";
            case 1  -> "v2c";
            case 3  -> "v3";
            default -> "unknown(" + model + ")";
        };
    }

    private String stripPort(String address) {
        int slash = address.lastIndexOf('/');
        return slash >= 0 ? address.substring(0, slash) : address;
    }
}
