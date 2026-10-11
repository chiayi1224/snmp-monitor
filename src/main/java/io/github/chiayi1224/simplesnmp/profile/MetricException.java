package io.github.chiayi1224.simplesnmp.profile;

import io.github.chiayi1224.simplesnmp.i18n.LocalizedException;
import io.github.chiayi1224.simplesnmp.i18n.Msg;

/**
 * 單一指標無法計算（OID 不存在、值不是數字、總量為 0 等）
 */
public class MetricException extends LocalizedException {

    public MetricException(Msg msg) {
        super(msg);
    }
}
