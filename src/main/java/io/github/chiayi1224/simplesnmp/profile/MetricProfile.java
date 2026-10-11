package io.github.chiayi1224.simplesnmp.profile;

import io.github.chiayi1224.simplesnmp.i18n.Msg;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 監控範本：描述一類設備的指標要從哪些 OID、用什麼方式計算
 *
 * @param id          資料庫編號（尚未儲存時為 null）
 * @param name        名稱（唯一）
 * @param matchPrefix sysObjectID 前綴，用於自動套用；空字串代表不參與自動比對（作為預設的範本除外）
 * @param builtin     是否為內建範本（不可修改或刪除）
 */
public record MetricProfile(Long id, String name, String description, String matchPrefix,
                            boolean builtin, List<ProfileMetric> metrics) {

    private static final Pattern OID_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

    public MetricProfile {
        description = description == null ? "" : description;
        matchPrefix = matchPrefix == null ? "" : matchPrefix.trim();
        metrics     = metrics == null ? List.of() : List.copyOf(metrics);
    }

    public MetricProfile withId(Long newId) {
        return new MetricProfile(newId, name, description, matchPrefix, builtin, metrics);
    }

    /** 合法回傳 null，否則回傳錯誤訊息 */
    public Msg validate() {
        if (name == null || name.isBlank()) return Msg.of("profile.name_required");
        if (name.length() > 64) return Msg.of("profile.name_too_long", 64);
        if (!matchPrefix.isEmpty() && !OID_PATTERN.matcher(matchPrefix).matches()) {
            return Msg.of("profile.prefix_invalid", matchPrefix);
        }
        Set<String> keys = new HashSet<>();
        for (ProfileMetric m : metrics) {
            Msg error = m.validate();
            if (error != null) return error;
            if (!keys.add(m.metricKey())) return Msg.of("profile.metric_duplicate", m.metricKey());
        }
        return null;
    }

    /** sysObjectID 是否符合本範本的前綴（前綴本身或其子節點） */
    public boolean matches(String sysObjectId) {
        if (matchPrefix.isEmpty() || sysObjectId == null || sysObjectId.isEmpty()) return false;
        return sysObjectId.equals(matchPrefix) || sysObjectId.startsWith(matchPrefix + ".");
    }
}
