package io.github.chiayi1224.simplesnmp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceConfigTest {

    @Test
    void 三參數建構子套用預設值() {
        DeviceConfig d = new DeviceConfig("R1", "10.0.0.1", "public");
        assertEquals("R1",       d.getName());
        assertEquals("10.0.0.1", d.getHost());
        assertEquals(161,        d.getPort());
        assertEquals("public",   d.getCommunity());
        assertEquals(2,          d.getRetries());
        assertEquals(3000L,      d.getTimeoutMs());
    }

    @Test
    void 六參數建構子保留所有值() {
        DeviceConfig d = new DeviceConfig("SW", "192.168.1.1", 162, "private", 3, 5000L);
        assertEquals("SW",          d.getName());
        assertEquals("192.168.1.1", d.getHost());
        assertEquals(162,           d.getPort());
        assertEquals("private",     d.getCommunity());
        assertEquals(3,             d.getRetries());
        assertEquals(5000L,         d.getTimeoutMs());
    }

    @Test
    void getUdpAddress格式正確() {
        DeviceConfig d = new DeviceConfig("X", "10.1.2.3", 161, "public", 1, 1000L);
        assertEquals("udp:10.1.2.3/161", d.getUdpAddress());
    }

    @Test
    void getUdpAddress非預設埠() {
        DeviceConfig d = new DeviceConfig("X", "10.1.2.3", 1161, "public", 1, 1000L);
        assertEquals("udp:10.1.2.3/1161", d.getUdpAddress());
    }

    @Test
    void toString包含名稱與位址() {
        DeviceConfig d = new DeviceConfig("Router", "10.0.0.1", 161, "public", 2, 3000L);
        String s = d.toString();
        assertTrue(s.contains("Router"));
        assertTrue(s.contains("10.0.0.1"));
        assertTrue(s.contains("161"));
    }

    @Test
    void 磁碟過濾條件_預設全部_儲存前整理_過長回傳錯誤() {
        assertEquals("", new DeviceConfig("R1", "10.0.0.1", "public").getDiskFilter());
        assertEquals("/,/data", new DeviceConfig.Builder("R1", "h").diskFilter(" / , /data ,/ ").build().getDiskFilter());
        assertNull(new DeviceConfig.Builder("R1", "h").diskFilter("/").build().validate());
        String tooLong = "/" + "a".repeat(DiskFilter.MAX_LENGTH);
        assertNotNull(new DeviceConfig.Builder("R1", "h").diskFilter(tooLong).build().validate());
    }

    @Test
    void 連線中斷告警_預設關閉_等級正規化_不合法回傳錯誤() {
        DeviceConfig d = new DeviceConfig("R1", "10.0.0.1", "public");
        assertEquals("", d.getUnreachableSeverity());
        assertEquals(1, d.getUnreachableCount());
        assertEquals("CRITICAL", new DeviceConfig.Builder("R1", "h").unreachableSeverity(" critical ").build().getUnreachableSeverity());
        assertNull(new DeviceConfig.Builder("R1", "h").unreachableSeverity("WARNING").unreachableCount(2).build().validate());
        assertNotNull(new DeviceConfig.Builder("R1", "h").unreachableSeverity("INFO").build().validate());
        assertNotNull(new DeviceConfig.Builder("R1", "h").unreachableSeverity("CRITICAL").unreachableCount(0).build().validate());
    }

    // ─── SNMP 版本與 v3 驗證 ─────────────────────────────────────

    private static DeviceConfig.Builder v3(String auth, String priv) {
        return new DeviceConfig.Builder("V3", "10.0.0.1")
            .snmpVersion(DeviceConfig.SnmpVersion.V3).v3User("monitor")
            .v3AuthPassword(auth).v3PrivPassword(priv);
    }

    @Test
    void 舊建構子預設為v2c() {
        assertEquals(DeviceConfig.SnmpVersion.V2C, new DeviceConfig("R1", "10.0.0.1", "public").getSnmpVersion());
    }

    @Test
    void 版本解析() {
        assertEquals(DeviceConfig.SnmpVersion.V1,  DeviceConfig.SnmpVersion.parse("1"));
        assertEquals(DeviceConfig.SnmpVersion.V2C, DeviceConfig.SnmpVersion.parse("2C"));
        assertEquals(DeviceConfig.SnmpVersion.V3,  DeviceConfig.SnmpVersion.parse(" 3 "));
        assertEquals(DeviceConfig.SnmpVersion.V2C, DeviceConfig.SnmpVersion.parse(null));
        assertNull(DeviceConfig.SnmpVersion.parse("4"));
    }

    @Test
    void 驗證_版本不合法() {
        assertNotNull(new DeviceConfig.Builder("X", "h").snmpVersion(null).build().validate());
    }

    @Test
    void 驗證_v1與v2c不檢查v3欄位() {
        assertNull(new DeviceConfig.Builder("X", "h").snmpVersion(DeviceConfig.SnmpVersion.V1).build().validate());
    }

    @Test
    void 驗證_v3合法的authPriv與authNoPriv與noAuth() {
        assertNull(v3("authPass1", "privPass1").build().validate());
        assertNull(v3("authPass1", "").build().validate());
        assertNull(v3("", "").build().validate());
    }

    @Test
    void 驗證_v3缺使用者名稱() {
        assertNotNull(v3("authPass1", "").v3User(" ").build().validate());
    }

    @Test
    void 驗證_v3只有加密沒有認證() {
        assertTrue(v3("", "privPass1").build().validate().contains("認證"));
    }

    @Test
    void 驗證_v3密碼少於8字元() {
        assertNotNull(v3("short", "").build().validate());
        assertNotNull(v3("authPass1", "short").build().validate());
    }

    @Test
    void 驗證_v3密碼為null代表沿用舊值不檢查() {
        assertNull(v3(null, null).build().validate());
    }

    @Test
    void toV3User_依密碼決定安全等級() {
        V3UserConfig authPriv = v3("authPass1", "privPass1")
            .v3AuthProtocol(V3UserConfig.AuthProtocol.SHA256)
            .v3PrivProtocol(V3UserConfig.PrivProtocol.AES256).build().toV3User();
        assertTrue(authPriv.hasAuth());
        assertTrue(authPriv.hasPriv());
        assertEquals(V3UserConfig.AuthProtocol.SHA256, authPriv.getAuthProtocol());
        assertEquals(V3UserConfig.PrivProtocol.AES256, authPriv.getPrivProtocol());

        V3UserConfig noAuth = v3("", "").build().toV3User();
        assertFalse(noAuth.hasAuth());
        assertFalse(noAuth.hasPriv());
    }
}
