package io.github.chiayi1224.simplesnmp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.security.AuthHMAC128SHA224;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC256SHA384;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.smi.OID;

import java.util.Locale;

/**
 * SNMPv3 使用者設定
 *
 * 對應三種 Security Level：
 *   noAuthNoPriv — 只設 username
 *   authNoPriv   — 設 username + authPassword + authProtocol
 *   authPriv     — 全設
 */
public class V3UserConfig {

    private static final Logger log = LoggerFactory.getLogger(V3UserConfig.class);

    /** 認證協定（SHA = SHA-1；SHA224 以上為 RFC 7860 的 HMAC-SHA-2） */
    public enum AuthProtocol {
        MD5(AuthMD5.ID), SHA(AuthSHA.ID),
        SHA224(AuthHMAC128SHA224.ID), SHA256(AuthHMAC192SHA256.ID),
        SHA384(AuthHMAC256SHA384.ID), SHA512(AuthHMAC384SHA512.ID);

        private final OID oid;
        AuthProtocol(OID oid) { this.oid = oid; }
        public OID oid() { return oid; }

        /** 大小寫不敏感解析，接受 "SHA-256" 等含連字號寫法；null、空白或未知值回傳 SHA */
        public static AuthProtocol parse(String s) {
            return parseEnum(AuthProtocol.class, s, SHA);
        }
    }

    /** 加密協定 */
    public enum PrivProtocol {
        DES(PrivDES.ID), AES128(PrivAES128.ID), AES192(PrivAES192.ID), AES256(PrivAES256.ID);

        private final OID oid;
        PrivProtocol(OID oid) { this.oid = oid; }
        public OID oid() { return oid; }

        /** 大小寫不敏感解析，接受 "AES-256"、"AES"（= AES128）；null、空白或未知值回傳 AES128 */
        public static PrivProtocol parse(String s) {
            if (s != null && s.trim().equalsIgnoreCase("AES")) return AES128;
            return parseEnum(PrivProtocol.class, s, AES128);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String s, E fallback) {
        if (s == null || s.isBlank()) return fallback;
        String name = s.trim().replace("-", "").toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            log.warn("未知的 SNMPv3 {} '{}'，改用預設 {}", type.getSimpleName(), s, fallback);
            return fallback;
        }
    }

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

    public AuthProtocol getAuthProtocol() { return authProtocol; }
    public PrivProtocol getPrivProtocol() { return privProtocol; }

    /** 轉換成 SNMP4J 的 authProtocol OID */
    public OID getAuthProtocolOid() { return authProtocol.oid(); }

    /** 轉換成 SNMP4J 的 privProtocol OID */
    public OID getPrivProtocolOid() { return privProtocol.oid(); }

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
