package io.github.chiayi1224.simplesnmp.i18n;

import java.util.Locale;

/** 測試用：在指定語系下執行（模擬 LocaleFilter 設定的請求語系） */
public final class TestLocale {

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    private TestLocale() {}

    public static void run(Locale locale, Body body) throws Exception {
        Messages.setCurrent(locale);
        try {
            body.run();
        } finally {
            Messages.clearCurrent();
        }
    }

    public static void english(Body body) throws Exception {
        run(Messages.EN, body);
    }
}
