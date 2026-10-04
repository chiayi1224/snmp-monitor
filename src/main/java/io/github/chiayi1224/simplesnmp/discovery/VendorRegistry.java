package io.github.chiayi1224.simplesnmp.discovery;

import java.util.Map;

/**
 * 依 sysObjectID 的企業編號（1.3.6.1.4.1.<n>）辨識廠牌。
 * 編號來源：IANA Private Enterprise Numbers。
 */
public final class VendorRegistry {

    /** SNMP enterprises 節點 */
    public static final String ENTERPRISES = "1.3.6.1.4.1";

    private static final Map<Integer, String> VENDORS = Map.ofEntries(
        Map.entry(2,     "IBM"),
        Map.entry(9,     "Cisco"),
        Map.entry(11,    "HP"),
        Map.entry(43,    "3Com"),
        Map.entry(171,   "D-Link"),
        Map.entry(311,   "Microsoft"),
        Map.entry(318,   "APC"),
        Map.entry(674,   "Dell"),
        Map.entry(1916,  "Extreme Networks"),
        Map.entry(1991,  "Brocade"),
        Map.entry(2011,  "Huawei"),
        Map.entry(2021,  "UCD-SNMP"),
        Map.entry(2620,  "Check Point"),
        Map.entry(2636,  "Juniper"),
        Map.entry(3375,  "F5"),
        Map.entry(4526,  "Netgear"),
        Map.entry(6027,  "Force10"),
        Map.entry(6486,  "Alcatel-Lucent"),
        Map.entry(6876,  "VMware"),
        Map.entry(6889,  "Avaya"),
        Map.entry(8072,  "Net-SNMP"),
        Map.entry(11863, "TP-Link"),
        Map.entry(12356, "Fortinet"),
        Map.entry(14179, "Cisco (Airespace)"),
        Map.entry(14823, "Aruba"),
        Map.entry(14988, "MikroTik"),
        Map.entry(25461, "Palo Alto Networks"),
        Map.entry(25506, "H3C"),
        Map.entry(30065, "Arista"),
        Map.entry(41112, "Ubiquiti"),
        Map.entry(44913, "HPE"),
        Map.entry(6574,  "Synology"),
        Map.entry(24681, "QNAP")
    );

    private VendorRegistry() {}

    /**
     * 取出 sysObjectID 的企業編號；不在 enterprises 底下或格式不符時回傳 null
     */
    public static Integer enterpriseNumber(String sysObjectId) {
        if (sysObjectId == null) return null;
        String prefix = ENTERPRISES + ".";
        if (!sysObjectId.startsWith(prefix)) return null;
        String rest = sysObjectId.substring(prefix.length());
        int dot = rest.indexOf('.');
        try {
            return Integer.parseInt(dot >= 0 ? rest.substring(0, dot) : rest);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 廠牌名稱：已知編號回傳名稱；未知編號回傳編號本身；無法取得編號時回傳 null
     */
    public static String identify(String sysObjectId) {
        Integer n = enterpriseNumber(sysObjectId);
        if (n == null) return null;
        return VENDORS.getOrDefault(n, String.valueOf(n));
    }

    /** 廠牌子樹（1.3.6.1.4.1.<n>）；無法取得時回傳 null */
    public static String enterpriseSubtree(String sysObjectId) {
        Integer n = enterpriseNumber(sysObjectId);
        return n == null ? null : ENTERPRISES + "." + n;
    }
}
