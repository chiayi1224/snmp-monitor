package io.github.chiayi1224.simplesnmp.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiskInfoTest {

    @Test
    void 基本getter回傳正確值() {
        DiskInfo d = new DiskInfo("/data", 1000L, 400L);
        assertEquals("/data", d.getMountPoint());
        assertEquals(1000L,   d.getTotalBytes());
        assertEquals(400L,    d.getUsedBytes());
        assertEquals(600L,    d.getFreeBytes());
    }

    @Test
    void getUsedPercent_計算正確() {
        DiskInfo d = new DiskInfo("/", 200L, 50L);
        assertEquals(25.0, d.getUsedPercent(), 0.001);
    }

    @Test
    void getUsedPercent_完全使用() {
        DiskInfo d = new DiskInfo("/", 100L, 100L);
        assertEquals(100.0, d.getUsedPercent(), 0.001);
    }

    @Test
    void getUsedPercent_totalBytes為零不拋例外() {
        DiskInfo d = new DiskInfo("/", 0L, 0L);
        assertEquals(0.0, d.getUsedPercent(), 0.001);
    }

    @Test
    void humanReadable_GB範圍() {
        String s = DiskInfo.humanReadable(2_147_483_648L); // 2 GB
        assertTrue(s.contains("GB"));
        assertTrue(s.contains("2.0"));
    }

    @Test
    void humanReadable_MB範圍() {
        String s = DiskInfo.humanReadable(10_485_760L); // 10 MB
        assertTrue(s.contains("MB"));
        assertTrue(s.contains("10.0"));
    }

    @Test
    void humanReadable_KB範圍() {
        String s = DiskInfo.humanReadable(2_048L); // 2 KB
        assertTrue(s.contains("KB"));
        assertTrue(s.contains("2.0"));
    }

    @Test
    void toString_包含掛載點與百分比() {
        DiskInfo d = new DiskInfo("/home", 1_073_741_824L, 536_870_912L);
        String s = d.toString();
        assertTrue(s.contains("/home"));
        assertTrue(s.contains("50.0%"));
    }
}
