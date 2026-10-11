package io.github.chiayi1224.simplesnmp.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    @AfterEach
    void tearDown() {
        Messages.clearCurrent();
    }

    // ─── 語系判斷 ─────────────────────────────────────────────────

    @Test
    void resolve_繁體中文相關() {
        assertEquals(Messages.ZH_TW, Messages.resolve("zh-TW"));
        assertEquals(Messages.ZH_TW, Messages.resolve("zh-HK"));
        assertEquals(Messages.ZH_TW, Messages.resolve("zh-MO"));
        assertEquals(Messages.ZH_TW, Messages.resolve("zh-Hant"));
        assertEquals(Messages.ZH_TW, Messages.resolve("zh-Hant-TW"));
    }

    @Test
    void resolve_簡體中文相關與單純zh() {
        assertEquals(Messages.ZH_CN, Messages.resolve("zh-CN"));
        assertEquals(Messages.ZH_CN, Messages.resolve("zh-SG"));
        assertEquals(Messages.ZH_CN, Messages.resolve("zh-Hans"));
        assertEquals(Messages.ZH_CN, Messages.resolve("zh-Hans-CN"));
        assertEquals(Messages.ZH_CN, Messages.resolve("zh"));
    }

    @Test
    void resolve_英文_依偏好順序_不支援時預設正體中文() {
        assertEquals(Messages.EN, Messages.resolve("en-US"));
        assertEquals(Messages.EN, Messages.resolve("en"));
        assertEquals(Messages.EN, Messages.resolve("ja-JP,en;q=0.8,zh-TW;q=0.5"));
        assertEquals(Messages.ZH_CN, Messages.resolve("fr, zh-CN;q=0.9, en;q=0.8"));
        assertEquals(Messages.ZH_TW, Messages.resolve("ja-JP"));
        assertEquals(Messages.ZH_TW, Messages.resolve(""));
        assertEquals(Messages.ZH_TW, Messages.resolve(null));
        assertEquals(Messages.ZH_TW, Messages.resolve("@@@"));
    }

    // ─── 產生文字 ─────────────────────────────────────────────────

    @Test
    void get_依語系與參數產生文字() {
        Msg msg = Msg.of("device.not_found", "SW-01");
        assertEquals("找不到設備：SW-01", Messages.get(msg, Messages.ZH_TW));
        assertEquals("找不到设备：SW-01", Messages.get(msg, Messages.ZH_CN));
        assertEquals("Device not found: SW-01", Messages.get(msg, Messages.EN));
    }

    @Test
    void get_巢狀訊息() {
        Msg msg = Msg.of("snmp.v3_auth_failed", Msg.of("snmp.report.unknown_user"));
        assertEquals("SNMPv3 authentication failed: unknown user name", Messages.get(msg, Messages.EN));
        assertEquals("SNMPv3 認證失敗：使用者名稱不存在", Messages.get(msg, Messages.ZH_TW));
    }

    @Test
    void get_單引號正確輸出() {
        assertEquals("Only administrators can change other accounts' passwords",
            Messages.get(Msg.of("user.admin_only_password"), Messages.EN));
    }

    @Test
    void get_缺鍵回傳代碼() {
        assertEquals("no.such.key", Messages.get(Msg.of("no.such.key"), Messages.EN));
    }

    @Test
    void current_預設正體中文_可依請求設定() {
        assertEquals("未登入", Msg.of("auth.not_logged_in").text());
        Messages.setCurrent(Messages.EN);
        assertEquals("Not logged in", Msg.of("auth.not_logged_in").text());
    }

    @Test
    void 不受JVM預設語系影響() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertEquals("未登入", Messages.get(Msg.of("auth.not_logged_in"), Messages.ZH_TW));
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            assertEquals("Not logged in", Messages.get(Msg.of("auth.not_logged_in"), Messages.EN));
        } finally {
            Locale.setDefault(saved);
        }
    }

    // ─── 語系檔一致性 ─────────────────────────────────────────────

    private static Properties load(String file) throws Exception {
        try (InputStream in = MessagesTest.class.getResourceAsStream("/i18n/" + file)) {
            assertNotNull(in, file);
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    @Test
    void 三份語系檔的鍵與參數數量一致_且格式可解析() throws Exception {
        Properties tw = load("messages.properties");
        Properties cn = load("messages_zh_CN.properties");
        Properties en = load("messages_en.properties");
        Set<String> keys = new TreeSet<>(tw.stringPropertyNames());
        assertEquals(keys, new TreeSet<>(cn.stringPropertyNames()), "簡體中文的鍵與正體中文不一致");
        assertEquals(keys, new TreeSet<>(en.stringPropertyNames()), "英文的鍵與正體中文不一致");

        for (String k : keys) {
            int args = new MessageFormat(tw.getProperty(k)).getFormatsByArgumentIndex().length;
            for (Properties other : new Properties[]{ cn, en }) {
                String pattern = other.getProperty(k);
                assertFalse(pattern.isBlank(), k + " 不可為空");
                assertEquals(args, new MessageFormat(pattern).getFormatsByArgumentIndex().length, k + " 的參數數量不一致");
            }
        }
    }

    @Test
    void 英文語系檔沒有中文字() throws Exception {
        Properties en = load("messages_en.properties");
        for (String k : en.stringPropertyNames()) {
            assertFalse(en.getProperty(k).matches(".*[\\u4e00-\\u9fff].*"), k + " 的英文訊息含有中文");
        }
    }
}
