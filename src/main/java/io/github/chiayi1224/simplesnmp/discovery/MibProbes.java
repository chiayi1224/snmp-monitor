package io.github.chiayi1224.simplesnmp.discovery;

import io.github.chiayi1224.simplesnmp.i18n.Localized;
import io.github.chiayi1224.simplesnmp.i18n.StoredText;
import io.github.chiayi1224.simplesnmp.i18n.Msg;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 常見 MIB 的探測定義：判斷設備是否支援，並取幾個代表性數值讓使用者確認。
 * 新增探測項目只需在 {@link #ALL} 加一筆。
 */
public final class MibProbes {

    /** 單一探測結果 */
    public record ProbeResult(String key, String name, String mib, boolean supported,
                              List<String> samples, String error) {}

    /** 探測項目：GET 一組 OID，或 walk 一個子樹，再把結果整理成文字樣本 */
    public record Probe(String key, String mib, Kind kind, List<String> oids, int maxRows,
                        Function<Map<String, Variable>, List<String>> summarize) {

        /** 顯示名稱（保存訊息代碼，讀取時依語系翻譯） */
        public String name() {
            return StoredText.encode(Msg.of("probe." + key + ".name"));
        }

        public enum Kind { GET, WALK }

        ProbeResult run(SnmpSession session, DeviceConfig device) {
            try {
                Map<String, Variable> values = new java.util.LinkedHashMap<>();
                if (kind == Kind.GET) {
                    values.putAll(session.get(device, oids));
                } else {
                    for (VariableBinding vb : session.walk(device, oids.get(0), maxRows, 0).rows()) {
                        values.put(vb.getOid().toString(), vb.getVariable());
                    }
                }
                List<String> samples = values.isEmpty() ? List.of() : summarize.apply(values);
                return new ProbeResult(key, name(), mib, !samples.isEmpty(), samples, null);
            } catch (IOException e) {
                return new ProbeResult(key, name(), mib, false, List.of(),
                    e instanceof Localized l ? StoredText.encode(l.msg()) : e.getMessage());
            }
        }
    }

    /** 每個探測項目最多顯示的樣本數 */
    static final int MAX_SAMPLES = 10;

    // ─── OID ─────────────────────────────────────────────────────

    static final String HR_PROCESSOR_LOAD   = "1.3.6.1.2.1.25.3.3.1.2";
    static final String HR_STORAGE_ENTRY    = "1.3.6.1.2.1.25.2.3.1";
    static final String HR_STORAGE_TYPES    = "1.3.6.1.2.1.25.2.1";
    static final String UCD_CPU_USER        = "1.3.6.1.4.1.2021.11.9.0";
    static final String UCD_CPU_SYSTEM      = "1.3.6.1.4.1.2021.11.10.0";
    static final String UCD_CPU_IDLE        = "1.3.6.1.4.1.2021.11.11.0";
    static final String UCD_MEM_TOTAL_REAL  = "1.3.6.1.4.1.2021.4.5.0";
    static final String UCD_MEM_AVAIL_REAL  = "1.3.6.1.4.1.2021.4.6.0";
    static final String IF_DESCR            = "1.3.6.1.2.1.2.2.1.2";
    static final String CISCO_CPU_5MIN      = "1.3.6.1.4.1.9.9.109.1.1.1.1.8";
    static final String CISCO_MEM_POOL      = "1.3.6.1.4.1.9.9.48.1.1.1";

    /** hrStorageTypes 的已知編號（1～10），名稱由訊息代碼 probe.storage_type.&lt;編號&gt; 提供 */
    private static final int STORAGE_TYPE_MAX = 10;

    public static final List<Probe> ALL = List.of(
        new Probe("hr-processor", "HOST-RESOURCES-MIB", Probe.Kind.WALK,
            List.of(HR_PROCESSOR_LOAD), 128, v -> limit(byIndex(v, HR_PROCESSOR_LOAD).entrySet().stream()
                .map(e -> sample("probe.sample.processor", e.getKey(), e.getValue())).toList())),

        new Probe("hr-storage", "HOST-RESOURCES-MIB", Probe.Kind.WALK,
            List.of(HR_STORAGE_ENTRY), 7 * 300, MibProbes::summarizeStorage),

        new Probe("ucd-cpu", "UCD-SNMP-MIB", Probe.Kind.GET,
            List.of(UCD_CPU_USER, UCD_CPU_SYSTEM, UCD_CPU_IDLE), 0, v -> v.containsKey(UCD_CPU_IDLE)
                ? List.of("user " + v.getOrDefault(UCD_CPU_USER, missing()) + "% / system "
                    + v.getOrDefault(UCD_CPU_SYSTEM, missing()) + "% / idle " + v.get(UCD_CPU_IDLE) + "%")
                : List.of()),

        new Probe("ucd-memory", "UCD-SNMP-MIB", Probe.Kind.GET,
            List.of(UCD_MEM_TOTAL_REAL, UCD_MEM_AVAIL_REAL), 0, v -> v.containsKey(UCD_MEM_TOTAL_REAL)
                ? List.of(sample("probe.sample.ucd_memory", v.get(UCD_MEM_TOTAL_REAL),
                    v.getOrDefault(UCD_MEM_AVAIL_REAL, missing())))
                : List.of()),

        new Probe("if-mib", "IF-MIB", Probe.Kind.WALK,
            List.of(IF_DESCR), 1000, MibProbes::summarizeInterfaces),

        new Probe("cisco-cpu", "CISCO-PROCESS-MIB", Probe.Kind.WALK,
            List.of(CISCO_CPU_5MIN), 64, v -> limit(byIndex(v, CISCO_CPU_5MIN).entrySet().stream()
                .map(e -> sample("probe.sample.cisco_cpu", e.getKey(), e.getValue())).toList())),

        new Probe("cisco-memory", "CISCO-MEMORY-POOL-MIB", Probe.Kind.WALK,
            List.of(CISCO_MEM_POOL), 7 * 100, MibProbes::summarizeCiscoMemory)
    );

    private MibProbes() {}

    /** 依序執行所有探測 */
    public static List<ProbeResult> runAll(SnmpSession session, DeviceConfig device) {
        List<ProbeResult> results = new ArrayList<>();
        for (Probe p : ALL) results.add(p.run(session, device));
        return results;
    }

    // ─── 整理樣本 ─────────────────────────────────────────────────

    private static List<String> summarizeStorage(Map<String, Variable> v) {
        Map<Integer, Map<Integer, Variable>> table = byColumn(v, HR_STORAGE_ENTRY);
        List<String> out = new ArrayList<>();
        for (Map<Integer, Variable> row : table.values()) {
            Object descr = text(row.get(3));
            Object type  = row.containsKey(2) ? storageType(row.get(2).toString()) : Msg.of("probe.storage_type.unknown");
            long unit = num(row.get(4), 1), size = num(row.get(5), 0), used = num(row.get(6), 0);
            String pct = size > 0 ? String.format("%.1f%%", used * 100.0 / size) : "—";
            out.add(StoredText.encode(Msg.of("probe.sample.storage", descr, type, humanBytes(size * unit), pct)));
        }
        return limit(out);
    }

    private static List<String> summarizeInterfaces(Map<String, Variable> v) {
        List<String> names = byIndex(v, IF_DESCR).values().stream().map(Object::toString).toList();
        if (names.isEmpty()) return List.of();
        String head = String.join(", ", names.subList(0, Math.min(names.size(), MAX_SAMPLES)));
        return List.of(sample("probe.sample.interfaces", names.size(), head + (names.size() > MAX_SAMPLES ? "…" : "")));
    }

    private static List<String> summarizeCiscoMemory(Map<String, Variable> v) {
        Map<Integer, Map<Integer, Variable>> table = byColumn(v, CISCO_MEM_POOL);
        List<String> out = new ArrayList<>();
        for (Map<Integer, Variable> row : table.values()) {
            if (!row.containsKey(5) && !row.containsKey(6)) continue;
            out.add(StoredText.encode(Msg.of("probe.sample.cisco_memory", text(row.get(2)),
                humanBytes(num(row.get(5), 0)), humanBytes(num(row.get(6), 0)))));
        }
        return limit(out);
    }

    // ─── 工具 ─────────────────────────────────────────────────────

    /** 單欄位表格：index → 值 */
    static Map<Integer, Variable> byIndex(Map<String, Variable> values, String column) {
        Map<Integer, Variable> out = new TreeMap<>();
        OID col = new OID(column);
        values.forEach((oid, val) -> {
            OID o = new OID(oid);
            if (o.size() == col.size() + 1 && o.startsWith(col)) out.put(o.last(), val);
        });
        return out;
    }

    /** 表格 entry：index → 欄位編號 → 值 */
    static Map<Integer, Map<Integer, Variable>> byColumn(Map<String, Variable> values, String entry) {
        Map<Integer, Map<Integer, Variable>> out = new TreeMap<>();
        OID e = new OID(entry);
        values.forEach((oid, val) -> {
            OID o = new OID(oid);
            if (o.size() == e.size() + 2 && o.startsWith(e)) {
                out.computeIfAbsent(o.last(), k -> new HashMap<>()).put(o.get(e.size()), val);
            }
        });
        return out;
    }

    /** 已知的儲存類型回傳訊息代碼，其他回傳原始 OID */
    private static Object storageType(String typeOid) {
        if (!typeOid.startsWith(HR_STORAGE_TYPES + ".")) return typeOid;
        String n = typeOid.substring(HR_STORAGE_TYPES.length() + 1);
        try {
            int i = Integer.parseInt(n);
            return i >= 1 && i <= STORAGE_TYPE_MAX ? Msg.of("probe.storage_type." + i) : typeOid;
        } catch (NumberFormatException e) {
            return typeOid;
        }
    }

    /** 樣本文字：保存訊息代碼，參數一律轉成字串（避免數字依語系加上千分位） */
    private static String sample(String key, Object... args) {
        Object[] text = new Object[args.length];
        for (int i = 0; i < args.length; i++) text[i] = args[i] instanceof Msg ? args[i] : String.valueOf(args[i]);
        return StoredText.encode(Msg.of(key, text));
    }

    private static long num(Variable v, long def) {
        if (v == null) return def;
        try { return v.toLong(); } catch (UnsupportedOperationException e) { return def; }
    }

    private static Object text(Variable v) {
        return v == null ? Msg.of("probe.no_name") : v.toString();
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1L << 30) return String.format("%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format("%.1f MB", bytes / (double) (1L << 20));
        if (bytes >= 1L << 10) return String.format("%.1f KB", bytes / (double) (1L << 10));
        return bytes + " B";
    }

    private static Variable missing() {
        return new org.snmp4j.smi.OctetString("?");
    }

    private static List<String> limit(List<String> list) {
        return list.size() <= MAX_SAMPLES ? list : list.subList(0, MAX_SAMPLES);
    }
}
