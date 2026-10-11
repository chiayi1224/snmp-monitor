package io.github.chiayi1224.simplesnmp.trap;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.TrapOidMapping.Mapping;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.Snmp;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.MessageProcessingModel;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SNMP Trap Receiver（同一個 port 同時支援 v1 / v2c / v3，含 INFORM）
 *
 * 版本處理：
 *   - v1：依 RFC 3584 §3.1 轉為 v2 格式（補上 snmpTrapOID.0 等欄位）後走相同流程
 *   - v1 / v2c：community 須在設定 trap_communities 的允許清單內（"*" 代表全部接受）
 *   - v3：以 USM 使用者驗證，使用者可透過 reloadV3Users() 動態增刪
 *   - INFORM（v2c / v3）：通過驗證後立即回覆 RESPONSE，避免設備重送
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

    // RFC 3584 §3.1 v1 → v2 轉換用的 OID
    static final String SYS_UPTIME_KEY           = "1.3.6.1.2.1.1.3.0";
    static final String SNMP_TRAPS_PREFIX        = "1.3.6.1.6.3.1.1.5.";   // 標準 generic trap（coldStart 等）
    static final String SNMP_TRAP_ADDRESS_KEY    = "1.3.6.1.6.3.18.1.3.0";
    static final String SNMP_TRAP_ENTERPRISE_KEY = "1.3.6.1.6.3.1.1.4.3.0";

    /** 允許的 v1 / v2c community 設定鍵；值為逗號分隔清單，"*" 代表全部接受 */
    static final String TRAP_COMMUNITIES_SETTING = "trap_communities";

    private final int         listenPort;
    private final MetricStore store;

    // 接收端專用的 SNMPv3 安全設定（不使用 SNMP4J 的全域 singleton）
    private final SecurityProtocols securityProtocols =
        new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility);
    private final USM usm =
        new USM(securityProtocols, new OctetString(MPv3.createLocalEngineID()), 0);

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
        this.store      = store;
        applyDevices(devices);
        applyMappings(mappings);
        applyV3Users(v3Users);
    }

    // ─── 啟動 ─────────────────────────────────────────────────────

    public void start() throws IOException {
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());
        // v3 一律啟用：啟動時沒有使用者，之後從 Web 新增也能立即接收
        dispatcher.addMessageProcessingModel(new MPv3(usm.getLocalEngineID().getValue(), null,
            securityProtocols, SecurityModels.getCollection(new SecurityModel[]{ usm }),
            CounterSupport.getInstance()));

        UdpAddress listenAddress = new UdpAddress(InetAddress.getByName("0.0.0.0"), listenPort);
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(listenAddress);

        snmp = new Snmp(dispatcher, transport);
        snmp.addCommandResponder(this);
        snmp.listen();

        log.info("Trap receiver started on port {}, {} known devices, {} OID mappings",
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
        log.info("Trap receiver device list reloaded: {} devices", ipToDevice.size());
    }

    /**
     * 重新載入 SNMPv3 使用者（USM），立即生效：新增的使用者可馬上送 Trap，已刪除 / 停用的使用者立即失效
     */
    public void reloadV3Users(List<V3UserConfig> newUsers) {
        applyV3Users(newUsers);
        log.info("SNMPv3 USM users reloaded: {} users", newUsers.size());
    }

    /**
     * 重新載入 Trap OID 對應表（OID 設定增修刪後呼叫此方法，無需重啟）
     */
    public void reloadMappings(List<Mapping> newMappings) {
        applyMappings(newMappings);
        log.info("Trap receiver OID mappings reloaded: {} mappings", newMappings.size());
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
        PDU pdu = event.getPDU();
        if (pdu == null || !isNotification(pdu.getType())) return;

        String peerIp = extractIp(event.getPeerAddress() != null
            ? event.getPeerAddress().toString() : "unknown");
        int model = event.getMessageProcessingModel();
        log.debug("Received {} from={} version={}", PDU.getTypeString(pdu.getType()), peerIp, snmpVersion(model));

        // v1 / v2c：檢查 community（v3 已由 USM 驗證）
        if (model != MessageProcessingModel.MPv3) {
            String community = new String(event.getSecurityName(), StandardCharsets.UTF_8);
            if (!isCommunityAccepted(community)) {
                log.warn("Rejected trap from={}: community not in allowed list", peerIp);
                event.setProcessed(true);
                return;
            }
        }

        if (pdu.getType() == PDU.INFORM) sendInformResponse(event);

        Map<String, String> bindings = pdu instanceof PDUv1 v1
            ? v1ToV2Bindings(v1)
            : collectBindings(pdu);

        handleTrap(peerIp, bindings);
        event.setProcessed(true);
    }

    private static boolean isNotification(int pduType) {
        return pduType == PDU.TRAP || pduType == PDU.V1TRAP || pduType == PDU.INFORM;
    }

    /** community 是否在 trap_communities 允許清單內；未設定或為 "*" 時全部接受 */
    boolean isCommunityAccepted(String community) {
        String allowed = store.getSetting(TRAP_COMMUNITIES_SETTING, "*");
        if (allowed == null || allowed.isBlank() || allowed.trim().equals("*")) return true;
        return Arrays.stream(allowed.split(","))
            .map(String::trim)
            .anyMatch(c -> c.equals(community));
    }

    /** 回覆 INFORM 的 RESPONSE（與 SNMP4J NotificationDispatcher 的作法相同） */
    private void sendInformResponse(CommandResponderEvent<?> event) {
        PDU response = (PDU) event.getPDU().clone();
        response.setType(PDU.RESPONSE);
        response.setErrorStatus(PDU.noError);
        response.setErrorIndex(0);
        try {
            event.getMessageDispatcher().returnResponsePdu(
                event.getMessageProcessingModel(), event.getSecurityModel(),
                event.getSecurityName(), event.getSecurityLevel(),
                response, event.getMaxSizeResponsePDU(),
                event.getStateReference(), new StatusInformation());
        } catch (MessageException e) {
            log.warn("Failed to respond to INFORM: {}", e.getMessage());
        }
    }

    /** 依 PDU 順序收集 VarBind */
    private static Map<String, String> collectBindings(PDU pdu) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (VariableBinding vb : pdu.getVariableBindings()) {
            bindings.put(vb.getOid().toString(), vb.getVariable().toString());
        }
        return bindings;
    }

    /**
     * v1 Trap 依 RFC 3584 §3.1 轉為 v2 VarBind：
     *   sysUpTime.0、snmpTrapOID.0、原始 VarBind、snmpTrapAddress.0、snmpTrapEnterprise.0
     * snmpTrapOID：generic 0~5 → snmpTraps.(generic+1)；6（enterpriseSpecific）→ enterprise.0.specific
     */
    static Map<String, String> v1ToV2Bindings(PDUv1 pdu) {
        String enterprise = pdu.getEnterprise().toString();
        String trapOid = pdu.getGenericTrap() == PDUv1.ENTERPRISE_SPECIFIC
            ? enterprise + ".0." + pdu.getSpecificTrap()
            : SNMP_TRAPS_PREFIX + (pdu.getGenericTrap() + 1);

        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put(SYS_UPTIME_KEY, new TimeTicks(pdu.getTimestamp()).toString());
        bindings.put(SNMP_TRAP_OID_KEY, trapOid);
        bindings.putAll(collectBindings(pdu));
        if (pdu.getAgentAddress() != null) {
            bindings.put(SNMP_TRAP_ADDRESS_KEY, pdu.getAgentAddress().toString());
        }
        bindings.put(SNMP_TRAP_ENTERPRISE_KEY, enterprise);
        return bindings;
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

        // ② 取出 trap OID（v1 已在 processPdu 轉換補上；仍缺少代表封包格式不正確）
        String trapOid = bindings.get(SNMP_TRAP_OID_KEY);
        if (trapOid == null) {
            log.warn("Trap from={} ({}) has no snmpTrapOID.0, ignored", deviceName, peerIp);
            return;
        }

        // ③ 組合 varbind 描述（排除 snmpTrapOID.0 本身）
        String message = describeBindings(bindings);

        // ④ 未登錄來源 IP：記錄到 unknown_sources（不影響後續 OID 處理）
        if (!knownSource) {
            log.info("Trap from unregistered source IP={} trapOid={}", peerIp, trapOid);
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
            log.info("Undefined trap from={} ({}) trapOid={}", deviceName, peerIp, trapOid);
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
            log.debug("Trap TRIGGERED {} {} ignored: alert already open", deviceName, metric);
            return;
        }

        log.warn("Trap TRIGGERED {} {} metric={} msg={}", mapping.severity(), deviceName, metric, message);
        store.saveAlertEvent(AlertEvent.triggered(deviceName, metric, mapping.severity(), 0, 0, message));
    }

    // ─── RESOLVED ─────────────────────────────────────────────────

    private void handleResolved(String deviceName, Mapping mapping, String message) {
        String metric = mapping.metricName();

        // 解除事件的等級沿用解除前的等級
        Severity open = store.openAlertSeverity(deviceName, metric);
        if (open == null) {
            log.debug("Trap RESOLVED {} {} ignored: no open alert", deviceName, metric);
            return;
        }

        log.info("Trap RESOLVED {} {} metric={} msg={}", open, deviceName, metric, message);
        store.saveAlertEvent(AlertEvent.resolved(deviceName, metric, open, 0, 0, message));
    }

    // ─── USM（v3）────────────────────────────────────────────────

    /** 以新清單整份替換 USM 使用者（未本地化的使用者可接受任何來源 engine ID 的 Trap） */
    private void applyV3Users(List<V3UserConfig> users) {
        usm.removeAllUsers();
        for (V3UserConfig user : users) {
            usm.addUser(buildUsmUser(user));
            log.info("USM user registered: user={} auth={} priv={}",
                user.getUsername(),
                user.hasAuth() ? "yes" : "no",
                user.hasPriv() ? "yes" : "no");
        }
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
