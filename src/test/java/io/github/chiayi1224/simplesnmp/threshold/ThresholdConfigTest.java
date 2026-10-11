package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThresholdConfigTest {

    private static ThresholdConfig high(Double warning, Double critical, double margin) {
        return new ThresholdConfig.Builder("cpu").warning(warning).critical(critical).rearmMargin(margin).build();
    }

    // ─── 等級判定 ─────────────────────────────────────────────────

    @Test
    void rawLevel_HIGH() {
        ThresholdConfig c = high(80.0, 95.0, 5);
        assertNull(c.rawLevel(80.0), "等於觸發值不算超過");
        assertEquals(Severity.WARNING, c.rawLevel(80.1));
        assertEquals(Severity.WARNING, c.rawLevel(95.0));
        assertEquals(Severity.CRITICAL, c.rawLevel(95.1));
    }

    @Test
    void rawLevel_只有Critical() {
        ThresholdConfig c = high(null, 95.0, 5);
        assertNull(c.rawLevel(90.0));
        assertEquals(Severity.CRITICAL, c.rawLevel(96.0));
    }

    @Test
    void rawLevel_LOW() {
        ThresholdConfig c = new ThresholdConfig.Builder("free_ports").direction(Direction.LOW)
            .warning(10.0).critical(3.0).build();
        assertNull(c.rawLevel(10.0));
        assertEquals(Severity.WARNING, c.rawLevel(9.0));
        assertEquals(Severity.CRITICAL, c.rawLevel(2.0));
    }

    @Test
    void recoveredFrom_含恢復寬度() {
        ThresholdConfig c = high(80.0, 95.0, 5);
        assertFalse(c.recoveredFrom(Severity.CRITICAL, 90.1));
        assertTrue(c.recoveredFrom(Severity.CRITICAL, 90.0));
        assertFalse(c.recoveredFrom(Severity.WARNING, 75.1));
        assertTrue(c.recoveredFrom(Severity.WARNING, 75.0));
        assertEquals(90.0, c.recoveryValue(Severity.CRITICAL), 1e-9);
    }

    @Test
    void recoveredFrom_LOW方向() {
        ThresholdConfig c = new ThresholdConfig.Builder("free_ports").direction(Direction.LOW)
            .critical(3.0).rearmMargin(2).build();
        assertFalse(c.recoveredFrom(Severity.CRITICAL, 4.9));
        assertTrue(c.recoveredFrom(Severity.CRITICAL, 5.0));
    }

    @Test
    void recoveredFrom_未設定的等級視為已恢復() {
        assertTrue(high(null, 95.0, 5).recoveredFrom(Severity.WARNING, 99.0));
    }

    // ─── 驗證 ─────────────────────────────────────────────────────

    private static String validate(String metric, Direction dir, Double w, Double c) {
        return ThresholdConfig.validate(metric, dir, w, c, 5, 3, 2);
    }

    @Test
    void validate_合法() {
        assertNull(validate("cpu", Direction.HIGH, 80.0, 95.0));
        assertNull(validate("memory", Direction.HIGH, null, 85.0));
        assertNull(validate("disk:/data", Direction.HIGH, 70.0, null));
        assertNull(validate("free_ports", Direction.LOW, 10.0, 3.0));
        assertNull(validate("temperature", Direction.HIGH, 60.0, 500.0), "自訂指標不限百分比");
    }

    @Test
    void validate_兩個觸發值都沒有() {
        assertNotNull(validate("cpu", Direction.HIGH, null, null));
    }

    @Test
    void validate_HIGH時Warning須小於Critical() {
        assertTrue(validate("cpu", Direction.HIGH, 95.0, 90.0).contains("Warning"));
        assertNotNull(validate("cpu", Direction.HIGH, 90.0, 90.0));
    }

    @Test
    void validate_LOW時Warning須大於Critical() {
        assertNotNull(validate("free_ports", Direction.LOW, 3.0, 10.0));
    }

    @Test
    void validate_百分比指標方向固定HIGH() {
        assertNotNull(validate("cpu", Direction.LOW, 20.0, 10.0));
        assertNotNull(validate("disk:/", Direction.LOW, 20.0, 10.0));
    }

    @Test
    void validate_百分比指標範圍() {
        assertNotNull(validate("disk", Direction.HIGH, null, 120.0));
        assertNotNull(validate("cpu", Direction.HIGH, 0.0, 90.0));
        assertNull(validate("cpu", Direction.HIGH, null, 100.0));
    }

    @Test
    void validate_恢復寬度與次數() {
        assertNotNull(ThresholdConfig.validate("cpu", Direction.HIGH, null, 90.0, -1, 3, 2));
        assertNull(ThresholdConfig.validate("cpu", Direction.HIGH, null, 90.0, 0, 3, 2));
        assertNotNull(ThresholdConfig.validate("cpu", Direction.HIGH, null, 90.0, 5, 0, 2));
        assertNotNull(ThresholdConfig.validate("cpu", Direction.HIGH, null, 90.0, 5, 3, 0));
    }

    @Test
    void validate_方向不合法與非數字() {
        assertNotNull(validate("cpu", null, null, 90.0));
        assertNotNull(validate("temperature", Direction.HIGH, Double.NaN, null));
    }

    @Test
    void Direction_解析() {
        assertEquals(Direction.HIGH, Direction.parse(null));
        assertEquals(Direction.LOW, Direction.parse("low"));
        assertNull(Direction.parse("SIDEWAYS"));
    }
}
