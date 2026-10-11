package io.github.chiayi1224.simplesnmp.i18n;

/**
 * 帶訊息代碼的例外：{@link #getMessage()} 回傳英文（供 log 使用），
 * 給使用者的文字以 {@link #msg()} 依請求語系另行產生。
 */
public class LocalizedException extends Exception implements Localized {

    private final transient Msg msg;

    public LocalizedException(Msg msg) {
        super(Messages.get(msg, Messages.EN));
        this.msg = msg;
    }

    public LocalizedException(Msg msg, Throwable cause) {
        super(Messages.get(msg, Messages.EN), cause);
        this.msg = msg;
    }

    @Override
    public Msg msg() {
        return msg;
    }
}
