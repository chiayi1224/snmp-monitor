package io.github.chiayi1224.simplesnmp.trap;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.TrapOidMapping.Mapping;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.threshold.AlertEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrapReceiverHandleTest {

    private static final String TRAP_OID_KEY  = "1.3.6.1.6.3.1.1.4.1.0";
    private static final String CPU_HIGH      = "1.3.6.1.4.1.9999.1.1";
    private static final String CPU_NORMAL    = "1.3.6.1.4.1.9999.1.2";
    private static final String UNDEFINED_OID = "1.3.6.1.4.1.9999.9.9";

    @Mock MetricStore store;

    TrapReceiver receiver;

    @BeforeEach
    void setUp() {
        receiver = new TrapReceiver(1162, List.of(), store,
            List.of(new Mapping(CPU_HIGH, CPU_NORMAL, "cpu", "CPU 過高")),
            List.of(new DeviceConfig("Router-01", "10.0.0.1", "public")));
    }

    private static Map<String, String> bindings(String trapOid, String... extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("1.3.6.1.2.1.1.3.0", "12345");
        m.put(TRAP_OID_KEY, trapOid);
        for (int i = 0; i + 1 < extra.length; i += 2) m.put(extra[i], extra[i + 1]);
        return m;
    }

    @Test
    void 已登錄設備_triggered_寫入告警並使用設備名稱() {
        receiver.handleTrap("10.0.0.1", bindings(CPU_HIGH));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store).saveAlertEvent(captor.capture());
        assertEquals("Router-01", captor.getValue().getDeviceName());
        assertEquals("cpu",       captor.getValue().getMetricName());
        assertEquals(AlertEvent.Type.TRIGGERED, captor.getValue().getType());
        verify(store, never()).saveUnknownSource(any(), any(), any());
    }

    @Test
    void triggered_已有開放告警時不重複寫入() {
        when(store.hasOpenAlert("Router-01", "cpu")).thenReturn(true);

        receiver.handleTrap("10.0.0.1", bindings(CPU_HIGH));

        verify(store, never()).saveAlertEvent(any());
    }

    @Test
    void resolved_有開放告警時寫入恢復事件() {
        when(store.hasOpenAlert("Router-01", "cpu")).thenReturn(true);

        receiver.handleTrap("10.0.0.1", bindings(CPU_NORMAL));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store).saveAlertEvent(captor.capture());
        assertEquals(AlertEvent.Type.RESOLVED, captor.getValue().getType());
    }

    @Test
    void resolved_無開放告警時略過() {
        receiver.handleTrap("10.0.0.1", bindings(CPU_NORMAL));

        verify(store, never()).saveAlertEvent(any());
    }

    @Test
    void 未定義OID_記錄到unknown_traps() {
        receiver.handleTrap("10.0.0.1", bindings(UNDEFINED_OID));

        verify(store).saveUnknownTrap(eq(UNDEFINED_OID), eq("Router-01"), eq("10.0.0.1"), anyString());
        verify(store, never()).saveAlertEvent(any());
    }

    @Test
    void 未登錄來源_記錄unknown_sources並以IP當設備名稱() {
        receiver.handleTrap("10.9.9.9", bindings(CPU_HIGH));

        verify(store).saveUnknownSource(eq("10.9.9.9"), eq(CPU_HIGH), anyString());
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(store).saveAlertEvent(captor.capture());
        assertEquals("10.9.9.9", captor.getValue().getDeviceName());
    }

    @Test
    void 無snmpTrapOID_v1_直接略過() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("1.3.6.1.2.1.1.3.0", "12345");

        receiver.handleTrap("10.0.0.1", m);

        verifyNoInteractions(store);
    }

    @Test
    void 訊息依PDU順序組合且排除snmpTrapOID() {
        receiver.handleTrap("10.0.0.1", bindings(UNDEFINED_OID, "1.3.6.1.4.1.9999.2", "b", "1.3.6.1.4.1.9999.1", "a"));

        verify(store).saveUnknownTrap(UNDEFINED_OID, "Router-01", "10.0.0.1",
            "1.3.6.1.2.1.1.3.0=12345; 1.3.6.1.4.1.9999.2=b; 1.3.6.1.4.1.9999.1=a");
    }

    @Test
    void reloadDevices後_立即以新清單辨識來源() {
        receiver.reloadDevices(List.of(new DeviceConfig("Server-01", "10.0.0.5", "public")));

        receiver.handleTrap("10.0.0.5", bindings(CPU_HIGH));
        receiver.handleTrap("10.0.0.1", bindings(CPU_HIGH));

        verify(store).saveUnknownSource(eq("10.0.0.1"), eq(CPU_HIGH), anyString());
        verify(store, never()).saveUnknownSource(eq("10.0.0.5"), any(), any());
    }

    @Test
    void reloadMappings後_立即以新對應表處理() {
        receiver.reloadMappings(List.of(new Mapping(UNDEFINED_OID, null, "memory", "")));

        receiver.handleTrap("10.0.0.1", bindings(UNDEFINED_OID));
        receiver.handleTrap("10.0.0.1", bindings(CPU_HIGH));

        verify(store).saveAlertEvent(any());
        verify(store).saveUnknownTrap(eq(CPU_HIGH), any(), any(), any());
    }
}
