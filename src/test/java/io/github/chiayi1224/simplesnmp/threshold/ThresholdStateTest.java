package io.github.chiayi1224.simplesnmp.threshold;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 狀態機完整測試
 * CPU 設定：threshold=90%, rearmValue=80%, triggerCount=3, rearmCount=2
 */
class ThresholdStateTest {

    private ThresholdState state;

    @BeforeEach
    void setUp() {
        state = new ThresholdState("Server-01", ThresholdConfig.cpu());
    }

    // ─── 正常情境 ─────────────────────────────────────────────────

    @Test
    void 初始狀態為NORMAL() {
        assertEquals(ThresholdState.State.NORMAL, state.getState());
    }

    @Test
    void 未超過閾值保持NORMAL() {
        assertNull(state.evaluate(80.0));
        assertNull(state.evaluate(85.0));
        assertNull(state.evaluate(89.9));
        assertEquals(ThresholdState.State.NORMAL, state.getState());
    }

    // ─── TRIGGERING 防抖動 ────────────────────────────────────────

    @Test
    void 第一次超過進入TRIGGERING無事件() {
        AlertEvent e = state.evaluate(91.0);
        assertNull(e);
        assertEquals(ThresholdState.State.TRIGGERING, state.getState());
    }

    @Test
    void 第二次超過仍在TRIGGERING無事件() {
        state.evaluate(91.0);
        AlertEvent e = state.evaluate(91.0);
        assertNull(e);
        assertEquals(ThresholdState.State.TRIGGERING, state.getState());
    }

    @Test
    void 連續三次超過觸發TRIGGERED事件() {
        state.evaluate(91.0);
        state.evaluate(91.0);
        AlertEvent e = state.evaluate(92.0);

        assertNotNull(e);
        assertEquals(AlertEvent.Type.TRIGGERED, e.getType());
        assertEquals("Server-01", e.getDeviceName());
        assertEquals("cpu",       e.getMetricName());
        assertEquals(92.0,        e.getValue(), 0.001);
        assertEquals(90.0,        e.getThresholdVal(), 0.001);
        assertEquals(ThresholdState.State.TRIGGERED, state.getState());
    }

    @Test
    void TRIGGERING中途恢復重置計數回NORMAL() {
        state.evaluate(91.0); // 1次
        state.evaluate(91.0); // 2次
        assertNull(state.evaluate(80.0)); // 恢復，reset
        assertEquals(ThresholdState.State.NORMAL, state.getState());

        // 重新累積需要再 3 次
        state.evaluate(91.0);
        state.evaluate(91.0);
        AlertEvent e = state.evaluate(91.0);
        assertNotNull(e);
        assertEquals(AlertEvent.Type.TRIGGERED, e.getType());
    }

    // ─── TRIGGERED 狀態持續超過 ───────────────────────────────────

    @Test
    void TRIGGERED後持續超過不重複觸發() {
        triggerAlert();
        assertNull(state.evaluate(95.0));
        assertNull(state.evaluate(95.0));
        assertEquals(ThresholdState.State.TRIGGERED, state.getState());
    }

    // ─── REARMING 防抖動 ─────────────────────────────────────────

    @Test
    void TRIGGERED後第一次恢復進入REARMING無事件() {
        triggerAlert();
        AlertEvent e = state.evaluate(79.0);
        assertNull(e);
        assertEquals(ThresholdState.State.REARMING, state.getState());
    }

    @Test
    void TRIGGERED後連續兩次恢復觸發RESOLVED事件() {
        triggerAlert();
        state.evaluate(79.0);
        AlertEvent e = state.evaluate(78.0);

        assertNotNull(e);
        assertEquals(AlertEvent.Type.RESOLVED, e.getType());
        assertEquals("Server-01", e.getDeviceName());
        assertEquals("cpu",       e.getMetricName());
        assertEquals(78.0,        e.getValue(), 0.001);
        assertEquals(ThresholdState.State.NORMAL, state.getState());
    }

    @Test
    void REARMING中途再次超過重置rearm計數() {
        triggerAlert();
        state.evaluate(79.0); // rearm 1次
        assertNull(state.evaluate(92.0)); // 又超過，reset rearm
        assertEquals(ThresholdState.State.TRIGGERED, state.getState());

        // 重新恢復需再 2 次
        state.evaluate(79.0);
        AlertEvent e = state.evaluate(79.0);
        assertNotNull(e);
        assertEquals(AlertEvent.Type.RESOLVED, e.getType());
    }

    // ─── 完整生命週期 ─────────────────────────────────────────────

    @Test
    void 完整NORMAL到TRIGGERED到NORMAL循環() {
        // 觸發
        triggerAlert();
        assertEquals(ThresholdState.State.TRIGGERED, state.getState());

        // 恢復
        state.evaluate(79.0);
        AlertEvent resolved = state.evaluate(79.0);
        assertNotNull(resolved);
        assertEquals(AlertEvent.Type.RESOLVED, resolved.getType());
        assertEquals(ThresholdState.State.NORMAL, state.getState());

        // 再次觸發
        state.evaluate(91.0);
        state.evaluate(91.0);
        AlertEvent reTrigger = state.evaluate(91.0);
        assertNotNull(reTrigger);
        assertEquals(AlertEvent.Type.TRIGGERED, reTrigger.getType());
    }

    // ─── Getter ───────────────────────────────────────────────────

    @Test
    void getLastValue回傳最後量測值() {
        state.evaluate(85.5);
        assertEquals(85.5, state.getLastValue(), 0.001);
    }

    @Test
    void getDeviceName和getMetricName正確() {
        assertEquals("Server-01", state.getDeviceName());
        assertEquals("cpu",       state.getMetricName());
    }

    // ─── 工具方法 ─────────────────────────────────────────────────

    private void triggerAlert() {
        state.evaluate(91.0);
        state.evaluate(91.0);
        state.evaluate(91.0);
    }
}
