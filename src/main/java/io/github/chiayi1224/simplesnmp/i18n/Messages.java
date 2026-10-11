package io.github.chiayi1224.simplesnmp.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * 依語系產生使用者訊息（ResourceBundle：i18n/messages*.properties，UTF-8）
 *
 * 支援 zh-TW（預設，基底檔）、zh-CN、en。目前請求的語系由 {@link LocaleFilter} 依 Accept-Language
 * 設定在 ThreadLocal；背景執行緒（輪詢、Trap、掃描）沒有請求語系，產生的文字以 {@link StoredText} 保存代碼。
 */
public final class Messages {

    private static final Logger log = LoggerFactory.getLogger(Messages.class);

    public static final Locale ZH_TW = Locale.forLanguageTag("zh-TW");
    public static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");
    public static final Locale EN    = Locale.ENGLISH;

    /** 支援的語系（第一個為預設） */
    public static final List<Locale> SUPPORTED = List.of(ZH_TW, ZH_CN, EN);

    private static final String BUNDLE = "i18n.messages";

    /** 不回退到 JVM 預設語系：找不到語系專屬檔時直接使用基底檔（正體中文），結果不受伺服器 OS 語系影響 */
    private static final ResourceBundle.Control CONTROL =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static final ThreadLocal<Locale> CURRENT = new ThreadLocal<>();

    private Messages() {}

    // ─── 目前請求的語系 ───────────────────────────────────────────

    /** 目前執行緒（請求）的語系；未設定時為正體中文 */
    public static Locale current() {
        Locale l = CURRENT.get();
        return l == null ? ZH_TW : l;
    }

    static void setCurrent(Locale locale) {
        CURRENT.set(locale);
    }

    static void clearCurrent() {
        CURRENT.remove();
    }

    // ─── 語系判斷 ─────────────────────────────────────────────────

    /**
     * 由 Accept-Language 標頭選出支援的語系（依偏好順序逐一比對）：
     *   zh-TW / zh-HK / zh-MO / zh-Hant* → zh-TW
     *   zh-CN / zh-SG / zh-Hans* / 單純 zh → zh-CN
     *   en*                               → en
     * 都不符合或標頭不存在時為 zh-TW。
     */
    public static Locale resolve(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return ZH_TW;
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException e) {
            return ZH_TW;
        }
        for (Locale.LanguageRange range : ranges) {
            Locale match = match(range.getRange());
            if (match != null) return match;
        }
        return ZH_TW;
    }

    /** 單一語言標籤對應到支援的語系；不支援回傳 null */
    public static Locale match(String tag) {
        if (tag == null) return null;
        String t = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (t.equals("zh") || t.startsWith("zh-cn") || t.startsWith("zh-sg") || t.startsWith("zh-hans")) return ZH_CN;
        if (t.startsWith("zh-")) return ZH_TW;
        if (t.equals("en") || t.startsWith("en-")) return EN;
        return null;
    }

    // ─── 產生文字 ─────────────────────────────────────────────────

    /** 以目前請求的語系產生文字 */
    public static String get(Msg msg) {
        return get(msg, current());
    }

    /** 以指定語系產生文字；缺鍵時回退正體中文，仍缺則回傳代碼 */
    public static String get(Msg msg, Locale locale) {
        String pattern = pattern(msg.key(), locale);
        if (pattern == null && !ZH_TW.equals(locale)) pattern = pattern(msg.key(), ZH_TW);
        if (pattern == null) {
            log.warn("Missing message key: {}", msg.key());
            return msg.key();
        }
        Object[] args = msg.args();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Msg nested) args[i] = get(nested, locale);
        }
        // 一律經過 MessageFormat，語系檔中的單引號一律寫成 '' （與有無參數無關，規則一致）
        return new MessageFormat(pattern, locale).format(args);
    }

    /** 訊息代碼是否存在（正體中文基底檔） */
    public static boolean has(String key) {
        return pattern(key, ZH_TW) != null;
    }

    private static String pattern(String key, Locale locale) {
        try {
            return bundle(locale).getString(key);
        } catch (MissingResourceException e) {
            return null;
        }
    }

    static ResourceBundle bundle(Locale locale) {
        Locale target = ZH_TW.equals(locale) ? Locale.ROOT : locale;
        return ResourceBundle.getBundle(BUNDLE, target, Messages.class.getClassLoader(), CONTROL);
    }
}
