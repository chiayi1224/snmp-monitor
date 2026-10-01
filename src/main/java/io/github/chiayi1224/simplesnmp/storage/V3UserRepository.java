package io.github.chiayi1224.simplesnmp.storage;

import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SNMPv3 Trap 使用者（snmp_v3_users）
 */
final class V3UserRepository {

    private static final Logger log = LoggerFactory.getLogger(V3UserRepository.class);

    private final Db db;

    V3UserRepository(Db db) {
        this.db = db;
    }

    void seedDefaults() {
        try {
            if (db.queryLong("SELECT COUNT(*) FROM snmp_v3_users") == 0) {
                add("trapUser", "myAuthPass", "SHA", "myPrivPass", "AES128");
                log.info("已寫入預設 SNMPv3 使用者");
            }
        } catch (Exception ex) {
            log.error("seedDefaultV3Users 失敗: {}", ex.getMessage());
        }
    }

    List<V3UserConfig> loadEnabled() {
        String sql = "SELECT username, auth_password, auth_protocol, priv_password, priv_protocol FROM snmp_v3_users WHERE enabled=1";
        try {
            return db.query(sql, V3UserRepository::toConfig);
        } catch (SQLException ex) {
            log.error("loadEnabledV3Users 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    List<Map<String, Object>> queryAll() {
        String sql = "SELECT id, username, auth_protocol, priv_protocol, enabled, created, updated FROM snmp_v3_users ORDER BY username";
        try {
            return db.query(sql, rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getLong("id"));
                row.put("username",     rs.getString("username"));
                row.put("authProtocol", rs.getString("auth_protocol"));
                row.put("privProtocol", rs.getString("priv_protocol"));
                row.put("enabled",      Db.bool(rs, "enabled"));
                row.put("created",      rs.getString("created"));
                row.put("updated",      rs.getString("updated"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("queryAllV3Users 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    void add(String username, String authPassword, String authProtocol,
             String privPassword, String privProtocol) throws SQLException {
        String now = Db.now();
        db.update("INSERT INTO snmp_v3_users(username, auth_password, auth_protocol, priv_password, priv_protocol, enabled, created, updated) VALUES(?,?,?,?,?,1,?,?)",
            username, authPassword, authProtocol, privPassword, privProtocol, now, now);
    }

    void update(long id, String username, String authPassword, String authProtocol,
                String privPassword, String privProtocol, boolean enabled) throws SQLException {
        db.update("UPDATE snmp_v3_users SET username=?, auth_password=?, auth_protocol=?, priv_password=?, priv_protocol=?, enabled=?, updated=? WHERE id=?",
            username, authPassword, authProtocol, privPassword, privProtocol, Db.flag(enabled), Db.now(), id);
    }

    void delete(long id) throws SQLException {
        db.update("DELETE FROM snmp_v3_users WHERE id=?", id);
    }

    /** 未設定 auth 密碼 → noAuthNoPriv；有 auth 無 priv → authNoPriv；兩者皆有 → authPriv */
    private static V3UserConfig toConfig(ResultSet rs) throws SQLException {
        String authPass  = rs.getString("auth_password");
        String privPass  = rs.getString("priv_password");
        V3UserConfig.Builder b = new V3UserConfig.Builder(rs.getString("username"));
        if (authPass != null && !authPass.isBlank()) {
            b.authPassword(authPass);
            b.authProtocol("MD5".equalsIgnoreCase(rs.getString("auth_protocol"))
                ? V3UserConfig.AuthProtocol.MD5 : V3UserConfig.AuthProtocol.SHA);
            if (privPass != null && !privPass.isBlank()) {
                b.privPassword(privPass);
                b.privProtocol("DES".equalsIgnoreCase(rs.getString("priv_protocol"))
                    ? V3UserConfig.PrivProtocol.DES : V3UserConfig.PrivProtocol.AES128);
            }
        }
        return b.build();
    }
}
