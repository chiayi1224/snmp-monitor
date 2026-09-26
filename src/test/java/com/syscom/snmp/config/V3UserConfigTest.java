package com.syscom.snmp.config;

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
}
