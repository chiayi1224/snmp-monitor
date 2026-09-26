package com.syscom.snmp.config;

import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivDES;

/**
 * SNMPv3 使用者設定
 *
 * 對應三種 Security Level：
 *   noAuthNoPriv — 只設 username
 *   authNoPriv   — 設 username + authPassword + authProtocol
 *   authPriv     — 全設
 */
public class V3UserConfig {

    public enum AuthProtocol   { MD5, SHA }
    public enum PrivProtocol   { DES, AES128 }

    private final String       username;
    private final String       authPassword;   // null = noAuth
    private final String       privPassword;   // null = noPriv
    private final AuthProtocol authProtocol;
    private final PrivProtocol privProtocol;

    private V3UserConfig(Builder b) {
        this.username     = b.username;
        this.authPassword = b.authPassword;
        this.privPassword = b.privPassword;
        this.authProtocol = b.authProtocol;
        this.privProtocol = b.privProtocol;
    }

    // ─── 常用快捷建構 ─────────────────────────────────────────────

    /** noAuthNoPriv */
    public static V3UserConfig noAuth(String username) {
        return new Builder(username).build();
    }

    /** authNoPriv（預設 SHA）*/
    public static V3UserConfig authOnly(String username, String authPassword) {
        return new Builder(username)
            .authPassword(authPassword)
            .authProtocol(AuthProtocol.SHA)
            .build();
    }

    /** authPriv（預設 SHA + AES128）*/
    public static V3UserConfig authPriv(String username, String authPassword, String privPassword) {
        return new Builder(username)
            .authPassword(authPassword)
            .authProtocol(AuthProtocol.SHA)
            .privPassword(privPassword)
            .privProtocol(PrivProtocol.AES128)
            .build();
    }

    // ─── Getter ───────────────────────────────────────────────────

    public String getUsername()     { return username; }
    public String getAuthPassword() { return authPassword; }
    public String getPrivPassword() { return privPassword; }
    public boolean hasAuth()        { return authPassword != null; }
    public boolean hasPriv()        { return privPassword != null; }

    /** 轉換成 SNMP4J 的 authProtocol OID */
    public org.snmp4j.smi.OID getAuthProtocolOid() {
        if (authProtocol == AuthProtocol.MD5) return AuthMD5.ID;
        return AuthSHA.ID;
    }

    /** 轉換成 SNMP4J 的 privProtocol OID */
    public org.snmp4j.smi.OID getPrivProtocolOid() {
        if (privProtocol == PrivProtocol.DES) return PrivDES.ID;
        return PrivAES128.ID;
    }

    // ─── Builder ──────────────────────────────────────────────────

    public static class Builder {
        private final String username;
        private String       authPassword = null;
        private String       privPassword = null;
        private AuthProtocol authProtocol = AuthProtocol.SHA;
        private PrivProtocol privProtocol = PrivProtocol.AES128;

        public Builder(String username)              { this.username     = username; }
        public Builder authPassword(String v)        { this.authPassword = v; return this; }
        public Builder privPassword(String v)        { this.privPassword = v; return this; }
        public Builder authProtocol(AuthProtocol v)  { this.authProtocol = v; return this; }
        public Builder privProtocol(PrivProtocol v)  { this.privProtocol = v; return this; }
        public V3UserConfig build()                  { return new V3UserConfig(this); }
    }
}
