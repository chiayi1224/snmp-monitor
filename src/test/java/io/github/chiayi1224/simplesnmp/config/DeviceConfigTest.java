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
}
