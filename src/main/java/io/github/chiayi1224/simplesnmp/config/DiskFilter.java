package io.github.chiayi1224.simplesnmp.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 設備的磁碟過濾條件：只記錄名稱符合的掛載點，空白代表全部記錄。
 *
 * 條件以逗號或換行分隔，每項為完整名稱，可用 * 代表任意字元（例如 "/"、"/data"、"/mnt/*"）。
 * 比對不分大小寫，方便 Windows 的磁碟代號（C:\ 與 c:\）。
 */
public final class DiskFilter {

    /** 儲存用的條件字串長度上限 */
    public static final int MAX_LENGTH = 500;

    private static final DiskFilter ALL = new DiskFilter("", List.of());

    private final String normalized;
    private final List<Pattern> patterns;

    private DiskFilter(String normalized, List<Pattern> patterns) {
        this.normalized = normalized;
        this.patterns = patterns;
    }

    /** 解析條件字串；null 或空白回傳「全部」 */
    public static DiskFilter parse(String raw) {
        if (raw == null || raw.isBlank()) return ALL;
        Set<String> items = new LinkedHashSet<>();
        for (String part : raw.split("[,\\r\\n]")) {
            String s = part.trim();
            if (!s.isEmpty()) items.add(s);
        }
        if (items.isEmpty()) return ALL;

        List<Pattern> patterns = new ArrayList<>();
        for (String item : items) patterns.add(toPattern(item));
        return new DiskFilter(String.join(",", items), patterns);
    }

    /** 整理成儲存用的字串（去除空白與重複項，以逗號分隔） */
    public static String normalize(String raw) {
        return parse(raw).normalized;
    }

    public boolean isAll() { return patterns.isEmpty(); }

    /** 整理後的條件字串；全部時為 "" */
    public String text() { return normalized; }

    public boolean matches(String mountPoint) {
        if (isAll()) return true;
        String name = mountPoint == null ? "" : mountPoint;
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) return true;
        }
        return false;
    }

    private static Pattern toPattern(String item) {
        String[] parts = item.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) regex.append(".*");
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }
}
