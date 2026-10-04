package io.github.chiayi1224.simplesnmp.profile;

import io.github.chiayi1224.simplesnmp.config.OidConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 指標計算類型。每種類型定義：需要的參數（validate）、要 GET 的 OID（scalarOids）、
 * 要走訪的子樹（walkRoots），以及如何由 SNMP 結果算出數值（compute）。
 *
 * 參數一律以字串 Map 傳入（對應 profile_metrics.params_json）。
 * 刻意只提供固定類型、不支援任意運算式（見 design D2）。
 */
public enum MetricKind {

    /** 單一 OID 的值，可乘以倍率。參數：oid、scale（選填，預設 1） */
    VALUE {
        @Override String validate(Map<String, String> p) {
            return firstError(requireOid(p, "oid"), optionalScale(p));
        }
        @Override List<String> scalarOids(Map<String, String> p) { return List.of(p.get("oid")); }
        @Override List<MetricValue> compute(Map<String, String> p, SnmpResult r) throws MetricException {
            return List.of(MetricValue.of(number(r, p.get("oid")) * scale(p)));
        }
    },

    /** 100 減去單一 OID 的值（例如 CPU idle → 使用率）。參數：oid */
    HUNDRED_MINUS {
        @Override String validate(Map<String, String> p) { return requireOid(p, "oid"); }
        @Override List<String> scalarOids(Map<String, String> p) { return List.of(p.get("oid")); }
        @Override List<MetricValue> compute(Map<String, String> p, SnmpResult r) throws MetricException {
            return List.of(MetricValue.of(100.0 - number(r, p.get("oid"))));
        }
    },

    /** 已用 ÷ 總量 × 100。參數：used、total、scale（換算成位元組的倍率，選填，預設 1） */
    USED_TOTAL_PERCENT {
        @Override String validate(Map<String, String> p) {
            return firstError(requireOid(p, "used"), requireOid(p, "total"), optionalScale(p));
        }
        @Override List<String> scalarOids(Map<String, String> p) { return List.of(p.get("used"), p.get("total")); }
        @Override List<MetricValue> compute(Map<String, String> p, SnmpResult r) throws MetricException {
            double used  = number(r, p.get("used"));
            double total = number(r, p.get("total"));
            return List.of(percent(used, total, scale(p)));
        }
    },

    /** 已用 ÷（已用＋剩餘）× 100。參數：used、free、scale（換算成位元組的倍率，選填，預設 1） */
    USED_FREE_PERCENT {
        @Override String validate(Map<String, String> p) {
            return firstError(requireOid(p, "used"), requireOid(p, "free"), optionalScale(p));
        }
        @Override List<String> scalarOids(Map<String, String> p) { return List.of(p.get("used"), p.get("free")); }
        @Override List<MetricValue> compute(Map<String, String> p, SnmpResult r) throws MetricException {
            double used = number(r, p.get("used"));
            double free = number(r, p.get("free"));
            return List.of(percent(used, used + free, scale(p)));
        }
    },

    /** 表格欄位所有列的平均（例如多顆 CPU 的負載）。參數：column */
    TABLE_AVERAGE {
        @Override String validate(Map<String, String> p) { return requireOid(p, "column"); }
        @Override List<String> scalarOids(Map<String, String> p) { return List.of(); }
        @Override List<String> walkRoots(Map<String, String> p) { return List.of(p.get("column")); }
        @Override List<MetricValue> compute(Map<String, String> p, SnmpResult r) throws MetricException {
            List<VariableBinding> rows = r.walk(p.get("column"));
            if (rows.isEmpty()) throw new MetricException("表格沒有資料：" + p.get("column"));
            double sum = 0;
            for (VariableBinding vb : rows) sum += toDouble(vb.getVariable(), vb.getOid().toString());
            return List.of(MetricValue.of(sum / rows.size()));
        }
    },

    /**
     * hrStorage 格式的表格：依儲存類型篩選，逐列計算使用率（容量 = units × allocationUnits）。
     * 參數：typeFilter（儲存類型 OID，例如固定磁碟 1.3.6.1.2.1.25.2.1.4）、
     *      entry（表格 entry OID，選填，預設 hrStorageEntry）、
     *      first（"true" 時只取第一列且不帶實例名稱，例如記憶體）
     */
    STORAGE_TABLE {
        @Override String validate(Map<String, String> p) {
            return firstError(requireOid(p, "typeFilter"), optionalOid(p, "entry"));
        }
        @Override List<String> scalarOids(Map<String, String> p) { return List.of(); }
        @Override List<String> walkRoots(Map<String, String> p) { return List.of(entry(p)); }
        @Override List<MetricValue> compute(Map<String, String> p, SnmpResult r) throws MetricException {
            List<VariableBinding> rows = r.walk(entry(p));
            if (rows.isEmpty()) throw new MetricException("表格沒有資料：" + entry(p));
            boolean first = Boolean.parseBoolean(p.get("first"));

            List<MetricValue> out = new ArrayList<>();
            for (Map<Integer, Variable> row : byIndex(rows, entry(p)).values()) {
                Variable type = row.get(2);
                if (type == null || !p.get("typeFilter").equals(type.toString())) continue;
                long unit = longOr(row.get(4), 1L);
                long size = longOr(row.get(5), 0L);
                long used = longOr(row.get(6), 0L);
                long totalBytes = size * unit, usedBytes = used * unit;
                double pct = totalBytes == 0 ? 0.0 : (double) usedBytes / totalBytes * 100.0;
                String name = row.containsKey(3) ? row.get(3).toString() : "unknown";
                out.add(new MetricValue(first ? null : name, pct, totalBytes, usedBytes));
                if (first) break;
            }
            if (first && out.isEmpty()) throw new MetricException("表格中沒有類型為 " + p.get("typeFilter") + " 的資料");
            return out;
        }
    },

