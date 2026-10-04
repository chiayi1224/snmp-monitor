package io.github.chiayi1224.simplesnmp.poller;

import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /**
     * 由 hrStorageEntry 的 walk 結果（任意順序）組出固定磁碟清單。
     * 只保留 hrStorageType = hrStorageFixedDisk 的列；容量 = units × allocationUnits。
     */
    static List<DiskInfo> disks(List<VariableBinding> rows) {
        OID typeCol  = new OID(OidConstants.HR_STORAGE_TYPE);
        OID descrCol = new OID(OidConstants.HR_STORAGE_DESCR);
        OID unitCol  = new OID(OidConstants.HR_STORAGE_UNIT);
        OID sizeCol  = new OID(OidConstants.HR_STORAGE_SIZE);
        OID usedCol  = new OID(OidConstants.HR_STORAGE_USED);
        String fixedDisk = OidConstants.HR_STORAGE_TYPE_FIXED_DISK;

        // index → 欄位 OID → 值
        Map<Integer, Map<OID, VariableBinding>> table = new TreeMap<>();
        for (VariableBinding vb : rows) {
            OID oid = vb.getOid();
            if (oid.size() != typeCol.size() + 1) continue;
            OID column = new OID(oid.getValue(), 0, oid.size() - 1);
            table.computeIfAbsent(oid.last(), k -> new HashMap<>()).put(column, vb);
        }

        List<DiskInfo> result = new ArrayList<>();
        for (Map<OID, VariableBinding> row : table.values()) {
            VariableBinding type = row.get(typeCol);
            if (type == null || !fixedDisk.equals(type.getVariable().toString())) continue;
            String mount = row.containsKey(descrCol) ? row.get(descrCol).getVariable().toString() : "unknown";
            long unit = longOf(row.get(unitCol), 1L);
            long size = longOf(row.get(sizeCol), 0L);
            long used = longOf(row.get(usedCol), 0L);
            result.add(new DiskInfo(mount, size * unit, used * unit));
        }
        return result;
    }

    private static long longOf(VariableBinding vb, long def) {
        if (vb == null) return def;
        try { return vb.getVariable().toLong(); }
        catch (UnsupportedOperationException e) { return def; }
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
