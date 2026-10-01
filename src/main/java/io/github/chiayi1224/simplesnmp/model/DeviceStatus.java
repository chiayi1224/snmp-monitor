package io.github.chiayi1224.simplesnmp.model;

/**
 * 設備連線狀態（存於 devices.status 欄位）
 */
public enum DeviceStatus {
    /** 尚未 poll 過 */
    UNKNOWN,
    /** Ping 與 SNMP 皆正常 */
    ONLINE,
    /** Ping 不通 */
    OFFLINE,
    /** Ping 正常但 SNMP 無回應 */
    SNMP_ERROR
}
