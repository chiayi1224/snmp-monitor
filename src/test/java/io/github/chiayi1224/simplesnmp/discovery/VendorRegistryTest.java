package io.github.chiayi1224.simplesnmp.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VendorRegistryTest {

    @Test
    void Cisco() {
        assertEquals("Cisco", VendorRegistry.identify("1.3.6.1.4.1.9.1.1208"));
        assertEquals("1.3.6.1.4.1.9", VendorRegistry.enterpriseSubtree("1.3.6.1.4.1.9.1.1208"));
    }

    @Test
    void NetSNMP() {
        assertEquals("Net-SNMP", VendorRegistry.identify("1.3.6.1.4.1.8072.3.2.10"));
    }

    @Test
    void 未知編號顯示編號本身() {
        assertEquals("99999", VendorRegistry.identify("1.3.6.1.4.1.99999.1"));
    }

    @Test
    void 不在enterprises底下回傳null() {
        assertNull(VendorRegistry.identify("1.3.6.1.2.1.1"));
        assertNull(VendorRegistry.enterpriseSubtree("1.3.6.1.2.1.1"));
        assertNull(VendorRegistry.identify(null));
        assertNull(VendorRegistry.identify("1.3.6.1.4.1.abc"));
    }

    @Test
    void 只有企業編號沒有子節點() {
        assertEquals("Juniper", VendorRegistry.identify("1.3.6.1.4.1.2636"));
    }
}
