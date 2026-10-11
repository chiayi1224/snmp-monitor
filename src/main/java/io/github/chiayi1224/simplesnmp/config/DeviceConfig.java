package io.github.chiayi1224.simplesnmp.config;

import io.github.chiayi1224.simplesnmp.i18n.Msg;
import java.util.Locale;

/**
 * 單一受監控設備的設定（含 SNMP 版本與 v3 認證）
 *
 * v3 密碼欄位的意義：
 *   null — 未提供（更新時代表沿用資料庫中的舊值）
 *   ""   — 未設定（新增）或清除（更新）
 */
public class DeviceConfig {

    /** SNMP 版本，label 為 API 與資料庫使用的字串 */
    public enum SnmpVersion {
        V1("1"), V2C("2c"), V3("3");

        private final String label;
        SnmpVersion(String label) { this.label = label; }
        public String label() { return label; }

        /** 解析 "1"、"2c"、"3"（大小寫不敏感）；null 或空白回傳 V2C；不合法回傳 null */
        public static SnmpVersion parse(String s) {
            if (s == null || s.isBlank()) return V2C;
            String v = s.trim().toLowerCase(Locale.ROOT);
            for (SnmpVersion version : values()) {
                if (version.label.equals(v)) return version;
            }
            return null;
        }
    }

    /** 連線中斷告警等級的合法值（"" 代表關閉） */
    public static final java.util.Set<String> UNREACHABLE_SEVERITIES = java.util.Set.of("", "WARNING", "CRITICAL");

    /** SNMPv3 規定密碼至少 8 個字元 */
    public static final int V3_MIN_PASSWORD_LENGTH = 8;

    private final String name;        // 顯示名稱，例如 "Switch-01"
    private final String host;        // IP 或 hostname
    private final int port;           // SNMP port，通常 161
    private final String community;   // v1 / v2c community string，例如 "public"
    private final int retries;
    private final long timeoutMs;

    private final SnmpVersion                 snmpVersion;
    private final String                      v3User;
    private final V3UserConfig.AuthProtocol   v3AuthProtocol;
    private final String                      v3AuthPassword;
    private final V3UserConfig.PrivProtocol   v3PrivProtocol;
    private final String                      v3PrivPassword;
    private final Long                        profileId;     // 手動指定的監控範本；null 代表依 sysObjectID 自動套用
    private final String                      sysObjectId;   // 最近一次輪詢取得的 sysObjectID；未知為 ""
    private final String                      diskFilter;    // 只記錄符合的磁碟（見 DiskFilter）；"" 代表全部
    private final String                      unreachableSeverity; // 連線中斷告警等級：""（關閉）、WARNING、CRITICAL
    private final int                         unreachableCount;    // 連續幾次無法連線才告警

    public DeviceConfig(String name, String host, String community) {
        this(name, host, 161, community, 2, 3000L);
    }

    public DeviceConfig(String name, String host, int port,
                        String community, int retries, long timeoutMs) {
        this(new Builder(name, host).port(port).community(community).retries(retries).timeoutMs(timeoutMs));
    }

    private DeviceConfig(Builder b) {
        this.name           = b.name;
        this.host           = b.host;
        this.port           = b.port;
        this.community      = b.community;
        this.retries        = b.retries;
        this.timeoutMs      = b.timeoutMs;
        this.snmpVersion    = b.snmpVersion;
        this.v3User         = b.v3User;
        this.v3AuthProtocol = b.v3AuthProtocol;
        this.v3AuthPassword = b.v3AuthPassword;
        this.v3PrivProtocol = b.v3PrivProtocol;
        this.v3PrivPassword = b.v3PrivPassword;
        this.profileId      = b.profileId;
        this.sysObjectId    = b.sysObjectId;
        this.diskFilter     = b.diskFilter;
        this.unreachableSeverity = b.unreachableSeverity;
        this.unreachableCount    = b.unreachableCount;
    }

    public String getName()      { return name; }
    public String getHost()      { return host; }
    public int    getPort()      { return port; }
    public String getCommunity() { return community; }
    public int    getRetries()   { return retries; }
    public long   getTimeoutMs() { return timeoutMs; }

    public SnmpVersion               getSnmpVersion()    { return snmpVersion; }
    public String                    getV3User()         { return v3User; }
    public V3UserConfig.AuthProtocol getV3AuthProtocol() { return v3AuthProtocol; }
    public String                    getV3AuthPassword() { return v3AuthPassword; }
    public V3UserConfig.PrivProtocol getV3PrivProtocol() { return v3PrivProtocol; }
    public String                    getV3PrivPassword() { return v3PrivPassword; }
    public Long                      getProfileId()      { return profileId; }
    public String                    getSysObjectId()    { return sysObjectId; }
    public String                    getDiskFilter()     { return diskFilter; }
    public String                    getUnreachableSeverity() { return unreachableSeverity; }
    public int                       getUnreachableCount()    { return unreachableCount; }

