package io.github.chiayi1224.simplesnmp.poller;

import io.github.chiayi1224.simplesnmp.config.OidConstants;

import java.util.Map;

/**
 * 將 SNMP GET 結果（OID → 值字串）換算成 CPU / Memory 使用率。
 * 不依賴 SNMP4J，方便單元測試。
 */
final class PollParser {

    /** 記憶體換算結果 */
    record Memory(long totalKb, long usedKb, double usedPct) {}

    private PollParser() {}

    /**
     * CPU 使用率 = 100 - idle%
     * idle OID 未回傳或非數字（例如 noSuchObject）時回傳 null
     */
    static Double cpuUsedPct(Map<String, String> scalars) {
        Double idle = parseDouble(scalars.get(OidConstants.CPU_IDLE));
        return idle == null ? null : 100.0 - idle;
    }

    /**
     * 記憶體使用量 = total - free - buffer - cache
     * 任一 OID 未回傳或非數字時回傳 null
     */
    static Memory memory(Map<String, String> scalars) {
        Long totalKb  = parseLong(scalars.get(OidConstants.MEM_TOTAL));
        Long freeKb   = parseLong(scalars.get(OidConstants.MEM_FREE));
        Long bufferKb = parseLong(scalars.get(OidConstants.MEM_BUFFER));
        Long cacheKb  = parseLong(scalars.get(OidConstants.MEM_CACHE));
        if (totalKb == null || freeKb == null || bufferKb == null || cacheKb == null) return null;

        long   usedKb = totalKb - freeKb - bufferKb - cacheKb;
        double pct    = totalKb > 0 ? (double) usedKb / totalKb * 100.0 : 0.0;
        return new Memory(totalKb, usedKb, pct);
    }

    private static Double parseDouble(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
