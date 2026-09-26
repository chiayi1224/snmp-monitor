package com.syscom.snmp.threshold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlertEventTest {

    @Test
    void triggered_建立TRIGGERED事件() {
        AlertEvent e = AlertEvent.triggered("Server-01", "cpu", 95.0, 90.0);
        assertEquals(AlertEvent.Type.TRIGGERED, e.getType());
        assertEquals("Server-01", e.getDeviceName());
        assertEquals("cpu",       e.getMetricName());
        assertEquals(95.0,        e.getValue(),        0.001);
        assertEquals(90.0,        e.getThresholdVal(), 0.001);
        assertNotNull(e.getTs());
    }

    @Test
    void resolved_建立RESOLVED事件() {
        AlertEvent e = AlertEvent.resolved("Server-02", "memory", 70.0, 75.0);
        assertEquals(AlertEvent.Type.RESOLVED, e.getType());
        assertEquals("Server-02", e.getDeviceName());
        assertEquals("memory",    e.getMetricName());
        assertEquals(70.0,        e.getValue(),        0.001);
        assertEquals(75.0,        e.getThresholdVal(), 0.001);
    }

    @Test
    void toString_包含設備名稱與類型() {
        AlertEvent e = AlertEvent.triggered("Router", "cpu", 91.0, 90.0);
        String s = e.toString();
        assertTrue(s.contains("Router"));
        assertTrue(s.contains("TRIGGERED"));
        assertTrue(s.contains("cpu"));
    }

    @Test
    void triggered_時戳不為null() {
        AlertEvent e = AlertEvent.triggered("dev", "cpu", 91.0, 90.0);
        assertNotNull(e.getTs());
    }
}
