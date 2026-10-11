package io.github.chiayi1224.simplesnmp.i18n;

import java.util.Arrays;
import java.util.Objects;

/**
 * 給使用者看的訊息：訊息代碼與參數（不可變）。
 * 文字在輸出時才依語系由 {@link Messages} 產生，因此同一個 Msg 可以用不同語言呈現。
 */
public final class Msg {

    private final String   key;
    private final Object[] args;

    private Msg(String key, Object[] args) {
        this.key  = Objects.requireNonNull(key);
        this.args = args == null ? new Object[0] : args.clone();
    }

    public static Msg of(String key, Object... args) {
        return new Msg(key, args);
    }

    public String key() { return key; }

    public Object[] args() { return args.clone(); }

    /** 依目前請求的語系產生文字 */
    public String text() {
        return Messages.get(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Msg m && key.equals(m.key) && Arrays.equals(args, m.args);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + Arrays.hashCode(args);
    }

    @Override
    public String toString() {
        return args.length == 0 ? key : key + Arrays.toString(args);
    }
}
