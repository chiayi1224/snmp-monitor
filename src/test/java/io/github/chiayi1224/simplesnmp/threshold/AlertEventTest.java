package io.github.chiayi1224.simplesnmp.threshold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlertEventTest {

    @Test
    void triggered_帶等級() {
        AlertEvent e = AlertEvent.triggered("Server-01", "cpu", Severity.WARNING, 85.0, 80.0, "");
        assertEquals(AlertEvent.Type.TRIGGERED, e.getType());
        assertEquals(Severity.WARNING, e.getSeverity());
        assertEquals("Server-01", e.getDeviceName());
        assertEquals("cpu",       e.getMetricName());
        assertEquals(85.0,        e.getValue(),        0.001);
        assertEquals(80.0,        e.getThresholdVal(), 0.001);
        assertNotNull(e.getTs());
    }

    @Test
    void changed_帶新等級() {
        AlertEvent e = AlertEvent.changed("Server-01", "cpu", Severity.CRITICAL, 96.0, 95.0, "");
        assertEquals(AlertEvent.Type.CHANGED, e.getType());
        assertEquals(Severity.CRITICAL, e.getSeverity());
    }

    @Test
    void resolved_帶解除前等級與訊息() {
        AlertEvent e = AlertEvent.resolved("Server-02", "memory", Severity.CRITICAL, 70.0, 75.0, "恢復");
        assertEquals(AlertEvent.Type.RESOLVED, e.getType());
        assertEquals(Severity.CRITICAL, e.getSeverity());
        assertEquals("恢復", e.getMessage());
    }

    @Test
    void message為null時為空字串() {
        assertEquals("", AlertEvent.triggered("d", "cpu", Severity.CRITICAL, 1, 1, null).getMessage());
    }

    @Test
    void toString_包含設備名稱類型與等級() {
        String s = AlertEvent.triggered("Router", "cpu", Severity.CRITICAL, 91.0, 90.0, "").toString();
        assertTrue(s.contains("Router"));
        assertTrue(s.contains("TRIGGERED"));
        assertTrue(s.contains("CRITICAL"));
        assertTrue(s.contains("cpu"));
    }

    @Test
    void Severity_解析大小寫不敏感_不合法回null() {
        assertEquals(Severity.WARNING, Severity.parse("warning"));
        assertEquals(Severity.CRITICAL, Severity.parse(" Critical "));
        assertNull(Severity.parse("INFO"));
        assertNull(Severity.parse(""));
        assertNull(Severity.parse(null));
    }

    @Test
    void Severity_比較() {
        assertEquals(Severity.CRITICAL, Severity.max(Severity.WARNING, Severity.CRITICAL));
        assertEquals(Severity.WARNING, Severity.max(null, Severity.WARNING));
        assertEquals(Severity.WARNING, Severity.min(Severity.WARNING, Severity.CRITICAL));
    }
}
