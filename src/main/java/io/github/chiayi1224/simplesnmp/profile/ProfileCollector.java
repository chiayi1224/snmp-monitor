package io.github.chiayi1224.simplesnmp.profile;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.snmp.SnmpException;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依範本向設備查詢並計算指標（Poller 與範本預覽共用，確保預覽結果與實際輪詢一致）
 *
 *  1. 範本中已啟用指標所需的 OID 合併成一次 GET（可附加額外 OID，例如 sysObjectID）
 *  2. 表格類型各自 walk，同一個子樹只 walk 一次；表格讀取失敗只影響用到它的指標
 *  3. 逐指標計算，單一指標失敗不影響其他指標
 */
public final class ProfileCollector {

    /** 每個表格最多讀取的筆數 */
    static final int WALK_MAX_ROWS = 5_000;

    /** 單一指標的結果：成功時 values 有值、error 為 null；失敗時相反 */
    public record MetricResult(ProfileMetric metric, List<MetricValue> values, String error) {
        public boolean ok() { return error == null; }
    }

    /** 設備的查詢結果與表格讀取錯誤（子樹 → 原因） */
    public record Fetched(SnmpResult result, Map<String, String> walkErrors) {}

    private final SnmpSession session;

    public ProfileCollector(SnmpSession session) {
        this.session = session;
    }

    /** 範本中已啟用指標需要 GET 的 OID（不重複，依指標順序） */
    public static List<String> scalarOids(MetricProfile profile) {
        Set<String> oids = new LinkedHashSet<>();
        for (ProfileMetric m : profile.metrics()) {
            if (m.enabled()) oids.addAll(m.kind().requiredScalarOids(m.params()));
        }
        return new ArrayList<>(oids);
    }

    /** 範本中已啟用指標需要走訪的子樹（不重複） */
    public static List<String> walkRoots(MetricProfile profile) {
        Set<String> roots = new LinkedHashSet<>();
        for (ProfileMetric m : profile.metrics()) {
            if (m.enabled()) roots.addAll(m.kind().requiredWalkRoots(m.params()));
        }
        return new ArrayList<>(roots);
    }

    /** GET 的結果與已請求過的 OID（不存在的 OID 不會出現在 values，但已請求過就不再重查） */
    public record Scalars(Map<String, Variable> values, Set<String> requested) {}

    /**
     * 一次 GET 範本所需的 OID（加上 extraOids，例如 sysObjectID）。
     *
     * @throws SnmpException GET 無回應、v3 認證失敗等（代表設備無法查詢）
     */
    public Scalars get(DeviceConfig device, MetricProfile profile, List<String> extraOids) throws IOException {
        Set<String> oids = new LinkedHashSet<>(extraOids);
        oids.addAll(scalarOids(profile));
        return new Scalars(new HashMap<>(session.get(device, new ArrayList<>(oids))), oids);
    }

    /** 補查範本需要但尚未請求過的 OID，再走訪範本所需的表格（範本確定後才呼叫，避免多走訪不需要的表格） */
    public Fetched complete(DeviceConfig device, MetricProfile profile, Scalars scalars) throws IOException {
        Map<String, Variable> values = new HashMap<>(scalars.values());
        List<String> more = new ArrayList<>();
        for (String oid : scalarOids(profile)) if (!scalars.requested().contains(oid)) more.add(oid);
        if (!more.isEmpty()) values.putAll(session.get(device, more));

        Map<String, String> errors = new HashMap<>();
        return new Fetched(new SnmpResult(values, walkTables(device, walkRoots(profile), errors)), errors);
    }

    /** GET 加上走訪（範本已確定時使用，例如預覽） */
    public Fetched fetch(DeviceConfig device, MetricProfile profile) throws IOException {
        return complete(device, profile, get(device, profile, List.of()));
    }

    private Map<String, List<VariableBinding>> walkTables(DeviceConfig device, List<String> roots,
                                                          Map<String, String> errors) throws IOException {
        Map<String, List<VariableBinding>> walks = new HashMap<>();
        for (String root : roots) {
            try {
                SnmpSession.WalkResult walk = session.walk(device, root, WALK_MAX_ROWS, 0);
                if (walk.error() != null) errors.put(root, "表格 " + root + " 讀取中斷：" + walk.error());
                walks.put(root, walk.rows());
            } catch (SnmpException e) {
                errors.put(root, "表格 " + root + " 讀取失敗：" + e.getMessage());
                walks.put(root, List.of());
            }
        }
        return walks;
    }

    /** 逐一計算範本中已啟用的指標 */
    public static List<MetricResult> calculate(MetricProfile profile, Fetched fetched) {
        List<MetricResult> out = new ArrayList<>();
        for (ProfileMetric m : profile.metrics()) {
            if (!m.enabled()) continue;
            try {
                List<MetricValue> values = m.kind().calculate(m.params(), fetched.result());
                for (MetricValue v : values) {
                    if (!Double.isFinite(v.value())) throw new MetricException("計算結果不是有限數值：" + v.value());
                }
                out.add(new MetricResult(m, values, null));
            } catch (MetricException e) {
                out.add(new MetricResult(m, null, walkErrorFor(m, fetched.walkErrors(), e.getMessage())));
            }
        }
        return out;
    }

    /** 表格讀取失敗時，以該錯誤作為指標失敗的原因（比「表格沒有資料」更明確） */
    private static String walkErrorFor(ProfileMetric m, Map<String, String> walkErrors, String fallback) {
        for (String root : m.kind().requiredWalkRoots(m.params())) {
            if (walkErrors.containsKey(root)) return walkErrors.get(root);
        }
        return fallback;
    }
}
