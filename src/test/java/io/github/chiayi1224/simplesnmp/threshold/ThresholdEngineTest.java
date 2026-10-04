package io.github.chiayi1224.simplesnmp.threshold;

import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThresholdEngineTest {

    @Mock
    MetricStore store;

    ThresholdEngine engine;

    @BeforeEach
    void setUp() {
        // 讓 mock 回傳對應的預設閾值設定，避免 NPE（lenient 允許部分測試未使用所有 stub）
        lenient().when(store.loadThresholdConfig("cpu")).thenReturn(ThresholdConfig.cpu());
        lenient().when(store.loadThresholdConfig("memory")).thenReturn(ThresholdConfig.memory());
        lenient().when(store.loadThresholdConfig(startsWith("disk:"))).thenAnswer(inv ->
            ThresholdConfig.disk(inv.getArgument(0, String.class).substring("disk:".length()))
        );
        engine = new ThresholdEngine(store);
    }

    // ─── CPU ──────────────────────────────────────────────────────

    @Test
    void evaluateCpu_未超過閾值不寫DB() {
        engine.evaluateCpu("Server-01", 85.0);
        engine.evaluateCpu("Server-01", 88.0);
        verify(store, never()).saveAlertEvent(any());
    }

    @Test
    void evaluateCpu_連續三次超過寫一次DB() {
        engine.evaluateCpu("Server-01", 91.0);
        engine.evaluateCpu("Server-01", 92.0);
        engine.evaluateCpu("Server-01", 93.0);
        verify(store, times(1)).saveAlertEvent(any());
    }

    @Test
    void evaluateCpu_觸發後持續超過不重複寫DB() {
        engine.evaluateCpu("Server-01", 91.0);
        engine.evaluateCpu("Server-01", 92.0);
        engine.evaluateCpu("Server-01", 93.0); // 觸發
        engine.evaluateCpu("Server-01", 95.0);
        engine.evaluateCpu("Server-01", 95.0);
        verify(store, times(1)).saveAlertEvent(any());
    }

    @Test
    void evaluateCpu_觸發後恢復寫RESOLVED() {
        // 觸發
        engine.evaluateCpu("Server-01", 91.0);
        engine.evaluateCpu("Server-01", 91.0);
        engine.evaluateCpu("Server-01", 91.0);
        // 恢復（需連續 2 次）
        engine.evaluateCpu("Server-01", 79.0);
        engine.evaluateCpu("Server-01", 79.0);
        // 共 2 次：1 TRIGGERED + 1 RESOLVED
        verify(store, times(2)).saveAlertEvent(any());
    }

    @Test
    void evaluateCpu_不同設備互相獨立() {
        // Server-A 觸發
        engine.evaluateCpu("Server-A", 91.0);
        engine.evaluateCpu("Server-A", 91.0);
        engine.evaluateCpu("Server-A", 91.0);

        // Server-B 正常
        engine.evaluateCpu("Server-B", 70.0);
        engine.evaluateCpu("Server-B", 70.0);

        verify(store, times(1)).saveAlertEvent(any());
    }

    // ─── Memory ───────────────────────────────────────────────────

    @Test
    void evaluateMemory_連續三次超過寫DB() {
        engine.evaluateMemory("Server-01", 86.0);
        engine.evaluateMemory("Server-01", 87.0);
        engine.evaluateMemory("Server-01", 88.0);
        verify(store, times(1)).saveAlertEvent(any());
    }

    @Test
    void evaluateMemory_CPU和Memory狀態互相獨立() {
        // CPU 觸發
        engine.evaluateCpu("Server-01", 91.0);
        engine.evaluateCpu("Server-01", 91.0);
        engine.evaluateCpu("Server-01", 91.0);
        // Memory 未達到 3 次
        engine.evaluateMemory("Server-01", 86.0);
        engine.evaluateMemory("Server-01", 86.0);

        verify(store, times(1)).saveAlertEvent(any()); // 只有 CPU 觸發
    }

    // ─── Disk ─────────────────────────────────────────────────────

    @Test
    void evaluateDisks_未超過不寫DB() {
        List<DiskInfo> disks = List.of(
            new DiskInfo("/", 1000L, 700L)  // 70%
        );
        engine.evaluateDisks("Server-01", disks);
        engine.evaluateDisks("Server-01", disks);
        verify(store, never()).saveAlertEvent(any());
    }

    @Test
    void evaluateDisks_磁碟超過閾值連續兩次觸發() {
        // Disk triggerCount=2
        List<DiskInfo> disks = List.of(
            new DiskInfo("/", 1000L, 850L)  // 85%
        );
        engine.evaluateDisks("Server-01", disks);
        engine.evaluateDisks("Server-01", disks);
        verify(store, times(1)).saveAlertEvent(any());
    }

    @Test
    void evaluateDisks_多個掛載點各自追蹤() {
        // root 超過，home 正常
        List<DiskInfo> disks = List.of(
            new DiskInfo("/",     1000L, 850L),  // 85% 超過
            new DiskInfo("/home", 1000L, 700L)   // 70% 正常
        );
        engine.evaluateDisks("Server-01", disks);
        engine.evaluateDisks("Server-01", disks); // disk triggerCount=2

        verify(store, times(1)).saveAlertEvent(any()); // 只有 / 觸發
    }

    // ─── 自訂指標 ─────────────────────────────────────────────────

    @Test
    void evaluate_自訂HIGH方向連續達標才觸發() {
        when(store.loadThresholdConfig("temperature")).thenReturn(new ThresholdConfig.Builder("temperature")
            .threshold(70).rearmValue(65).triggerCount(3).rearmCount(2).build());

        engine.evaluate("SW-01", "temperature", 75);
        engine.evaluate("SW-01", "temperature", 75);
        verify(store, never()).saveAlertEvent(any());
        engine.evaluate("SW-01", "temperature", 75);

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store).saveAlertEvent(captor.capture());
        assertEquals(AlertEvent.Type.TRIGGERED, captor.getValue().getType());
        assertEquals("temperature", captor.getValue().getMetricName());
    }

    @Test
    void evaluate_自訂LOW方向低於門檻觸發_高於恢復值解除() {
        when(store.loadThresholdConfig("free_ports")).thenReturn(new ThresholdConfig.Builder("free_ports")
            .direction(ThresholdConfig.Direction.LOW).threshold(5).rearmValue(8).triggerCount(2).rearmCount(2).build());

        engine.evaluate("SW-01", "free_ports", 2);
        engine.evaluate("SW-01", "free_ports", 2);   // 觸發
        engine.evaluate("SW-01", "free_ports", 6);   // 已高於觸發值但未達恢復值，不解除
        engine.evaluate("SW-01", "free_ports", 6);
        verify(store, times(1)).saveAlertEvent(any());
        engine.evaluate("SW-01", "free_ports", 9);
        engine.evaluate("SW-01", "free_ports", 9);   // 解除

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store, times(2)).saveAlertEvent(captor.capture());
        assertEquals(AlertEvent.Type.TRIGGERED, captor.getAllValues().get(0).getType());
        assertEquals(AlertEvent.Type.RESOLVED,  captor.getAllValues().get(1).getType());
    }

    @Test
    void evaluate_沒有閾值只略過不告警() {
        when(store.loadThresholdConfig("uptime_days")).thenReturn(null);
        for (int i = 0; i < 5; i++) engine.evaluate("SW-01", "uptime_days", 999);
        verify(store, never()).saveAlertEvent(any());
    }

    @Test
    void 閾值修改後下次評估立即生效() {
        engine.evaluateCpu("Server-01", 85.0);
        engine.evaluateCpu("Server-01", 85.0);   // 原本 90% 不超標
        when(store.loadThresholdConfig("cpu")).thenReturn(new ThresholdConfig.Builder("cpu")
            .threshold(80).rearmValue(70).triggerCount(1).rearmCount(1).build());
        engine.evaluateCpu("Server-01", 85.0);
        verify(store, times(1)).saveAlertEvent(any());
    }
}
