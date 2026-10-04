package io.github.chiayi1224.simplesnmp.snmp;

import java.io.IOException;

/**
 * SNMP 查詢失敗（設備無回應、v3 認證失敗、回應錯誤狀態等），訊息可直接顯示給使用者
 */
public class SnmpException extends IOException {

    public SnmpException(String message) {
        super(message);
    }
}
