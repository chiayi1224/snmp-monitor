package io.github.chiayi1224.simplesnmp.threshold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThresholdConfigTest {

    // ─── cpu() 預設值 ─────────────────────────────────────────────

    @Test
    void cpu_預設值正確() {
        ThresholdConfig c = ThresholdConfig.cpu();
        assertEquals("cpu",                      c.getMetricName());
        assertEquals(ThresholdConfig.Direction.HIGH, c.getDirection());
        assertEquals(90.0, c.getThreshold(),  0.001);
        assertEquals(80.0, c.getRearmValue(), 0.001);
        assertEquals(3,    c.getTriggerCount());
        assertEquals(2,    c.getRearmCount());
    }

    @Test
    void cpu_超過閾值() {
        ThresholdConfig c = ThresholdConfig.cpu();
        assertTrue(c.isExceeded(91.0));
        assertFalse(c.isExceeded(90.0)); // 等於不算超過
        assertFalse(c.isExceeded(80.0));
    }

    @Test
    void cpu_已恢復判斷() {
        ThresholdConfig c = ThresholdConfig.cpu();
        assertTrue(c.isRearmed(80.0));  // 等於 rearmValue 算恢復
        assertTrue(c.isRearmed(70.0));
        assertFalse(c.isRearmed(85.0));
    }

    // ─── memory() 預設值 ──────────────────────────────────────────

    @Test
    void memory_預設值正確() {
        ThresholdConfig c = ThresholdConfig.memory();
        assertEquals("memory", c.getMetricName());
        assertEquals(85.0, c.getThreshold(),  0.001);
        assertEquals(75.0, c.getRearmValue(), 0.001);
        assertEquals(3,    c.getTriggerCount());
        assertEquals(2,    c.getRearmCount());
    }

    // ─── disk() 預設值 ────────────────────────────────────────────

    @Test
    void disk_指標名稱包含掛載點() {
        ThresholdConfig c = ThresholdConfig.disk("/data");
        assertEquals("disk:/data", c.getMetricName());
    }

    @Test
    void disk_預設值正確() {
        ThresholdConfig c = ThresholdConfig.disk("/");
        assertEquals(80.0, c.getThreshold(),  0.001);
        assertEquals(70.0, c.getRearmValue(), 0.001);
        assertEquals(2,    c.getTriggerCount());
        assertEquals(2,    c.getRearmCount());
    }

    // ─── Builder ──────────────────────────────────────────────────

    @Test
    void Builder_自訂參數() {
        ThresholdConfig c = new ThresholdConfig.Builder("temp")
            .direction(ThresholdConfig.Direction.LOW)
            .threshold(10.0)
            .rearmValue(20.0)
            .triggerCount(1)
            .rearmCount(1)
            .build();

        assertEquals("temp",                     c.getMetricName());
        assertEquals(ThresholdConfig.Direction.LOW, c.getDirection());
        assertEquals(10.0, c.getThreshold(),  0.001);
        assertEquals(20.0, c.getRearmValue(), 0.001);
        assertEquals(1,    c.getTriggerCount());
        assertEquals(1,    c.getRearmCount());
    }

    @Test
    void LOW方向_isExceeded低於閾值才超過() {
        ThresholdConfig c = new ThresholdConfig.Builder("temp")
            .direction(ThresholdConfig.Direction.LOW)
            .threshold(10.0)
            .rearmValue(20.0)
            .build();

        assertTrue(c.isExceeded(9.9));
        assertFalse(c.isExceeded(10.0));
        assertFalse(c.isExceeded(15.0));
    }

    @Test
    void LOW方向_isRearmed高於rearmValue才恢復() {
        ThresholdConfig c = new ThresholdConfig.Builder("temp")
            .direction(ThresholdConfig.Direction.LOW)
            .threshold(10.0)
            .rearmValue(20.0)
            .build();

        assertTrue(c.isRearmed(20.0));
        assertTrue(c.isRearmed(25.0));
        assertFalse(c.isRearmed(19.9));
    }
}
