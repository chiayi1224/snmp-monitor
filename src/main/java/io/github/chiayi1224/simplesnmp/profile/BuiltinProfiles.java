package io.github.chiayi1224.simplesnmp.profile;

import io.github.chiayi1224.simplesnmp.config.OidConstants;

import java.util.List;
import java.util.Map;

/**
 * 內建監控範本（程式定義，啟動時同步到資料庫，不可修改或刪除，可複製後編輯）
 */
public final class BuiltinProfiles {

    public static final String NET_SNMP       = "Net-SNMP（UCD-SNMP）";
    public static final String HOST_RESOURCES = "HOST-RESOURCES 通用";
    public static final String CISCO_IOS      = "Cisco IOS";

    static final String HR_PROCESSOR_LOAD     = "1.3.6.1.2.1.25.3.3.1.2";
    static final String HR_STORAGE_RAM        = "1.3.6.1.2.1.25.2.1.2";
    static final String CISCO_CPU_5MIN_REV    = "1.3.6.1.4.1.9.9.109.1.1.1.1.8";
    static final String CISCO_MEM_POOL_USED_1 = "1.3.6.1.4.1.9.9.48.1.1.1.5.1";
    static final String CISCO_MEM_POOL_FREE_1 = "1.3.6.1.4.1.9.9.48.1.1.1.6.1";

    private static final ProfileMetric FIXED_DISKS = new ProfileMetric(ProfileMetric.DISK, MetricKind.STORAGE_TABLE,
        Map.of("typeFilter", OidConstants.HR_STORAGE_TYPE_FIXED_DISK), "%", true);

    /** 與升級前寫死的 Poller 邏輯完全相同（ssCpuIdle、UCD 記憶體公式、hrStorage 固定磁碟） */
    public static MetricProfile netSnmp() {
        return new MetricProfile(null, NET_SNMP, "Linux / Unix 的 Net-SNMP agent（UCD-SNMP-MIB）",
            "1.3.6.1.4.1.8072", true, List.of(
                new ProfileMetric(ProfileMetric.CPU, MetricKind.HUNDRED_MINUS, Map.of("oid", OidConstants.CPU_IDLE), "%", true),
                new ProfileMetric(ProfileMetric.MEMORY, MetricKind.UCD_MEMORY, Map.of(), "%", true),
                FIXED_DISKS));
    }

    /** 沒有其他範本符合時的預設（HOST-RESOURCES-MIB，多數作業系統與部分網路設備支援） */
    public static MetricProfile hostResources() {
        return new MetricProfile(null, HOST_RESOURCES, "沒有其他範本符合時的預設，使用 HOST-RESOURCES-MIB",
            "", true, List.of(
                new ProfileMetric(ProfileMetric.CPU, MetricKind.TABLE_AVERAGE, Map.of("column", HR_PROCESSOR_LOAD), "%", true),
                new ProfileMetric(ProfileMetric.MEMORY, MetricKind.STORAGE_TABLE,
                    Map.of("typeFilter", HR_STORAGE_RAM, "first", "true"), "%", true),
                FIXED_DISKS));
    }

    /** Cisco IOS：CISCO-PROCESS-MIB 5 分鐘平均 CPU、CISCO-MEMORY-POOL-MIB processor pool */
    public static MetricProfile ciscoIos() {
        return new MetricProfile(null, CISCO_IOS, "Cisco IOS 路由器與交換器",
            "1.3.6.1.4.1.9", true, List.of(
                new ProfileMetric(ProfileMetric.CPU, MetricKind.TABLE_AVERAGE, Map.of("column", CISCO_CPU_5MIN_REV), "%", true),
                new ProfileMetric(ProfileMetric.MEMORY, MetricKind.USED_FREE_PERCENT,
                    Map.of("used", CISCO_MEM_POOL_USED_1, "free", CISCO_MEM_POOL_FREE_1), "%", true)));
    }

    public static List<MetricProfile> all() {
        return List.of(netSnmp(), hostResources(), ciscoIos());
    }

    private BuiltinProfiles() {}
}
