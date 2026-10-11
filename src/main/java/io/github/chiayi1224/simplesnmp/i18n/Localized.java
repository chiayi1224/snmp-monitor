package io.github.chiayi1224.simplesnmp.i18n;

/**
 * 帶訊息代碼的例外或結果（例外無法共用同一個基底類別時以此介面識別，例如繼承 IOException 的 SnmpException）
 */
public interface Localized {

    /** 給使用者看的訊息 */
    Msg msg();
}
