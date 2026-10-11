package io.github.chiayi1224.simplesnmp.threshold;

import java.util.Locale;

/**
 * 告警等級；宣告順序即嚴重程度（WARNING &lt; CRITICAL）
 */
public enum Severity {
    WARNING,
    CRITICAL;

    /** 大小寫不敏感解析；null、空白或不合法回傳 null */
    public static Severity parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 兩者中較嚴重的一個（null 視為無告警） */
    public static Severity max(Severity a, Severity b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** 兩者中較輕的一個（null 視為無告警） */
    public static Severity min(Severity a, Severity b) {
        if (a == null || b == null) return null;
        return a.compareTo(b) <= 0 ? a : b;
    }
}
