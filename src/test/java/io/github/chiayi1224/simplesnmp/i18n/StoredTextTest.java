package io.github.chiayi1224.simplesnmp.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoredTextTest {

    @AfterEach
    void tearDown() {
        Messages.clearCurrent();
    }

    @Test
    void 無參數_編碼解碼() {
        String stored = StoredText.encode(Msg.of("alert.unreachable.snmp"));
        assertEquals("@i18n:alert.unreachable.snmp", stored);
        assertEquals(Msg.of("alert.unreachable.snmp"), StoredText.decode(stored));
    }

    @Test
    void 有參數與巢狀訊息_依讀取時的語系產生文字() {
        String stored = StoredText.encode(Msg.of("scan.walk_failed", "1.3.6.1", Msg.of("snmp.no_response")));
        Messages.setCurrent(Messages.EN);
        assertEquals("Walking 1.3.6.1 failed: No response from device", StoredText.localize(stored));
        Messages.setCurrent(Messages.ZH_CN);
        assertEquals("遍历 1.3.6.1 失败：设备无响应", StoredText.localize(stored));
    }

    @Test
    void 數字參數保留() {
        Msg decoded = StoredText.decode(StoredText.encode(Msg.of("probe.sample.interfaces", 3, "eth0")));
        assertEquals(3L, decoded.args()[0]);
        assertEquals("eth0", decoded.args()[1]);
    }

    @Test
    void 舊文字與Trap原文原樣輸出() {
        assertEquals("SNMP 無回應", StoredText.localize("SNMP 無回應"));
        assertEquals("1.3.6.1.4.1.9999.7.10=<script>", StoredText.localize("1.3.6.1.4.1.9999.7.10=<script>"));
        assertNull(StoredText.localize(null));
        assertNull(StoredText.decode("plain"));
    }

    @Test
    void 參數JSON損壞時只用代碼() {
        assertEquals(Msg.of("alert.device_deleted"), StoredText.decode("@i18n:alert.device_deleted|not-json"));
    }
}
