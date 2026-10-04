package io.github.chiayi1224.simplesnmp.profile;

/**
 * 單一指標無法計算（OID 不存在、值不是數字、總量為 0 等），訊息可直接顯示給使用者
 */
public class MetricException extends Exception {

    public MetricException(String message) {
        super(message);
    }
}
