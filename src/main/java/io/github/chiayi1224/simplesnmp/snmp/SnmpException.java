package io.github.chiayi1224.simplesnmp.snmp;

import io.github.chiayi1224.simplesnmp.i18n.Localized;
import io.github.chiayi1224.simplesnmp.i18n.Messages;
import io.github.chiayi1224.simplesnmp.i18n.Msg;

import java.io.IOException;

/**
 * SNMP 查詢失敗（設備無回應、v3 認證失敗、回應錯誤狀態等）。
 * {@link #getMessage()} 為英文（供 log 使用），給使用者的文字以 {@link #msg()} 依語系產生。
 */
public class SnmpException extends IOException implements Localized {

    private final transient Msg msg;

    public SnmpException(Msg msg) {
        super(Messages.get(msg, Messages.EN));
        this.msg = msg;
    }

    @Override
    public Msg msg() {
        return msg;
    }
}
