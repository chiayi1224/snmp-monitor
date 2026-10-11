package io.github.chiayi1224.simplesnmp.trap;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.TrapOidMapping.Mapping;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import io.github.chiayi1224.simplesnmp.threshold.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.net.DatagramSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 端對端測試：以 SNMP4J 實際送出 v1 / v2c / v3 Trap 與 INFORM 到本機 TrapReceiver
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrapReceiverEndToEndTest {

    private static final String TRAP_OID_KEY = "1.3.6.1.6.3.1.1.4.1.0";
    private static final String CPU_HIGH     = "1.3.6.1.4.1.9999.1.1";
    private static final String ENTERPRISE   = "1.3.6.1.4.1.9999";
    private static final long   WAIT_MS      = 3000;

    @Mock MetricStore store;

    TrapReceiver receiver;
    Snmp         sender;
    int          port;

    @BeforeEach
    void setUp() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) { port = s.getLocalPort(); }

        receiver = new TrapReceiver(port,
            List.of(V3UserConfig.authPriv("trapUser", "authPass123", "privPass123")),
            store,
            List.of(new Mapping(CPU_HIGH, null, "cpu", "CPU 過高"),
                    new Mapping(ENTERPRISE + ".0.7", null, "fan", "風扇故障（v1 enterprise-specific）")),
            List.of(new DeviceConfig("Local", "127.0.0.1", "public")));
        receiver.start();

        // 送出端使用獨立的 USM / SecurityProtocols，不共用接收端的全域設定
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility);
        USM usm = new USM(protocols, new OctetString(MPv3.createLocalEngineID()), 0);
        usm.addUser(new UsmUser(new OctetString("trapUser"),
            AuthSHA.ID, new OctetString("authPass123"), PrivAES128.ID, new OctetString("privPass123")));
        usm.addUser(new UsmUser(new OctetString("legacyUser"),
            AuthMD5.ID, new OctetString("legacyAuth1"), PrivDES.ID, new OctetString("legacyPriv1")));
        usm.addUser(new UsmUser(new OctetString("modernUser"),
            AuthHMAC192SHA256.ID, new OctetString("modernAuth1"), PrivAES256.ID, new OctetString("modernPriv1")));
        usm.addUser(new UsmUser(new OctetString("wrongUser"),
            AuthSHA.ID, new OctetString("wrongPass123"), PrivAES128.ID, new OctetString("wrongPass123")));

        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());
        dispatcher.addMessageProcessingModel(new MPv3(usm.getLocalEngineID().getValue(), null, protocols,
            SecurityModels.getCollection(new SecurityModel[]{ usm }), CounterSupport.getInstance()));
        sender = new Snmp(dispatcher, new DefaultUdpTransportMapping());
        sender.listen();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sender != null) sender.close();
        if (receiver != null) receiver.stop();
    }

    private UdpAddress target() {
        return new UdpAddress("127.0.0.1/" + port);
    }

    private CommunityTarget<UdpAddress> communityTarget(int version, String community) {
        CommunityTarget<UdpAddress> t = new CommunityTarget<>(target(), new OctetString(community));
        t.setVersion(version);
        t.setRetries(0);
        t.setTimeout(1500);
        return t;
    }

    private static PDU v2Trap(int type, String trapOid) {
        PDU pdu = new PDU();
        pdu.setType(type);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(1234)));
        pdu.add(new VariableBinding(new OID(TRAP_OID_KEY), new OID(trapOid)));
        pdu.add(new VariableBinding(new OID("1.3.6.1.4.1.9999.2.1"), new OctetString("detail")));
        return pdu;
    }

    private AlertEvent awaitAlert() {
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store, timeout(WAIT_MS)).saveAlertEvent(captor.capture());
        return captor.getValue();
    }

    // ─── 等級 ─────────────────────────────────────────────────────

    @Test
    void WARNING等級對應_觸發與解除都帶等級() throws Exception {
        String warnHigh = "1.3.6.1.4.1.9999.3.1", warnNormal = "1.3.6.1.4.1.9999.3.2";
        receiver.reloadMappings(List.of(new Mapping(warnHigh, warnNormal, "fan", "風扇轉速偏低", Severity.WARNING)));

        sender.send(v2Trap(PDU.TRAP, warnHigh), communityTarget(SnmpConstants.version2c, "public"));
        AlertEvent triggered = awaitAlert();
        assertEquals(AlertEvent.Type.TRIGGERED, triggered.getType());
        assertEquals(Severity.WARNING, triggered.getSeverity());

        when(store.openAlertSeverity("Local", "fan")).thenReturn(Severity.WARNING);
        sender.send(v2Trap(PDU.TRAP, warnNormal), communityTarget(SnmpConstants.version2c, "public"));
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store, timeout(WAIT_MS).times(2)).saveAlertEvent(captor.capture());
        AlertEvent resolved = captor.getAllValues().get(1);
        assertEquals(AlertEvent.Type.RESOLVED, resolved.getType());
        assertEquals(Severity.WARNING, resolved.getSeverity());
    }

    // ─── v2c ──────────────────────────────────────────────────────

    @Test
    void v2c_trap_產生告警() throws Exception {
        sender.send(v2Trap(PDU.TRAP, CPU_HIGH), communityTarget(SnmpConstants.version2c, "public"));

        AlertEvent e = awaitAlert();
        assertEquals("Local", e.getDeviceName());
        assertEquals("cpu",   e.getMetricName());
    }

    @Test
    void 新增設備後_同IP的Trap立即以新設備名稱記錄() throws Exception {
        receiver.reloadDevices(List.of(new DeviceConfig("Router-02", "127.0.0.1", "public")));

        sender.send(v2Trap(PDU.TRAP, CPU_HIGH), communityTarget(SnmpConstants.version2c, "public"));

        assertEquals("Router-02", awaitAlert().getDeviceName());
        verify(store, never()).saveUnknownSource(any(), any(), any());
    }

    @Test
    void v2c_inform_回覆確認並產生告警() throws Exception {
        ResponseEvent<UdpAddress> resp = sender.send(v2Trap(PDU.INFORM, CPU_HIGH),
            communityTarget(SnmpConstants.version2c, "public"));

        assertNotNull(resp.getResponse(), "INFORM 應收到 RESPONSE，否則設備會一直重送");
        assertEquals(PDU.RESPONSE, resp.getResponse().getType());
        awaitAlert();
    }

    // ─── v1 ──────────────────────────────────────────────────────

    private static PDUv1 v1Trap(int generic, int specific) {
        PDUv1 pdu = new PDUv1();
        pdu.setType(PDU.V1TRAP);
        pdu.setEnterprise(new OID(ENTERPRISE));
        pdu.setGenericTrap(generic);
        pdu.setSpecificTrap(specific);
        pdu.setTimestamp(5678);
        pdu.setAgentAddress(new IpAddress("127.0.0.1"));
        pdu.add(new VariableBinding(new OID("1.3.6.1.4.1.9999.2.1"), new OctetString("fan-1")));
        return pdu;
    }

    @Test
    void v1_enterprise_specific_trap_依RFC3584轉換後產生告警() throws Exception {
        sender.send(v1Trap(PDUv1.ENTERPRISE_SPECIFIC, 7), communityTarget(SnmpConstants.version1, "public"));

        AlertEvent e = awaitAlert();
        assertEquals("fan", e.getMetricName());
    }

    @Test
    void v1_generic_trap_轉為標準snmpTraps_OID() throws Exception {
        sender.send(v1Trap(PDUv1.COLDSTART, 0), communityTarget(SnmpConstants.version1, "public"));

        // coldStart（generic 0）→ snmpTraps.1
        verify(store, timeout(WAIT_MS)).saveUnknownTrap(eq("1.3.6.1.6.3.1.1.5.1"), eq("Local"), eq("127.0.0.1"),
            contains("1.3.6.1.4.1.9999.2.1=fan-1"));
    }

    // ─── community 檢查 ───────────────────────────────────────────

    @Test
    void community_未設定限制時全部接受() throws Exception {
        sender.send(v2Trap(PDU.TRAP, CPU_HIGH), communityTarget(SnmpConstants.version2c, "anything"));

        awaitAlert();
    }

    @Test
    void community_不在允許清單時丟棄且不回覆INFORM() throws Exception {
        when(store.getSetting(eq("trap_communities"), any())).thenReturn("public, monitor");

        ResponseEvent<UdpAddress> resp = sender.send(v2Trap(PDU.INFORM, CPU_HIGH),
            communityTarget(SnmpConstants.version2c, "wrong"));

        assertNull(resp.getResponse());
        verify(store, after(500).never()).saveAlertEvent(any());
    }

    @Test
    void community_在允許清單時接受() throws Exception {
        when(store.getSetting(eq("trap_communities"), any())).thenReturn("public, monitor");

        sender.send(v2Trap(PDU.TRAP, CPU_HIGH), communityTarget(SnmpConstants.version2c, "monitor"));

        awaitAlert();
    }

    // ─── v3 ──────────────────────────────────────────────────────

    private UserTarget<UdpAddress> userTarget(String user) {
        UserTarget<UdpAddress> t = new UserTarget<>(target(), new OctetString(user), new byte[0]);
        t.setVersion(SnmpConstants.version3);
        t.setSecurityLevel(SecurityLevel.AUTH_PRIV);
        t.setRetries(0);
        t.setTimeout(1500);
        return t;
    }

    private static ScopedPDU v3Trap(String trapOid) {
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(PDU.TRAP);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(1234)));
        pdu.add(new VariableBinding(new OID(TRAP_OID_KEY), new OID(trapOid)));
        return pdu;
    }

    @Test
    void v3_authPriv_trap_產生告警() throws Exception {
        sender.send(v3Trap(CPU_HIGH), userTarget("trapUser"));

        assertEquals("cpu", awaitAlert().getMetricName());
    }

    @Test
    void v3_inform_回覆確認並產生告警() throws Exception {
        ScopedPDU pdu = v3Trap(CPU_HIGH);
        pdu.setType(PDU.INFORM);

        ResponseEvent<UdpAddress> resp = sender.send(pdu, userTarget("trapUser"));

        assertNotNull(resp.getResponse(), "v3 INFORM 應收到 RESPONSE");
        awaitAlert();
    }

    @Test
    void v3_MD5_DES_舊式設備_產生告警() throws Exception {
        receiver.reloadV3Users(List.of(new V3UserConfig.Builder("legacyUser")
            .authPassword("legacyAuth1").authProtocol(V3UserConfig.AuthProtocol.MD5)
            .privPassword("legacyPriv1").privProtocol(V3UserConfig.PrivProtocol.DES)
            .build()));

        sender.send(v3Trap(CPU_HIGH), userTarget("legacyUser"));

        awaitAlert();
    }

    @Test
    void v3_SHA256_AES256_產生告警() throws Exception {
        receiver.reloadV3Users(List.of(new V3UserConfig.Builder("modernUser")
            .authPassword("modernAuth1").authProtocol(V3UserConfig.AuthProtocol.SHA256)
            .privPassword("modernPriv1").privProtocol(V3UserConfig.PrivProtocol.AES256)
            .build()));

        sender.send(v3Trap(CPU_HIGH), userTarget("modernUser"));

        awaitAlert();
    }

    @Test
    void v3_未登錄使用者_丟棄() throws Exception {
        sender.send(v3Trap(CPU_HIGH), userTarget("wrongUser"));

        verify(store, after(800).never()).saveAlertEvent(any());
    }

    @Test
    void v3_reload後新增的使用者立即生效_刪除的使用者失效() throws Exception {
        receiver.reloadV3Users(List.of(V3UserConfig.authPriv("wrongUser", "wrongPass123", "wrongPass123")));

        sender.send(v3Trap(CPU_HIGH), userTarget("wrongUser"));
        awaitAlert();

        clearInvocations(store);
        sender.send(v3Trap(CPU_HIGH), userTarget("trapUser"));
        verify(store, after(800).never()).saveAlertEvent(any());
    }

    @Test
    void 啟動時沒有v3使用者_之後新增仍可接收v3() throws Exception {
        receiver.stop();
        receiver = new TrapReceiver(port, List.of(), store,
            List.of(new Mapping(CPU_HIGH, null, "cpu", "")),
            List.of(new DeviceConfig("Local", "127.0.0.1", "public")));
        receiver.start();

        receiver.reloadV3Users(List.of(V3UserConfig.authPriv("trapUser", "authPass123", "privPass123")));
        sender.send(v3Trap(CPU_HIGH), userTarget("trapUser"));

        awaitAlert();
    }
}
