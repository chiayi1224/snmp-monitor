package io.github.chiayi1224.simplesnmp.snmp;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnmpSession 端對端測試：對本機 TestSnmpAgent 以 v1 / v2c / v3 發出 GET 與 walk
 */
class SnmpSessionEndToEndTest {

    private static final String SYS_DESCR = "1.3.6.1.2.1.1.1.0";
    private static final String SYS_NAME  = "1.3.6.1.2.1.1.5.0";
    private static final String IF_DESCR  = "1.3.6.1.2.1.2.2.1.2";

    private final List<TestSnmpAgent> agents = new ArrayList<>();
    private SnmpSession session;

    @BeforeEach
    void setUp() throws Exception {
        session = new SnmpSession();
    }

    @AfterEach
    void tearDown() throws Exception {
        session.close();
        for (TestSnmpAgent a : agents) a.close();
    }

    private TestSnmpAgent agent() throws Exception {
        TestSnmpAgent a = new TestSnmpAgent()
            .put(SYS_DESCR, "Test Agent")
            .put(SYS_NAME, "agent-1");
        for (int i = 1; i <= 50; i++) a.put(IF_DESCR + "." + i, "eth" + i);
        a.put("1.3.6.1.2.1.2.2.1.3.1", 6);   // ifDescr 之後的欄位，walk 不應包含
        agents.add(a);
        return a;
    }

    private static DeviceConfig.Builder device(TestSnmpAgent a) {
        return new DeviceConfig.Builder("dev-" + a.port(), "127.0.0.1").port(a.port()).retries(0).timeoutMs(800);
    }

    private static DeviceConfig v3(TestSnmpAgent a, String user, V3UserConfig.AuthProtocol ap, String auth,
                                   V3UserConfig.PrivProtocol pp, String priv) {
        return device(a).snmpVersion(DeviceConfig.SnmpVersion.V3).v3User(user)
            .v3AuthProtocol(ap).v3AuthPassword(auth).v3PrivProtocol(pp).v3PrivPassword(priv).build();
    }

    // ─── GET ──────────────────────────────────────────────────────

    @Test
    void v2c_get() throws Exception {
        TestSnmpAgent a = agent();

        Map<String, Variable> r = session.get(device(a).build(), List.of(SYS_DESCR, SYS_NAME));

        assertEquals("Test Agent", r.get(SYS_DESCR).toString());
        assertEquals("agent-1",    r.get(SYS_NAME).toString());
    }

    @Test
    void v1_get() throws Exception {
        TestSnmpAgent a = agent();

        Map<String, Variable> r = session.get(device(a).snmpVersion(DeviceConfig.SnmpVersion.V1).build(), List.of(SYS_NAME));

        assertEquals("agent-1", r.get(SYS_NAME).toString());
    }

    @Test
    void get_不存在的OID不出現在結果中() throws Exception {
        TestSnmpAgent a = agent();

        Map<String, Variable> r = session.get(device(a).build(), List.of(SYS_NAME, "1.3.6.1.4.1.9.9.9.0"));

        assertEquals(1, r.size());
        assertTrue(r.containsKey(SYS_NAME));
    }

    @Test
    void community錯誤_設備無回應() throws Exception {
        TestSnmpAgent a = agent();

        SnmpException e = assertThrows(SnmpException.class,
            () -> session.get(device(a).community("wrong").build(), List.of(SYS_NAME)));
        assertTrue(e.getMessage().contains("無回應"));
    }

    @Test
    void v3_authPriv_SHA256_AES256_get() throws Exception {
        TestSnmpAgent a = agent().addUser(new UsmUser(new OctetString("monitor"),
            AuthHMAC192SHA256.ID, new OctetString("authPass1"), PrivAES256.ID, new OctetString("privPass1")));

        Map<String, Variable> r = session.get(
            v3(a, "monitor", V3UserConfig.AuthProtocol.SHA256, "authPass1", V3UserConfig.PrivProtocol.AES256, "privPass1"),
            List.of(SYS_NAME));

        assertEquals("agent-1", r.get(SYS_NAME).toString());
    }

