package com.syscom.snmp.config;

/**
 * 常用 OID 常數（Linux Server 版）
 */
public final class OidConstants {

    private OidConstants() {}

    // ─── System ────────────────────────────────────────────────────
    public static final String SYS_UPTIME = "1.3.6.1.2.1.1.3.0";
    public static final String SYS_NAME   = "1.3.6.1.2.1.1.5.0";

    // ─── CPU (UCD-SNMP-MIB) ────────────────────────────────────────
    public static final String CPU_USER   = "1.3.6.1.4.1.2021.11.9.0";
    public static final String CPU_SYSTEM = "1.3.6.1.4.1.2021.11.10.0";
    public static final String CPU_IDLE   = "1.3.6.1.4.1.2021.11.11.0";

    // ─── Memory (UCD-SNMP-MIB, 單位 KB) ───────────────────────────
    public static final String MEM_TOTAL  = "1.3.6.1.4.1.2021.4.5.0";
    public static final String MEM_FREE   = "1.3.6.1.4.1.2021.4.6.0";
    public static final String MEM_BUFFER = "1.3.6.1.4.1.2021.4.14.0";
    public static final String MEM_CACHE  = "1.3.6.1.4.1.2021.4.15.0";

    // ─── Disk: hrStorageTable (需要 WALK) ─────────────────────────
    public static final String HR_STORAGE_TABLE   = "1.3.6.1.2.1.25.2.3";
    public static final String HR_STORAGE_TYPE    = "1.3.6.1.2.1.25.2.3.1.2";
    public static final String HR_STORAGE_DESCR   = "1.3.6.1.2.1.25.2.3.1.3"; // 掛載點
    public static final String HR_STORAGE_UNIT    = "1.3.6.1.2.1.25.2.3.1.4"; // bytes/unit
    public static final String HR_STORAGE_SIZE    = "1.3.6.1.2.1.25.2.3.1.5"; // 總 units
    public static final String HR_STORAGE_USED    = "1.3.6.1.2.1.25.2.3.1.6"; // 已用 units

    // type = 1.3.6.1.2.1.25.2.1.4 代表 hrStorageFixedDisk，用來過濾掉 RAM/swap
    public static final String HR_STORAGE_TYPE_FIXED_DISK = "1.3.6.1.2.1.25.2.1.4";

    // ─── 要 GET 的純量 OID 清單（CPU + Memory） ───────────────────
    public static String[] scalarOids() {
        return new String[]{
            SYS_UPTIME,
            SYS_NAME,
            CPU_USER,
            CPU_SYSTEM,
            CPU_IDLE,
            MEM_TOTAL,
            MEM_FREE,
            MEM_BUFFER,
            MEM_CACHE
        };
    }
}
