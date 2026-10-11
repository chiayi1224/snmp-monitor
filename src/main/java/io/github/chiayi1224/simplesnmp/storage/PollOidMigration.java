package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.config.OidConstants;
import io.github.chiayi1224.simplesnmp.profile.BuiltinProfiles;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Poll OID 管理移除後的一次性升級轉換（design D8）
 *
 * 舊版 Poller 依 poll_oids 決定要收集什麼：
 *   cpu    — ssCpuIdle 啟用
 *   memory — UCD 記憶體四個 OID 全部啟用
 *   disk   — disk 群組任一筆啟用
 * 若舊設定讓其中任一項不收集，建立「Net-SNMP（UCD-SNMP）（升級轉換）」範本並停用對應指標，
 * 手動指定給升級前曾輪詢過、尚未取得 sysObjectID 的設備（即原本走 Net-SNMP 邏輯的設備），
 * 讓升級後的收集行為與升級前相同。以 app_settings 的 poll_oids_migrated 標記只執行一次；
 * poll_oids 資料表保留不刪，以利回滾。
 */
final class PollOidMigration {

    private static final Logger log = LoggerFactory.getLogger(PollOidMigration.class);

    static final String FLAG         = "poll_oids_migrated";
    static final String PROFILE_NAME = BuiltinProfiles.NET_SNMP + "（升級轉換）";

    private static final List<String> MEMORY_OIDS = List.of(
        OidConstants.MEM_TOTAL, OidConstants.MEM_FREE, OidConstants.MEM_BUFFER, OidConstants.MEM_CACHE);

    private final Db                 db;
    private final ProfileRepository  profiles;
    private final SettingsRepository settings;

    PollOidMigration(Db db, ProfileRepository profiles, SettingsRepository settings) {
        this.db       = db;
        this.profiles = profiles;
        this.settings = settings;
    }

    void runOnce() {
        if (settings.getBool(FLAG, false)) return;
        try {
            migrate();
            settings.upsert(FLAG, "true", SettingsRepository.seedDescription(FLAG));
        } catch (SQLException ex) {
            log.error("Poll OID migration failed, will retry on next start: {}", ex.getMessage());
        }
    }

    private void migrate() throws SQLException {
        // oid → enabled；group_name='disk' 另外判斷
        Map<String, Boolean> enabled = new HashMap<>();
        db.query("SELECT oid, enabled FROM poll_oids", rs -> enabled.put(rs.getString("oid"), Db.bool(rs, "enabled")));
        if (enabled.isEmpty()) return;   // 全新安裝，沒有舊設定

        boolean cpu    = enabled.getOrDefault(OidConstants.CPU_IDLE, false);
        boolean memory = MEMORY_OIDS.stream().allMatch(oid -> enabled.getOrDefault(oid, false));
        boolean disk   = db.queryLong("SELECT COUNT(*) FROM poll_oids WHERE group_name='disk' AND enabled=1") > 0;
        if (cpu && memory && disk) return;   // 與內建 Net-SNMP 範本相同，不需轉換

        List<String> disabled = new ArrayList<>();
        List<ProfileMetric> metrics = new ArrayList<>();
        for (ProfileMetric m : BuiltinProfiles.netSnmp().metrics()) {
            boolean on = switch (m.metricKey()) {
                case ProfileMetric.CPU    -> cpu;
                case ProfileMetric.MEMORY -> memory;
                default                   -> disk;
            };
            if (!on) disabled.add(m.metricKey());
            metrics.add(new ProfileMetric(m.metricKey(), m.kind(), m.params(), m.unit(), on));
        }

        // 名稱已存在（例如先前轉換到一半）就沿用，不重複建立
        Long id = db.queryOne("SELECT id FROM metric_profiles WHERE name=?", rs -> rs.getLong(1), PROFILE_NAME);
        if (id == null) {
            id = profiles.create(new MetricProfile(null, PROFILE_NAME,
                "由舊版 Poll OID 設定轉換：停用 " + String.join("、", disabled), "", false, metrics));
        }
        int assigned = db.update(
            "UPDATE devices SET profile_id=? WHERE profile_id IS NULL AND sys_object_id='' AND status != 'UNKNOWN'", id);
        log.info("Migrated legacy Poll OID settings to profile \"{}\" (disabled {}), assigned to {} devices",
            PROFILE_NAME, String.join("、", disabled), assigned);
    }
}
