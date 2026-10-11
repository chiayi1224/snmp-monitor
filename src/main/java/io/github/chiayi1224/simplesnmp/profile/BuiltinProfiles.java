package io.github.chiayi1224.simplesnmp.profile;

import io.github.chiayi1224.simplesnmp.i18n.Messages;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
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
        return new MetricProfile(null, NET_SNMP, seedDescription(NET_SNMP),
            "1.3.6.1.4.1.8072", true, List.of(
                new ProfileMetric(ProfileMetric.CPU, MetricKind.HUNDRED_MINUS, Map.of("oid", OidConstants.CPU_IDLE), "%", true),
                new ProfileMetric(ProfileMetric.MEMORY, MetricKind.UCD_MEMORY, Map.of(), "%", true),
                FIXED_DISKS));
    }

    /** 沒有其他範本符合時的預設（HOST-RESOURCES-MIB，多數作業系統與部分網路設備支援） */
    public static MetricProfile hostResources() {
        return new MetricProfile(null, HOST_RESOURCES, seedDescription(HOST_RESOURCES),
            "", true, List.of(
                new ProfileMetric(ProfileMetric.CPU, MetricKind.TABLE_AVERAGE, Map.of("column", HR_PROCESSOR_LOAD), "%", true),
                new ProfileMetric(ProfileMetric.MEMORY, MetricKind.STORAGE_TABLE,
                    Map.of("typeFilter", HR_STORAGE_RAM, "first", "true"), "%", true),
                FIXED_DISKS));
    }

    /** Cisco IOS：CISCO-PROCESS-MIB 5 分鐘平均 CPU、CISCO-MEMORY-POOL-MIB processor pool */
    public static MetricProfile ciscoIos() {
        return new MetricProfile(null, CISCO_IOS, seedDescription(CISCO_IOS),
            "1.3.6.1.4.1.9", true, List.of(
                new ProfileMetric(ProfileMetric.CPU, MetricKind.TABLE_AVERAGE, Map.of("column", CISCO_CPU_5MIN_REV), "%", true),
                new ProfileMetric(ProfileMetric.MEMORY, MetricKind.USED_FREE_PERCENT,
                    Map.of("used", CISCO_MEM_POOL_USED_1, "free", CISCO_MEM_POOL_FREE_1), "%", true)));
    }

    /** 內建範本的識別名稱 → 訊息代碼用的鍵（名稱是資料庫中的識別值，不可變更） */
    private static final Map<String, String> KEYS = Map.of(
        NET_SNMP, "net_snmp", HOST_RESOURCES, "host_resources", CISCO_IOS, "cisco_ios");

    /** 內建範本名稱對應的鍵；不是內建範本回傳 null */
    public static String keyOf(String name) {
        return KEYS.get(name);
    }

    /** 顯示名稱：內建範本依目前請求語系翻譯，其他範本為原名稱 */
    public static String displayName(MetricProfile p) {
        String key = p.builtin() ? keyOf(p.name()) : null;
        return key == null ? p.name() : Messages.get(Msg.of("profile.builtin." + key + ".name"));
    }

    /** 顯示說明：內建範本依目前請求語系翻譯，其他範本為原說明 */
    public static String displayDescription(MetricProfile p) {
        String key = p.builtin() ? keyOf(p.name()) : null;
        return key == null ? p.description() : Messages.get(Msg.of("profile.builtin." + key + ".description"));
    }

    /** 寫入資料庫的說明：正體中文 */
    private static String seedDescription(String name) {
        return Messages.get(Msg.of("profile.builtin." + KEYS.get(name) + ".description"), Messages.ZH_TW);
    }

    public static List<MetricProfile> all() {
        return List.of(netSnmp(), hostResources(), ciscoIos());
    }

    private BuiltinProfiles() {}
}
