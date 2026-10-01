package com.syscom.snmp.poller;

import com.syscom.snmp.config.OidConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PollParserTest {

    @Test
    void CPU_使用率為100減idle() {
        assertEquals(25.0, PollParser.cpuUsedPct(Map.of(OidConstants.CPU_IDLE, "75")), 0.001);
    }

    @Test
    void CPU_idle未回傳時為null() {
        assertNull(PollParser.cpuUsedPct(Map.of()));
    }

    @Test
    void CPU_idle非數字時為null不拋例外() {
        assertNull(PollParser.cpuUsedPct(Map.of(OidConstants.CPU_IDLE, "noSuchObject")));
    }

    @Test
    void Memory_扣除free_buffer_cache後計算使用率() {
        PollParser.Memory mem = PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL,  "1000",
            OidConstants.MEM_FREE,   "200",
            OidConstants.MEM_BUFFER, "100",
            OidConstants.MEM_CACHE,  "200"));

        assertNotNull(mem);
        assertEquals(1000, mem.totalKb());
        assertEquals(500,  mem.usedKb());
        assertEquals(50.0, mem.usedPct(), 0.001);
    }

    @Test
    void Memory_total為0時使用率為0() {
        PollParser.Memory mem = PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL,  "0",
            OidConstants.MEM_FREE,   "0",
            OidConstants.MEM_BUFFER, "0",
            OidConstants.MEM_CACHE,  "0"));

        assertNotNull(mem);
        assertEquals(0.0, mem.usedPct(), 0.001);
    }

    @Test
    void Memory_缺任一OID時為null() {
        assertNull(PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL, "1000",
            OidConstants.MEM_FREE,  "200",
            OidConstants.MEM_CACHE, "200")));
    }

    @Test
    void Memory_任一值非數字時為null不拋例外() {
        assertNull(PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL,  "1000",
            OidConstants.MEM_FREE,   "noSuchInstance",
            OidConstants.MEM_BUFFER, "100",
            OidConstants.MEM_CACHE,  "200")));
    }
}
