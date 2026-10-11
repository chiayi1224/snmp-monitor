package io.github.chiayi1224.simplesnmp.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * spec「設備套用範本的規則」的四個情境，以及範本內容驗證
 */
class ProfileResolverTest {

    private static List<MetricProfile> builtins() {
        List<MetricProfile> list = new ArrayList<>();
        long id = 1;
        for (MetricProfile p : BuiltinProfiles.all()) list.add(p.withId(id++));
        return list;
    }

    private static MetricProfile custom(long id, String prefix) {
        return new MetricProfile(id, "Custom-" + id, "", prefix, false, List.of());
    }

    @Test
    void 自動套用_Cisco() {
        ProfileResolver r = new ProfileResolver(builtins());
        assertEquals(BuiltinProfiles.CISCO_IOS, r.resolve(null, "1.3.6.1.4.1.9.1.1208").name());
    }

    @Test
    void 較長前綴優先() {
        List<MetricProfile> all = builtins();
        all.add(custom(10, "1.3.6.1.4.1.9.1.1208"));
        ProfileResolver r = new ProfileResolver(all);

        assertEquals("Custom-10", r.resolve(null, "1.3.6.1.4.1.9.1.1208").name());
        assertEquals(BuiltinProfiles.CISCO_IOS, r.resolve(null, "1.3.6.1.4.1.9.1.999").name());
    }

    @Test
    void 手動指定優先() {
        ProfileResolver r = new ProfileResolver(builtins());
        long hostResId = builtins().stream().filter(p -> p.name().equals(BuiltinProfiles.HOST_RESOURCES)).findFirst().orElseThrow().id();

        assertEquals(BuiltinProfiles.HOST_RESOURCES, r.resolve(hostResId, "1.3.6.1.4.1.9.1.1208").name());
    }

    @Test
    void 手動指定的範本已刪除時改回自動() {
        ProfileResolver r = new ProfileResolver(builtins());
        assertEquals(BuiltinProfiles.NET_SNMP, r.resolve(999L, "1.3.6.1.4.1.8072.3.2.10").name());
    }

    @Test
    void 都不符合時使用HOST_RESOURCES_包含未知sysObjectID() {
        ProfileResolver r = new ProfileResolver(builtins());
        assertEquals(BuiltinProfiles.HOST_RESOURCES, r.resolve(null, "1.3.6.1.4.1.99999.1").name());
        assertEquals(BuiltinProfiles.HOST_RESOURCES, r.resolve(null, "").name());
        assertEquals(BuiltinProfiles.HOST_RESOURCES, r.resolve(null, null).name());
    }

    @Test
    void 前綴必須以節點為界_不誤判相似編號() {
        ProfileResolver r = new ProfileResolver(builtins());
        // 1.3.6.1.4.1.90 不應被 Cisco（1.3.6.1.4.1.9）匹配
        assertEquals(BuiltinProfiles.HOST_RESOURCES, r.resolve(null, "1.3.6.1.4.1.90.1").name());
    }

    // ─── 範本驗證 ─────────────────────────────────────────────────

    private static ProfileMetric metric(String key, MetricKind kind, Map<String, String> params) {
        return new ProfileMetric(key, kind, params, "%", true);
    }

    @Test
    void 內建範本皆合法() {
        for (MetricProfile p : BuiltinProfiles.all()) assertNull(p.validate(), p.name());
    }

    @Test
    void 驗證_指標名稱不合法() {
        MetricProfile p = new MetricProfile(null, "X", "", "", false,
            List.of(metric("CPU Temp", MetricKind.VALUE, Map.of("oid", "1.1"))));
        assertTrue(p.validate().contains("不合法"));
    }

    @Test
    void 驗證_指標名稱重複() {
        MetricProfile p = new MetricProfile(null, "X", "", "", false, List.of(
            metric("cpu", MetricKind.VALUE, Map.of("oid", "1.1")),
            metric("cpu", MetricKind.VALUE, Map.of("oid", "1.2"))));
        assertTrue(p.validate().contains("重複"));
    }

    @Test
    void 驗證_不支援的計算類型() {
        MetricProfile p = new MetricProfile(null, "X", "", "", false,
            List.of(metric("temp", MetricKind.parse("EXPRESSION"), Map.of())));
        assertTrue(p.validate().contains("不支援"));
    }

    @Test
    void 驗證_缺少必要OID() {
        MetricProfile p = new MetricProfile(null, "X", "", "", false,
            List.of(metric("memory", MetricKind.USED_TOTAL_PERCENT, Map.of("used", "1.1"))));
        assertTrue(p.validate().contains("total"));
    }

    @Test
    void 驗證_disk必須是多值STORAGE_TABLE_其他指標必須單值() {
        assertNotNull(new MetricProfile(null, "X", "", "", false,
            List.of(metric("disk", MetricKind.VALUE, Map.of("oid", "1.1")))).validate());
        assertNotNull(new MetricProfile(null, "X", "", "", false,
            List.of(metric("memory", MetricKind.STORAGE_TABLE, Map.of("typeFilter", "1.3.6.1.2.1.25.2.1.2")))).validate());
    }

    @Test
    void 驗證_device_unreachable為保留名稱() {
        assertNotNull(new MetricProfile(null, "X", "", "", false,
            List.of(metric("device_unreachable", MetricKind.VALUE, Map.of("oid", "1.1")))).validate());
        assertTrue(ProfileMetric.isReservedKey("device_unreachable"));
    }

    @Test
    void 驗證_前綴格式與名稱必填() {
        assertNotNull(new MetricProfile(null, "X", "", "1.3.x", false, List.of()).validate());
        assertNotNull(new MetricProfile(null, " ", "", "", false, List.of()).validate());
    }
}
