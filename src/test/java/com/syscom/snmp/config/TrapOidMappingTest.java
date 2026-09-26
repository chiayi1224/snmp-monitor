package com.syscom.snmp.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrapOidMappingTest {

    @Test
    void defaults_回傳非空清單() {
        List<TrapOidMapping.Mapping> list = TrapOidMapping.defaults();
        assertNotNull(list);
        assertFalse(list.isEmpty());
    }

    @Test
    void defaults_包含cpu指標() {
        List<TrapOidMapping.Mapping> list = TrapOidMapping.defaults();
        boolean hasCpu = list.stream()
            .anyMatch(m -> "cpu".equals(m.metricName()));
        assertTrue(hasCpu, "應包含 cpu 指標對應");
    }

    @Test
    void defaults_包含memory指標() {
        List<TrapOidMapping.Mapping> list = TrapOidMapping.defaults();
        boolean hasMem = list.stream()
            .anyMatch(m -> "memory".equals(m.metricName()));
        assertTrue(hasMem, "應包含 memory 指標對應");
    }

    @Test
    void Mapping_triggeredOid不為空() {
        List<TrapOidMapping.Mapping> list = TrapOidMapping.defaults();
        for (TrapOidMapping.Mapping m : list) {
            assertNotNull(m.triggeredOid());
            assertFalse(m.triggeredOid().isBlank());
        }
    }

    @Test
    void Mapping_hasResolved_resolvedOid不為空() {
        List<TrapOidMapping.Mapping> list = TrapOidMapping.defaults();
        for (TrapOidMapping.Mapping m : list) {
            if (m.hasResolved()) {
                assertNotNull(m.resolvedOid());
                assertFalse(m.resolvedOid().isBlank());
            }
        }
    }
}
