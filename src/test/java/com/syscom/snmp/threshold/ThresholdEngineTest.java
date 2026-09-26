package com.syscom.snmp.threshold;

import com.syscom.snmp.model.DiskInfo;
import com.syscom.snmp.storage.MetricStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
}
