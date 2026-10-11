package io.github.chiayi1224.simplesnmp.i18n;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 存入資料庫的系統文字：保存訊息代碼，讀取時才依語系產生文字。
 *
 * 格式：{@code @i18n:<key>} 或 {@code @i18n:<key>|<參數的 JSON 陣列>}。
 * 參數可以是字串、數字或巢狀的 {@code {"key":..,"args":[..]}}（另一則訊息）。
 * 不以 {@code @i18n:} 開頭的文字（升級前的舊資料、Trap 原文）原樣顯示。
 */
public final class StoredText {

    static final String PREFIX = "@i18n:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private StoredText() {}

    public static String encode(Msg msg) {
        Object[] args = msg.args();
        if (args.length == 0) return PREFIX + msg.key();
        try {
            return PREFIX + msg.key() + "|" + JSON.writeValueAsString(toJson(args));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode message arguments: " + msg, e);
        }
    }

    private static List<Object> toJson(Object[] args) {
        List<Object> out = new ArrayList<>();
        for (Object a : args) {
            if (a instanceof Msg m) out.add(java.util.Map.of("key", m.key(), "args", toJson(m.args())));
            else if (a instanceof Number || a instanceof String || a == null) out.add(a);
            else out.add(String.valueOf(a));
        }
        return out;
    }

    /** 是否為編碼過的系統文字 */
    public static boolean isEncoded(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** 解碼為訊息；不是編碼文字時回傳 null */
    public static Msg decode(String stored) {
        if (!isEncoded(stored)) return null;
        String body = stored.substring(PREFIX.length());
        int bar = body.indexOf('|');
        if (bar < 0) return Msg.of(body);
        try {
            return Msg.of(body.substring(0, bar), fromJson(JSON.readTree(body.substring(bar + 1))));
        } catch (JsonProcessingException e) {
            return Msg.of(body.substring(0, bar));
        }
    }

    private static Object[] fromJson(JsonNode array) {
        List<Object> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (n.isObject() && n.has("key")) out.add(Msg.of(n.get("key").asText(), fromJson(n.path("args"))));
            else if (n.isIntegralNumber()) out.add(n.asLong());
            else if (n.isNumber()) out.add(n.asDouble());
            else if (n.isNull()) out.add(null);
            else out.add(n.asText());
        }
        return out.toArray();
    }

    /** 讀取時轉成目前請求語系的文字；不是編碼文字時原樣回傳（null 維持 null） */
    public static String localize(String stored) {
        Msg msg = decode(stored);
        return msg == null ? stored : Messages.get(msg);
    }
}
