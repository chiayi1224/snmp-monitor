package io.github.chiayi1224.simplesnmp.config;

/**
 * 單一受監控設備的設定
 */
public class DeviceConfig {

    private final String name;        // 顯示名稱，例如 "Switch-01"
    private final String host;        // IP 或 hostname
    private final int port;           // SNMP port，通常 161
    private final String community;   // v2c community string，例如 "public"
    private final int retries;
    private final long timeoutMs;

    public DeviceConfig(String name, String host, String community) {
        this(name, host, 161, community, 2, 3000L);
    }

    public DeviceConfig(String name, String host, int port,
                        String community, int retries, long timeoutMs) {
        this.name      = name;
        this.host      = host;
        this.port      = port;
        this.community = community;
        this.retries   = retries;
        this.timeoutMs = timeoutMs;
    }

    public String getName()      { return name; }
    public String getHost()      { return host; }
    public int    getPort()      { return port; }
    public String getCommunity() { return community; }
    public int    getRetries()   { return retries; }
    public long   getTimeoutMs() { return timeoutMs; }

    /** 組成 SNMP4J 用的 address 字串 */
    public String getUdpAddress() {
        return "udp:" + host + "/" + port;
    }

    @Override
    public String toString() {
        return name + "(" + host + ":" + port + ")";
    }
}