    /** 組成 SNMP4J 用的 address 字串 */
    public String getUdpAddress() {
        return "udp:" + host + "/" + port;
    }

    /** 轉成 SNMP4J USM 使用者設定；空白密碼視為未設定 */
    public V3UserConfig toV3User() {
        V3UserConfig.Builder b = new V3UserConfig.Builder(v3User == null ? "" : v3User);
        if (isSet(v3AuthPassword)) {
            b.authPassword(v3AuthPassword).authProtocol(v3AuthProtocol);
            if (isSet(v3PrivPassword)) b.privPassword(v3PrivPassword).privProtocol(v3PrivProtocol);
        }
        return b.build();
    }

    /**
     * 驗證設定；合法回傳 null，否則回傳錯誤訊息。
     * 密碼為 null（沿用舊值）時不檢查長度與 auth / priv 搭配，由更新端在合併舊值後再驗證。
     */
    public Msg validate() {
        if (snmpVersion == null) return Msg.of("device.version_invalid");
        if (diskFilter.length() > DiskFilter.MAX_LENGTH) return Msg.of("device.disk_filter_too_long", DiskFilter.MAX_LENGTH);
        if (!UNREACHABLE_SEVERITIES.contains(unreachableSeverity)) return Msg.of("device.unreachable_severity_invalid");
        if (unreachableCount < 1) return Msg.of("device.unreachable_count_invalid");
        if (snmpVersion != SnmpVersion.V3) return null;

        if (v3User == null || v3User.isBlank()) return Msg.of("device.v3_user_required");
        if (v3AuthPassword == null || v3PrivPassword == null) return null;
        if (isSet(v3PrivPassword) && !isSet(v3AuthPassword)) return Msg.of("device.v3_priv_needs_auth");
        if (isSet(v3AuthPassword) && v3AuthPassword.length() < V3_MIN_PASSWORD_LENGTH)
            return Msg.of("device.v3_auth_too_short", V3_MIN_PASSWORD_LENGTH);
        if (isSet(v3PrivPassword) && v3PrivPassword.length() < V3_MIN_PASSWORD_LENGTH)
            return Msg.of("device.v3_priv_too_short", V3_MIN_PASSWORD_LENGTH);
        return null;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isEmpty();
    }

    @Override
    public String toString() {
        return name + "(" + host + ":" + port + ", v" + (snmpVersion == null ? "?" : snmpVersion.label()) + ")";
    }

    // ─── Builder ──────────────────────────────────────────────────

    public static class Builder {
        private final String name;
        private final String host;
        private int    port      = 161;
        private String community = "public";
        private int    retries   = 2;
        private long   timeoutMs = 3000L;

        private SnmpVersion               snmpVersion    = SnmpVersion.V2C;
        private String                    v3User         = "";
        private V3UserConfig.AuthProtocol v3AuthProtocol = V3UserConfig.AuthProtocol.SHA;
        private String                    v3AuthPassword = "";
        private V3UserConfig.PrivProtocol v3PrivProtocol = V3UserConfig.PrivProtocol.AES128;
        private String                    v3PrivPassword = "";
        private Long                      profileId      = null;
        private String                    sysObjectId    = "";
        private String                    diskFilter     = "";
        private String                    unreachableSeverity = "";
        private int                       unreachableCount    = 1;

        public Builder(String name, String host) { this.name = name; this.host = host; }

        public Builder port(int v)                               { this.port = v; return this; }
        public Builder community(String v)                       { this.community = v; return this; }
        public Builder retries(int v)                            { this.retries = v; return this; }
        public Builder timeoutMs(long v)                         { this.timeoutMs = v; return this; }
        public Builder snmpVersion(SnmpVersion v)                { this.snmpVersion = v; return this; }
        public Builder v3User(String v)                          { this.v3User = v; return this; }
        public Builder v3AuthProtocol(V3UserConfig.AuthProtocol v) { this.v3AuthProtocol = v; return this; }
        public Builder v3AuthPassword(String v)                  { this.v3AuthPassword = v; return this; }
        public Builder v3PrivProtocol(V3UserConfig.PrivProtocol v) { this.v3PrivProtocol = v; return this; }
        public Builder v3PrivPassword(String v)                  { this.v3PrivPassword = v; return this; }
        public Builder profileId(Long v)                         { this.profileId = v; return this; }
        public Builder sysObjectId(String v)                     { this.sysObjectId = v == null ? "" : v; return this; }
        public Builder diskFilter(String v)                      { this.diskFilter = DiskFilter.normalize(v); return this; }
        public Builder unreachableSeverity(String v)             { this.unreachableSeverity = v == null ? "" : v.trim().toUpperCase(Locale.ROOT); return this; }
        public Builder unreachableCount(int v)                   { this.unreachableCount = v; return this; }

        public DeviceConfig build() { return new DeviceConfig(this); }
    }
}
