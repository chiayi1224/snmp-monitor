package io.github.chiayi1224.simplesnmp.profile;

/**
 * 計算出的一個指標值
 *
 * @param instance   多值指標的實例名稱（例如磁碟掛載點）；單值指標為 null
 * @param value      數值（百分比類型為 0～100）
 * @param totalBytes 容量類指標的總量（位元組），其他類型為 null
 * @param usedBytes  容量類指標的已用量（位元組），其他類型為 null
 */
public record MetricValue(String instance, double value, Long totalBytes, Long usedBytes) {

    public static MetricValue of(double value) {
        return new MetricValue(null, value, null, null);
    }
}
