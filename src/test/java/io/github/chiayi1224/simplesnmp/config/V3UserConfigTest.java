package io.github.chiayi1224.simplesnmp.config;

import org.junit.jupiter.api.Test;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivDES;

import static org.junit.jupiter.api.Assertions.*;

class V3UserConfigTest {

    @Test
    void noAuth_無認證無加密() {
        V3UserConfig u = V3UserConfig.noAuth("user1");
        assertEquals("user1", u.getUsername());
        assertFalse(u.hasAuth());
        assertFalse(u.hasPriv());
        assertNull(u.getAuthPassword());
        assertNull(u.getPrivPassword());
    }

    @Test
    void authOnly_有認證無加密_預設SHA() {
        V3UserConfig u = V3UserConfig.authOnly("user2", "authPass");
        assertEquals("user2",    u.getUsername());
        assertEquals("authPass", u.getAuthPassword());
        assertTrue(u.hasAuth());
        assertFalse(u.hasPriv());
        assertEquals(AuthSHA.ID, u.getAuthProtocolOid());
    }

    @Test
    void authPriv_有認證有加密_預設SHA加AES128() {
        V3UserConfig u = V3UserConfig.authPriv("user3", "authPass", "privPass");
        assertEquals("user3",    u.getUsername());
        assertEquals("authPass", u.getAuthPassword());
        assertEquals("privPass", u.getPrivPassword());
        assertTrue(u.hasAuth());
        assertTrue(u.hasPriv());
        assertEquals(AuthSHA.ID,   u.getAuthProtocolOid());
        assertEquals(PrivAES128.ID, u.getPrivProtocolOid());
    }

    @Test
    void Builder指定MD5和DES() {
        V3UserConfig u = new V3UserConfig.Builder("user4")
            .authPassword("p1")
            .authProtocol(V3UserConfig.AuthProtocol.MD5)
            .privPassword("p2")
            .privProtocol(V3UserConfig.PrivProtocol.DES)
            .build();
        assertEquals(AuthMD5.ID, u.getAuthProtocolOid());
        assertEquals(PrivDES.ID, u.getPrivProtocolOid());
    }

    // ─── 協定擴充與字串解析 ──────────────────────────────────────

    @Test
    void 各認證協定對應SNMP4J的OID() {
        assertEquals(AuthMD5.ID,                                   V3UserConfig.AuthProtocol.MD5.oid());
        assertEquals(AuthSHA.ID,                                   V3UserConfig.AuthProtocol.SHA.oid());
        assertEquals(org.snmp4j.security.AuthHMAC128SHA224.ID,     V3UserConfig.AuthProtocol.SHA224.oid());
        assertEquals(org.snmp4j.security.AuthHMAC192SHA256.ID,     V3UserConfig.AuthProtocol.SHA256.oid());
        assertEquals(org.snmp4j.security.AuthHMAC256SHA384.ID,     V3UserConfig.AuthProtocol.SHA384.oid());
        assertEquals(org.snmp4j.security.AuthHMAC384SHA512.ID,     V3UserConfig.AuthProtocol.SHA512.oid());
    }

    @Test
    void 各加密協定對應SNMP4J的OID() {
        assertEquals(PrivDES.ID,                          V3UserConfig.PrivProtocol.DES.oid());
        assertEquals(PrivAES128.ID,                       V3UserConfig.PrivProtocol.AES128.oid());
        assertEquals(org.snmp4j.security.PrivAES192.ID,   V3UserConfig.PrivProtocol.AES192.oid());
        assertEquals(org.snmp4j.security.PrivAES256.ID,   V3UserConfig.PrivProtocol.AES256.oid());
    }

    @Test
    void 解析_大小寫與連字號不敏感() {
        assertEquals(V3UserConfig.AuthProtocol.SHA256, V3UserConfig.AuthProtocol.parse("sha-256"));
        assertEquals(V3UserConfig.AuthProtocol.MD5,    V3UserConfig.AuthProtocol.parse(" md5 "));
        assertEquals(V3UserConfig.PrivProtocol.AES256, V3UserConfig.PrivProtocol.parse("AES-256"));
        assertEquals(V3UserConfig.PrivProtocol.AES128, V3UserConfig.PrivProtocol.parse("aes"));
        assertEquals(V3UserConfig.PrivProtocol.DES,    V3UserConfig.PrivProtocol.parse("des"));
    }

    @Test
    void 解析_未知或空值回預設() {
        assertEquals(V3UserConfig.AuthProtocol.SHA,    V3UserConfig.AuthProtocol.parse("SHA-999"));
        assertEquals(V3UserConfig.AuthProtocol.SHA,    V3UserConfig.AuthProtocol.parse(null));
        assertEquals(V3UserConfig.PrivProtocol.AES128, V3UserConfig.PrivProtocol.parse(""));
        assertEquals(V3UserConfig.PrivProtocol.AES128, V3UserConfig.PrivProtocol.parse("3DES"));
    }
}
