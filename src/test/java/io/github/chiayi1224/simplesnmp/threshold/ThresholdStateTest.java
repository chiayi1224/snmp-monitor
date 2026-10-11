package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.threshold.ThresholdConfig.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多等級狀態機：每個 spec Scenario 一個測試。
 * 預設設定：Warning 80、Critical 95、恢復寬度 5、觸發連續 3 次、恢復連續 2 次。
 */
class ThresholdStateTest {

    private ThresholdConfig config;
    private ThresholdState  state;

    @BeforeEach
    void setUp() {
        config = cfg(80.0, 95.0);
        state  = new ThresholdState("Server-01", "cpu");
    }

    private static ThresholdConfig cfg(Double warning, Double critical) {
        return new ThresholdConfig.Builder("cpu").warning(warning).critical(critical)
            .rearmMargin(5).triggerCount(3).rearmCount(2).build();
    }

    /** 依序輸入，回傳所有產生的事件 */
    private List<AlertEvent> feed(double... values) {
        List<AlertEvent> events = new ArrayList<>();
        for (double v : values) {
            AlertEvent e = state.evaluate(config, v);
            if (e != null) events.add(e);
        }
        return events;
    }

    private static void assertEvent(AlertEvent e, AlertEvent.Type type, Severity severity) {
        assertEquals(type, e.getType());
        assertEquals(severity, e.getSeverity());
        assertEquals("Server-01", e.getDeviceName());
        assertEquals("cpu", e.getMetricName());
    }

    // ─── 觸發 ─────────────────────────────────────────────────────

    @Test
    void 觸發Warning() {
        List<AlertEvent> events = feed(85, 88, 90);
        assertEquals(1, events.size());
        assertEvent(events.get(0), AlertEvent.Type.TRIGGERED, Severity.WARNING);
        assertEquals(80.0, events.get(0).getThresholdVal(), 1e-9);
        assertEquals(Severity.WARNING, state.getLevel());
    }

    @Test
    void 直接觸發Critical_不會先產生Warning() {
        List<AlertEvent> events = feed(96, 97, 99);
        assertEquals(1, events.size());
        assertEvent(events.get(0), AlertEvent.Type.TRIGGERED, Severity.CRITICAL);
    }

    @Test
    void 混合時取每次都達到的等級() {
        List<AlertEvent> events = feed(96, 85, 97);
        assertEquals(1, events.size());
        assertEvent(events.get(0), AlertEvent.Type.TRIGGERED, Severity.WARNING);
    }

    @Test
    void 中途回到正常_不產生事件() {
        assertTrue(feed(85, 70, 85).isEmpty());
        assertNull(state.getLevel());
    }

    @Test
    void 只設定Critical時只在超過Critical時觸發() {
        config = cfg(null, 95.0);
        assertTrue(feed(90, 90, 90).isEmpty());
        assertEvent(feed(96, 96, 96).get(0), AlertEvent.Type.TRIGGERED, Severity.CRITICAL);
    }

    @Test
    void 觸發後持續超過不重複產生事件() {
        feed(85, 85, 85);
        assertTrue(feed(85, 85, 85, 85).isEmpty());
    }

    // ─── 升級 ─────────────────────────────────────────────────────

    @Test
    void Warning升為Critical() {
        feed(85, 85, 85);
        List<AlertEvent> events = feed(96, 97, 98);
        assertEquals(1, events.size());
        assertEvent(events.get(0), AlertEvent.Type.CHANGED, Severity.CRITICAL);
        assertEquals(Severity.CRITICAL, state.getLevel());
    }

    @Test
    void 升級需連續_中間夾Warning數值時重新計數() {
        feed(85, 85, 85);
        assertTrue(feed(96, 97, 85, 96, 97).isEmpty());
        assertEquals(Severity.WARNING, state.getLevel());
    }

    // ─── 降級與解除 ───────────────────────────────────────────────

    private void toCritical() {
        feed(96, 96, 96);
        assertEquals(Severity.CRITICAL, state.getLevel());
    }

    @Test
    void Critical降為Warning() {
        toCritical();
        List<AlertEvent> events = feed(88, 86);
        assertEquals(1, events.size());
        assertEvent(events.get(0), AlertEvent.Type.CHANGED, Severity.WARNING);
        assertEquals(Severity.WARNING, state.getLevel());
    }

    @Test
    void Critical直接解除() {
        toCritical();
        List<AlertEvent> events = feed(60, 50);
        assertEquals(1, events.size());
        assertEvent(events.get(0), AlertEvent.Type.RESOLVED, Severity.CRITICAL);
        assertNull(state.getLevel());
    }