    /** Net-SNMP 記憶體：(total − free − buffer − cache) ÷ total（UCD-SNMP-MIB，單位 KB）。無參數 */
    UCD_MEMORY {
        @Override String validate(Map<String, String> p) { return null; }
        @Override List<String> scalarOids(Map<String, String> p) {
            return List.of(OidConstants.MEM_TOTAL, OidConstants.MEM_FREE, OidConstants.MEM_BUFFER, OidConstants.MEM_CACHE);
        }
        @Override List<MetricValue> compute(Map<String, String> p, SnmpResult r) throws MetricException {
            long totalKb = (long) number(r, OidConstants.MEM_TOTAL);
            long usedKb  = totalKb - (long) number(r, OidConstants.MEM_FREE)
                                   - (long) number(r, OidConstants.MEM_BUFFER)
                                   - (long) number(r, OidConstants.MEM_CACHE);
            double pct = totalKb > 0 ? (double) usedKb / totalKb * 100.0 : 0.0;
            return List.of(new MetricValue(null, pct, totalKb * 1024, usedKb * 1024));
        }
    };

    /** 參數合法回傳 null，否則回傳錯誤訊息 */
    abstract String validate(Map<String, String> params);

    /** 需要以 GET 取得的 OID */
    abstract List<String> scalarOids(Map<String, String> params);

    /** 需要走訪的子樹 */
    List<String> walkRoots(Map<String, String> params) { return List.of(); }

    /** 由 SNMP 結果計算數值 */
    abstract List<MetricValue> compute(Map<String, String> params, SnmpResult result) throws MetricException;

    // ─── 公開入口（含 null 參數保護）─────────────────────────────

    public String validateParams(Map<String, String> params) {
        return validate(params == null ? Map.of() : params);
    }

    public List<String> requiredScalarOids(Map<String, String> params) { return scalarOids(params); }

    public List<String> requiredWalkRoots(Map<String, String> params) { return walkRoots(params); }

    public List<MetricValue> calculate(Map<String, String> params, SnmpResult result) throws MetricException {
        return compute(params, result);
    }

    /** 大小寫不敏感解析；不合法回傳 null */
    public static MetricKind parse(String s) {
        if (s == null) return null;
        try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    // ─── 共用工具 ─────────────────────────────────────────────────

    static final String HR_STORAGE_ENTRY = OidConstants.HR_STORAGE_ENTRY;
    private static final Pattern OID_PATTERN = Pattern.compile("^\\d+(\\.\\d+)+$");

    private static String entry(Map<String, String> p) {
        String e = p.get("entry");
        return e == null || e.isBlank() ? HR_STORAGE_ENTRY : e;
    }

    private static String requireOid(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) return "缺少參數 " + key;
        return OID_PATTERN.matcher(v).matches() ? null : "參數 " + key + " 不是合法的 OID：" + v;
    }

    private static String optionalOid(Map<String, String> p, String key) {
        String v = p.get(key);
        return v == null || v.isBlank() ? null : requireOid(p, key);
    }

    private static String optionalScale(Map<String, String> p) {
        String v = p.get("scale");
        if (v == null || v.isBlank()) return null;
        try {
            double d = Double.parseDouble(v);
            return d > 0 && Double.isFinite(d) ? null : "參數 scale 必須大於 0";
        } catch (NumberFormatException e) {
            return "參數 scale 必須是數字";
        }
    }

    private static double scale(Map<String, String> p) {
        String v = p.get("scale");
        return v == null || v.isBlank() ? 1.0 : Double.parseDouble(v);
    }

    private static String firstError(String... errors) {
        for (String e : errors) if (e != null) return e;
        return null;
    }

    private static MetricValue percent(double used, double total, double scale) throws MetricException {
        if (total == 0) throw new MetricException("總量為 0，無法計算百分比");
        return new MetricValue(null, used / total * 100.0, Math.round(total * scale), Math.round(used * scale));
    }

    private static double number(SnmpResult r, String oid) throws MetricException {
        Variable v = r.scalar(oid);
        if (v == null || v.isException()) throw new MetricException("OID 不存在：" + oid);
        return toDouble(v, oid);
    }

    /** 數值型別直接取值；字串型別（部分 MIB 以文字表示數字）則嘗試解析 */
    private static double toDouble(Variable v, String oid) throws MetricException {
        try {
            return v.toLong();
        } catch (UnsupportedOperationException e) {
            try {
                return Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException nfe) {
                throw new MetricException("OID " + oid + " 的值不是數字：" + v);
            }
        }
    }

    private static long longOr(Variable v, long def) {
        if (v == null) return def;
        try { return v.toLong(); } catch (UnsupportedOperationException e) { return def; }
    }

    /** 表格 entry 的走訪結果：index → 欄位編號 → 值 */
    private static Map<Integer, Map<Integer, Variable>> byIndex(List<VariableBinding> rows, String entry) {
        OID e = new OID(entry);
        Map<Integer, Map<Integer, Variable>> out = new TreeMap<>();
        for (VariableBinding vb : rows) {
            OID o = vb.getOid();
            if (o.size() != e.size() + 2 || !o.startsWith(e)) continue;
            out.computeIfAbsent(o.last(), k -> new HashMap<>()).put(o.get(e.size()), vb.getVariable());
        }
        return out;
    }
}
