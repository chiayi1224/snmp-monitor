package io.github.chiayi1224.simplesnmp.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.snmp.SnmpSession;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 設備 OID 掃描服務
 *
 * - 掃描在單一背景執行緒依序執行，不阻塞 Web 請求，也不與輪詢共用 SNMP socket
 * - 同一台設備同時間只能有一個掃描（排隊中或執行中）
 * - 步驟：系統資訊 → 廠牌辨識 → 常見 MIB 探測 →（選擇性）走訪子樹 → 只保留最近 3 次
 * - 只發出 GET / GETNEXT / GETBULK
 */
public class DiscoveryService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SYS_DESCR     = "1.3.6.1.2.1.1.1.0";
    static final String SYS_OBJECT_ID = OidConstants.SYS_OBJECT_ID;
    static final String SYS_UPTIME    = "1.3.6.1.2.1.1.3.0";
    static final String SYS_NAME      = "1.3.6.1.2.1.1.5.0";
    static final String MIB2          = "1.3.6.1.2.1";

    /** 每台設備保留的掃描次數 */
    static final int KEEP_SCANS = 3;
    /** 每批寫入 DB 的 OID 筆數 */
    private static final int WRITE_BATCH = 500;

    /** 掃描上限：單次最多收集的 OID 筆數與最長執行時間 */
    public record Limits(int maxOids, long maxMillis) {
        public static final Limits DEFAULT = new Limits(10_000, 120_000);
    }

    /** 啟動結果：alreadyRunning 為 true 時 scanId 是進行中的那一次 */
    public record StartResult(long scanId, boolean alreadyRunning) {}

    /** 設備不存在 */
    public static class DeviceNotFoundException extends Exception {
        public DeviceNotFoundException(String name) { super("找不到設備：" + name); }
    }

    private final MetricStore     store;
    private final SnmpSession     session;
    private final Limits          limits;
    private final ExecutorService executor;

    /** 設備名稱 → 進行中的掃描編號 */
    private final Map<String, Long> running = new ConcurrentHashMap<>();

    public DiscoveryService(MetricStore store, SnmpSession session, Limits limits) {
        this.store    = store;
        this.session  = session;
        this.limits   = limits;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "oid-discovery");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 啟動掃描
     *
     * @param walk     是否走訪子樹
     * @param subtrees 要走訪的子樹；空白代表預設（MIB-2 與廠牌子樹）
     */
    public synchronized StartResult start(String deviceName, boolean walk, List<String> subtrees)
            throws DeviceNotFoundException, java.sql.SQLException {
        DeviceConfig device = store.loadDevice(deviceName);
        if (device == null) throw new DeviceNotFoundException(deviceName);

        Long existing = running.get(deviceName);
        if (existing != null) return new StartResult(existing, true);

        long scanId = store.createScan(deviceName, walk);
        running.put(deviceName, scanId);
        executor.submit(() -> {
            try {
                scan(scanId, device, walk, subtrees == null ? List.of() : subtrees);
            } finally {
                running.remove(deviceName);
            }
        });
        log.info("[{}] 開始 OID 掃描 #{}（walk={}）", deviceName, scanId, walk);
        return new StartResult(scanId, false);
    }

    /** 指定設備是否有進行中的掃描 */
    public boolean isRunning(String deviceName) {
        return running.containsKey(deviceName);
    }

    // ─── 掃描流程 ─────────────────────────────────────────────────

    private void scan(long scanId, DeviceConfig device, boolean walk, List<String> subtrees) {
        long deadline = System.currentTimeMillis() + limits.maxMillis();
        try {
            // ① 系統資訊（同時確認設備可連線）
            store.updateScanProgress(scanId, "讀取系統資訊");
            Map<String, Variable> sys;
            try {
                sys = session.get(device, List.of(SYS_DESCR, SYS_OBJECT_ID, SYS_NAME, SYS_UPTIME));
            } catch (IOException e) {
                finish(scanId, device, "FAILED", e.getMessage(), false);
                return;
            }
            String objectId = str(sys.get(SYS_OBJECT_ID));
            store.saveScanSystemInfo(scanId, str(sys.get(SYS_DESCR)), objectId, str(sys.get(SYS_NAME)),
                str(sys.get(SYS_UPTIME)), VendorRegistry.identify(objectId));

            // ② 常見 MIB 探測
            store.updateScanProgress(scanId, "探測常見 MIB");
            store.saveScanProbes(scanId, JSON.writeValueAsString(MibProbes.runAll(session, device)));

            // ③ 走訪子樹
            boolean truncated = false;
            String  error     = null;
            if (walk) {
                int collected = 0;
                for (String root : resolveSubtrees(subtrees, objectId)) {
                    if (collected >= limits.maxOids() || System.currentTimeMillis() > deadline) {
                        truncated = true;
                        break;
                    }
                    store.updateScanProgress(scanId, "走訪 " + root + "（已收集 " + collected + " 筆）");
                    SnmpSession.WalkResult r;
                    try {
                        r = session.walk(device, root, limits.maxOids() - collected, deadline);
                    } catch (IOException e) {
                        error = "走訪 " + root + " 失敗：" + e.getMessage();
                        break;
                    }
                    writeResults(scanId, r.rows());
                    collected += r.rows().size();
                    if (r.truncated()) truncated = true;
                    if (r.error() != null) { error = "走訪 " + root + " 中斷：" + r.error(); break; }
                }
            }
            finish(scanId, device, error == null ? "COMPLETED" : "FAILED", error, truncated);
        } catch (Exception e) {
            log.error("[{}] 掃描 #{} 發生錯誤", device.getName(), scanId, e);
            finish(scanId, device, "FAILED", "內部錯誤：" + e.getMessage(), false);
        }
    }

    /** 未指定子樹時預設為 MIB-2 與廠牌子樹；去除重複並保留順序 */
    static List<String> resolveSubtrees(List<String> requested, String sysObjectId) {
        Set<String> roots = new LinkedHashSet<>();
        for (String s : requested) {
            if (s != null && !s.isBlank()) roots.add(s.trim());
        }
        if (roots.isEmpty()) {
            roots.add(MIB2);
            String vendor = VendorRegistry.enterpriseSubtree(sysObjectId);
            if (vendor != null) roots.add(vendor);
        }
        return new ArrayList<>(roots);
    }

    private void writeResults(long scanId, List<VariableBinding> rows) throws java.sql.SQLException {
        List<String[]> batch = new ArrayList<>(WRITE_BATCH);
        for (VariableBinding vb : rows) {
            batch.add(new String[]{ vb.getOid().toString(), vb.getVariable().getSyntaxString(), display(vb.getVariable()) });
            if (batch.size() >= WRITE_BATCH) {
                store.addScanResults(scanId, batch);
                batch.clear();
            }
        }
        store.addScanResults(scanId, batch);
    }

    private void finish(long scanId, DeviceConfig device, String status, String error, boolean truncated) {
        try {
            store.finishScan(scanId, status, error, truncated);
            store.pruneScans(device.getName(), KEEP_SCANS);
            log.info("[{}] OID 掃描 #{} 結束：{}{}{}", device.getName(), scanId, status,
                truncated ? "（已截斷）" : "", error != null ? "，" + error : "");
        } catch (java.sql.SQLException e) {
            log.error("[{}] 寫入掃描結果失敗: {}", device.getName(), e.getMessage());
        }
    }

    /** 顯示用字串：OCTET STRING 含不可列印字元時以 hex 表示 */
    static String display(Variable v) {
        if (v instanceof OctetString os && !os.isPrintable()) return os.toHexString();
        return v.toString();
    }

    private static String str(Variable v) {
        return v == null ? null : display(v);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
