package io.github.chiayi1224.simplesnmp.profile;

import java.util.List;

/**
 * 決定設備套用哪一份範本：
 *   1. 設備手動指定的範本（存在時）
 *   2. sysObjectID 前綴相符且前綴最長的範本
 *   3. 「HOST-RESOURCES 通用」
 */
public final class ProfileResolver {

    private final List<MetricProfile> profiles;
    private final MetricProfile       fallback;

    public ProfileResolver(List<MetricProfile> profiles) {
        this.profiles = List.copyOf(profiles);
        this.fallback = profiles.stream()
            .filter(p -> p.builtin() && BuiltinProfiles.HOST_RESOURCES.equals(p.name()))
            .findFirst()
            .orElseGet(BuiltinProfiles::hostResources);
    }

    public MetricProfile resolve(Long manualProfileId, String sysObjectId) {
        if (manualProfileId != null) {
            for (MetricProfile p : profiles) {
                if (manualProfileId.equals(p.id())) return p;
            }
        }
        MetricProfile best = null;
        for (MetricProfile p : profiles) {
            if (p.matches(sysObjectId) && (best == null || p.matchPrefix().length() > best.matchPrefix().length())) {
                best = p;
            }
        }
        return best != null ? best : fallback;
    }
}