    @Test
    void 恢復寬度內不降級() {
        toCritical();
        assertTrue(feed(92, 91).isEmpty(), "未低於 95 − 5 = 90");
        assertEquals(Severity.CRITICAL, state.getLevel());
    }

    @Test
    void 恢復需連續_中途再超過時重新計數() {
        toCritical();
        assertTrue(feed(60, 96, 60).isEmpty());
        assertEquals(Severity.CRITICAL, state.getLevel());
    }

    @Test
    void Warning解除() {
        feed(85, 85, 85);
        List<AlertEvent> events = feed(70, 72);
        assertEquals(1, events.size());
        assertEvent(events.get(0), AlertEvent.Type.RESOLVED, Severity.WARNING);
        assertEquals(75.0, events.get(0).getThresholdVal(), 1e-9, "RESOLVED 記錄恢復值");
    }

    @Test
    void 沒有Warning時自Critical恢復即解除() {
        config = cfg(null, 95.0);
        feed(96, 96, 96);
        assertEvent(feed(88, 86).get(0), AlertEvent.Type.RESOLVED, Severity.CRITICAL);
    }

    // ─── 設定變更 ─────────────────────────────────────────────────

    @Test
    void 移除Warning後下一次輪詢解除() {
        feed(85, 85, 85);
        config = cfg(null, 95.0);
        AlertEvent e = state.evaluate(config, 85);
        assertEvent(e, AlertEvent.Type.RESOLVED, Severity.WARNING);
        assertNull(state.getLevel());
    }

    @Test
    void 移除Warning但數值超過Critical時改為Critical() {
        feed(85, 85, 85);
        config = cfg(null, 95.0);
        assertEvent(state.evaluate(config, 97), AlertEvent.Type.CHANGED, Severity.CRITICAL);
    }

    @Test
    void 移除Critical但仍超過Warning時降為Warning() {
        toCritical();
        config = cfg(80.0, null);
        assertEvent(state.evaluate(config, 96), AlertEvent.Type.CHANGED, Severity.WARNING);
    }

    @Test
    void 修改觸發值時保留等級() {
        feed(85, 85, 85);
        config = cfg(82.0, 95.0);
        assertNull(state.evaluate(config, 85));
        assertEquals(Severity.WARNING, state.getLevel());
    }

    // ─── LOW 方向 ─────────────────────────────────────────────────

    @Test
    void LOW方向_觸發升級與解除() {
        config = new ThresholdConfig.Builder("free_ports").direction(Direction.LOW)
            .warning(10.0).critical(3.0).rearmMargin(2).triggerCount(2).rearmCount(2).build();
        assertEvent2(feed(8, 7).get(0), AlertEvent.Type.TRIGGERED, Severity.WARNING);
        assertEvent2(feed(2, 1).get(0), AlertEvent.Type.CHANGED, Severity.CRITICAL);
        assertEvent2(feed(6, 7).get(0), AlertEvent.Type.CHANGED, Severity.WARNING);
        assertEvent2(feed(12, 13).get(0), AlertEvent.Type.RESOLVED, Severity.WARNING);
    }

    private static void assertEvent2(AlertEvent e, AlertEvent.Type type, Severity severity) {
        assertEquals(type, e.getType());
        assertEquals(severity, e.getSeverity());
    }

    // ─── 還原與清除 ───────────────────────────────────────────────

    @Test
    void 以初始等級建立_持續超標不重複觸發_恢復時解除() {
        state = new ThresholdState("Server-01", "cpu", Severity.CRITICAL);
        assertTrue(feed(97, 97, 97, 97).isEmpty());
        assertEvent(feed(50, 50).get(0), AlertEvent.Type.RESOLVED, Severity.CRITICAL);
    }

    @Test
    void clear_有告警時回傳解除事件() {
        feed(85, 85, 85);
        AlertEvent e = state.clear("閾值已移除");
        assertEvent(e, AlertEvent.Type.RESOLVED, Severity.WARNING);
        assertEquals("閾值已移除", e.getMessage());
        assertNull(state.clear("x"), "已無告警時回傳 null");
    }

    @Test
    void restore_計數歸零() {
        feed(85, 85);           // 升級計數 2
        state.restore(null);
        assertTrue(feed(85, 85).isEmpty(), "計數已歸零，需重新連續 3 次");
    }

    @Test
    void message帶入事件() {
        state.evaluate(config, 85, "msg");
        state.evaluate(config, 85, "msg");
        assertEquals("msg", state.evaluate(config, 85, "msg").getMessage());
    }
}
