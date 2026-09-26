package com.syscom.snmp.trap;

import com.syscom.snmp.config.DeviceConfig;
import com.syscom.snmp.config.TrapOidMapping;
import com.syscom.snmp.storage.MetricStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TrapReceiverReloadTest {

    @Mock
    MetricStore store;

    TrapReceiver receiver;

    @BeforeEach
    void setUp() {
        List<DeviceConfig> initialDevices = List.of(
            new DeviceConfig("Router-01", "10.0.0.1", "public"),
            new DeviceConfig("Switch-01", "10.0.0.2", "public")
        );
        receiver = new TrapReceiver(1162, List.of(), store,
            TrapOidMapping.defaults(), initialDevices);
    }

    @Test
    void 初始化_正確建立() {
        assertNotNull(receiver);
    }

    @Test
    void reloadDevices_新設備清單正常執行不拋例外() {
        List<DeviceConfig> newDevices = List.of(
            new DeviceConfig("Server-01", "192.168.1.10", "public"),
            new DeviceConfig("Server-02", "192.168.1.11", "public"),
            new DeviceConfig("Server-03", "192.168.1.12", "public")
        );
        assertDoesNotThrow(() -> receiver.reloadDevices(newDevices));
    }

    @Test
    void reloadDevices_空清單不拋例外() {
        assertDoesNotThrow(() -> receiver.reloadDevices(List.of()));
    }

    @Test
    void reloadDevices_可重複呼叫() {
        List<DeviceConfig> d1 = List.of(new DeviceConfig("A", "1.1.1.1", "public"));
        List<DeviceConfig> d2 = List.of(new DeviceConfig("B", "2.2.2.2", "public"));
        assertDoesNotThrow(() -> {
            receiver.reloadDevices(d1);
            receiver.reloadDevices(d2);
            receiver.reloadDevices(List.of());
        });
    }
}
