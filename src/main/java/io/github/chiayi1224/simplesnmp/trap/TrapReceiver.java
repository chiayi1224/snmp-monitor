package io.github.chiayi1224.simplesnmp.trap;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.TrapOidMapping.Mapping;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /*
     * 查表皆為不可變快照，reload 時整份替換（volatile 寫入），
     * 避免 clear() 與重新填入之間收到的 Trap 查到空表而被誤判為未定義 / 未登錄。
     */
    private volatile Map<String, String>  ipToDevice;      // IP → 設備名稱
    private volatile Map<String, Mapping> triggeredIndex;  // triggered OID → 對應
    private volatile Map<String, Mapping> resolvedIndex;   // resolved OID  → 對應

    private Snmp snmp;

    public TrapReceiver(int listenPort,
                        List<V3UserConfig>   v3Users,
                        MetricStore          store,
                        List<Mapping>        mappings,
                        List<DeviceConfig>   devices) {
        this.listenPort = listenPort;
        this.v3Users    = v3Users;
        this.store      = store;
        applyDevices(devices);
        applyMappings(mappings);
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
            listenPort, ipToDevice.size(), triggeredIndex.size());
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
        applyDevices(devices);
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
        applyMappings(newMappings);
        log.info("Trap Receiver OID 對應表已重新載入，共 {} 組", newMappings.size());
    }

    private void applyDevices(List<DeviceConfig> devices) {
        Map<String, String> ips = new HashMap<>();
        for (DeviceConfig d : devices) ips.put(d.getHost(), d.getName());
        ipToDevice = Map.copyOf(ips);
    }

    private void applyMappings(List<Mapping> mappings) {
        Map<String, Mapping> triggered = new HashMap<>();
        Map<String, Mapping> resolved  = new HashMap<>();
        for (Mapping m : mappings) {
            triggered.put(m.triggeredOid(), m);
            if (m.hasResolved()) resolved.put(m.resolvedOid(), m);
        }
        triggeredIndex = Map.copyOf(triggered);
        resolvedIndex  = Map.copyOf(resolved);
    }

    // ─── 收到 Trap ────────────────────────────────────────────────

    @Override
    public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
        if (event.getPDU() == null) return;

        String peerIp = extractIp(event.getPeerAddress() != null
            ? event.getPeerAddress().toString() : "unknown");
        log.debug("收到 Trap from={} version={}", peerIp, snmpVersion(event.getMessageProcessingModel()));

        // 依 PDU 順序收集 VarBind
        Map<String, String> bindings = new LinkedHashMap<>();
        for (VariableBinding vb : event.getPDU().getVariableBindings()) {
            bindings.put(vb.getOid().toString(), vb.getVariable().toString());
        }

        handleTrap(peerIp, bindings);
        event.setProcessed(true);
    }

    /**
     * Trap 處理主流程（不依賴 SNMP4J 事件物件，方便單元測試）
     *
     * @param peerIp   來源 IP
     * @param bindings VarBind（OID → 值），需保留 PDU 原始順序
     */
    void handleTrap(String peerIp, Map<String, String> bindings) {
        // ① 查表取得設備名稱；找不到則用 IP 當名稱，並標記為未登錄來源
        String  knownName   = ipToDevice.get(peerIp);
        boolean knownSource = knownName != null;
        String  deviceName  = knownSource ? knownName : peerIp;

        // ② 取出 trap OID（v1 沒有這個欄位）
        String trapOid = bindings.get(SNMP_TRAP_OID_KEY);
        if (trapOid == null) {
            log.info("收到 v1 trap from={} ({})，無 snmpTrapOID，略過", deviceName, peerIp);
            return;
        }

        // ③ 組合 varbind 描述（排除 snmpTrapOID.0 本身）
        String message = describeBindings(bindings);

        // ④ 未登錄來源 IP：記錄到 unknown_sources（不影響後續 OID 處理）
        if (!knownSource) {
            log.info("未登錄來源 IP={} trapOid={}", peerIp, trapOid);
            store.saveUnknownSource(peerIp, trapOid, message);
        }

        // ⑤ 查 OID 對應表
        Mapping triggered = triggeredIndex.get(trapOid);
        Mapping resolved  = resolvedIndex.get(trapOid);
        if (triggered != null) {
            handleTriggered(deviceName, triggered, message);
        } else if (resolved != null) {
            handleResolved(deviceName, resolved, message);
        } else {
            // 未定義 OID：完整記錄到 DB，同一 OID 只保留一筆（計次更新）
            log.info("未定義 trap from={} ({}) trapOid={}", deviceName, peerIp, trapOid);
            store.saveUnknownTrap(trapOid, deviceName, peerIp, message);
        }
    }

    /** 將 VarBind 組成 "oid=value; oid=value" 字串（排除 snmpTrapOID.0） */
    private static String describeBindings(Map<String, String> bindings) {
        StringBuilder sb = new StringBuilder();
        bindings.forEach((oid, val) -> {
            if (SNMP_TRAP_OID_KEY.equals(oid)) return;
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(oid).append("=").append(val);
        });
        return sb.toString();
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
    private static String extractIp(String address) {
        int slash = address.lastIndexOf('/');
        return slash >= 0 ? address.substring(0, slash) : address;
    }

    private static String snmpVersion(int model) {
        return switch (model) {
            case 0  -> "v1";
            case 1  -> "v2c";
            case 3  -> "v3";
            default -> "unknown(" + model + ")";
        };
    }
}
