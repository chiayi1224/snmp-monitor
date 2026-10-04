package io.github.chiayi1224.simplesnmp.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chiayi1224.simplesnmp.profile.MetricKind;
import io.github.chiayi1224.simplesnmp.profile.MetricProfile;
import io.github.chiayi1224.simplesnmp.profile.ProfileMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 監控範本（metric_profiles / profile_metrics）
 */
final class ProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(ProfileRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PARAMS_TYPE = new TypeReference<>() {};

    private final Db db;

    ProfileRepository(Db db) {
        this.db = db;
    }

    /** 所有範本（含指標），依內建優先、名稱排序 */
    List<MetricProfile> loadAll() {
        try {
            List<MetricProfile> heads = db.query(
                "SELECT * FROM metric_profiles ORDER BY builtin DESC, name", rs -> new MetricProfile(
                    rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                    rs.getString("match_prefix"), Db.bool(rs, "builtin"), List.of()));
            Map<Long, List<ProfileMetric>> metrics = new LinkedHashMap<>();
            db.query("SELECT * FROM profile_metrics ORDER BY id", rs -> {
                metrics.computeIfAbsent(rs.getLong("profile_id"), k -> new ArrayList<>()).add(new ProfileMetric(
                    rs.getString("metric_key"), MetricKind.parse(rs.getString("kind")),
                    parseParams(rs.getString("params_json")), rs.getString("unit"), Db.bool(rs, "enabled")));
                return null;
            });
            List<MetricProfile> out = new ArrayList<>();
            for (MetricProfile p : heads) {
                out.add(new MetricProfile(p.id(), p.name(), p.description(), p.matchPrefix(), p.builtin(),
                    metrics.getOrDefault(p.id(), List.of())));
            }
            return out;
        } catch (SQLException ex) {
            log.error("loadProfiles 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    MetricProfile load(long id) {
        return loadAll().stream().filter(p -> p.id() == id).findFirst().orElse(null);
    }

    boolean nameExists(String name, long excludeId) throws SQLException {
        return db.queryLong("SELECT COUNT(*) FROM metric_profiles WHERE name=? AND id != ?", name, excludeId) > 0;
    }

    /** 新增範本，回傳編號 */
    long create(MetricProfile p) throws SQLException {
        String now = Db.now();
        db.update("INSERT INTO metric_profiles(name, description, match_prefix, builtin, created, updated) VALUES(?,?,?,?,?,?)",
            p.name(), p.description(), p.matchPrefix(), Db.flag(p.builtin()), now, now);
        long id = db.queryLong("SELECT id FROM metric_profiles WHERE name=?", p.name());
        insertMetrics(id, p.metrics());
        return id;
    }

    /** 以新內容整份取代範本（含指標） */
    void update(long id, MetricProfile p) throws SQLException {
        db.update("UPDATE metric_profiles SET name=?, description=?, match_prefix=?, builtin=?, updated=? WHERE id=?",
            p.name(), p.description(), p.matchPrefix(), Db.flag(p.builtin()), Db.now(), id);
        db.update("DELETE FROM profile_metrics WHERE profile_id=?", id);
        insertMetrics(id, p.metrics());
    }

    void delete(long id) throws SQLException {
        db.update("DELETE FROM profile_metrics WHERE profile_id=?", id);
        db.update("DELETE FROM metric_profiles WHERE id=?", id);
    }

    /** 內建範本依名稱同步：不存在就新增，存在就以程式定義覆蓋（使用者的複本名稱不同，不受影響） */
    void syncBuiltin(List<MetricProfile> builtins) {
        for (MetricProfile b : builtins) {
            try {
                Long id = db.queryOne("SELECT id FROM metric_profiles WHERE name=?", rs -> rs.getLong(1), b.name());
                if (id == null) {
                    create(b);
                    log.info("已建立內建監控範本：{}", b.name());
                } else {
                    update(id, b);
                }
            } catch (SQLException ex) {
                log.error("同步內建範本 {} 失敗: {}", b.name(), ex.getMessage());
            }
        }
    }

    private void insertMetrics(long profileId, List<ProfileMetric> metrics) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (ProfileMetric m : metrics) {
            rows.add(new Object[]{ profileId, m.metricKey(), m.kind().name(), toJson(m.params()), m.unit(), Db.flag(m.enabled()) });
        }
        db.batch("INSERT INTO profile_metrics(profile_id, metric_key, kind, params_json, unit, enabled) VALUES(?,?,?,?,?,?)", rows);
    }

    private static String toJson(Map<String, String> params) {
        try {
            return JSON.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Map<String, String> parseParams(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, PARAMS_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("範本參數 JSON 格式錯誤，視為空白：{}", json);
            return Map.of();
        }
    }
}