    @Test
    void v3_密碼錯誤_回報認證失敗() throws Exception {
        TestSnmpAgent a = agent().addUser(new UsmUser(new OctetString("monitor"),
            AuthSHA.ID, new OctetString("authPass1"), PrivAES128.ID, new OctetString("privPass1")));

        SnmpException e = assertThrows(SnmpException.class, () -> session.get(
            v3(a, "monitor", V3UserConfig.AuthProtocol.SHA, "WRONGpass", V3UserConfig.PrivProtocol.AES128, "privPass1"),
            List.of(SYS_NAME)));
        assertTrue(e.getMessage().contains("認證") || e.getMessage().contains("無回應"), e.getMessage());
    }

    @Test
    void v3_兩台設備相同使用者名稱不同密碼可同時查詢() throws Exception {
        TestSnmpAgent a1 = agent().put(SYS_NAME, "first").addUser(new UsmUser(new OctetString("monitor"),
            AuthSHA.ID, new OctetString("passwordOne"), PrivAES128.ID, new OctetString("privacyOne")));
        TestSnmpAgent a2 = agent().put(SYS_NAME, "second").addUser(new UsmUser(new OctetString("monitor"),
            AuthSHA.ID, new OctetString("passwordTwo"), PrivAES128.ID, new OctetString("privacyTwo")));
        DeviceConfig d1 = v3(a1, "monitor", V3UserConfig.AuthProtocol.SHA, "passwordOne", V3UserConfig.PrivProtocol.AES128, "privacyOne");
        DeviceConfig d2 = v3(a2, "monitor", V3UserConfig.AuthProtocol.SHA, "passwordTwo", V3UserConfig.PrivProtocol.AES128, "privacyTwo");

        // 交錯查詢兩次，確認後加入的使用者不會覆蓋前一台的金鑰
        for (int i = 0; i < 2; i++) {
            assertEquals("first",  session.get(d1, List.of(SYS_NAME)).get(SYS_NAME).toString());
            assertEquals("second", session.get(d2, List.of(SYS_NAME)).get(SYS_NAME).toString());
        }
    }

    // ─── WALK ─────────────────────────────────────────────────────

    @Test
    void v2c_walk_使用GETBULK且只包含子樹() throws Exception {
        TestSnmpAgent a = agent();

        SnmpSession.WalkResult r = session.walk(device(a).build(), IF_DESCR, 10_000, 0);

        assertEquals(50, r.rows().size());
        assertFalse(r.truncated());
        assertTrue(r.rows().stream().allMatch(vb -> vb.getOid().toString().startsWith(IF_DESCR + ".")));
        assertTrue(a.receivedPduTypes().contains(PDU.GETBULK));
    }

    @Test
    void v1_walk_使用GETNEXT() throws Exception {
        TestSnmpAgent a = agent();

        SnmpSession.WalkResult r = session.walk(device(a).snmpVersion(DeviceConfig.SnmpVersion.V1).build(), IF_DESCR, 10_000, 0);

        assertEquals(50, r.rows().size());
        assertFalse(a.receivedPduTypes().contains(PDU.GETBULK));
        assertTrue(a.receivedPduTypes().contains(PDU.GETNEXT));
    }

    @Test
    void walk_達到筆數上限時截斷() throws Exception {
        TestSnmpAgent a = agent();

        SnmpSession.WalkResult r = session.walk(device(a).build(), IF_DESCR, 30, 0);

        assertEquals(30, r.rows().size());
        assertTrue(r.truncated());
    }

    @Test
    void walk_設備離線拋出例外() throws Exception {
        TestSnmpAgent a = agent().silent(true);

        assertThrows(SnmpException.class, () -> session.walk(device(a).build(), IF_DESCR, 100, 0));
    }

    @Test
    void 只發出讀取請求() throws Exception {
        TestSnmpAgent a = agent();
        session.get(device(a).build(), List.of(SYS_NAME));
        session.walk(device(a).build(), IF_DESCR, 100, 0);

        assertFalse(a.receivedPduTypes().contains(PDU.SET));
    }
}
