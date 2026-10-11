package io.github.chiayi1224.simplesnmp.profile;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 範本中的一個指標定義
 *
 * @param metricKey 指標名稱：cpu、memory、disk 或自訂（小寫英數與底線，2～32 字元）
 * @param kind      計算類型
 * @param params    計算類型所需的參數（OID 等）
 * @param unit      顯示用單位（例如 "%"、"°C"）
 * @param enabled   是否收集
 */
public record ProfileMetric(String metricKey, MetricKind kind, Map<String, String> params,
                            String unit, boolean enabled) {

    public static final String CPU    = "cpu";
    public static final String MEMORY = "memory";
    public static final String DISK   = "disk";

    /** 設備連線中斷告警使用的指標名稱，保留給系統使用，不可作為自訂指標或閾值名稱 */
    public static final String DEVICE_UNREACHABLE = "device_unreachable";

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");

    public ProfileMetric {
        params = params == null ? Map.of() : Map.copyOf(params);
        unit   = unit == null ? "" : unit;
    }

    /** 是否為 CPU / 記憶體 / 磁碟以外的自訂指標 */
    public boolean isCustom() {
        return isCustomKey(metricKey);
    }

    /** 指標名稱是否合法（小寫英文開頭，只含小寫英數與底線，2～32 字元） */
    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    /** 是否為系統保留的指標名稱 */
    public static boolean isReservedKey(String key) {
        return DEVICE_UNREACHABLE.equals(key);
    }

    /** 是否為 CPU / 記憶體 / 磁碟以外的指標名稱 */
    public static boolean isCustomKey(String key) {
        return !CPU.equals(key) && !MEMORY.equals(key) && !DISK.equals(key);
    }

    /** 合法回傳 null，否則回傳錯誤訊息 */
    public String validate() {
        if (!isValidKey(metricKey)) {
            return "指標名稱 '" + metricKey + "' 不合法：須為小寫英文開頭，只含小寫英數與底線，2～32 字元";
        }
        if (isReservedKey(metricKey)) {
            return "指標名稱 '" + metricKey + "' 為系統保留名稱";
        }
        if (kind == null) {
            return "指標 " + metricKey + " 的計算類型不支援，可用：" + java.util.Arrays.toString(MetricKind.values());
        }
        String error = kind.validateParams(params);
        if (error != null) return "指標 " + metricKey + "：" + error;

        // disk 依掛載點產生多個值；其他指標只能產生單一值
        boolean multi = kind == MetricKind.STORAGE_TABLE && !Boolean.parseBoolean(params.get("first"));
        if (DISK.equals(metricKey) && !multi) {
            return "指標 disk 必須使用 STORAGE_TABLE（不設定 first），以便依掛載點分別記錄";
        }
        if (!DISK.equals(metricKey) && multi) {
            return "指標 " + metricKey + " 使用 STORAGE_TABLE 時必須設定 first=true（只取單一值）";
        }
        return null;
    }
}
