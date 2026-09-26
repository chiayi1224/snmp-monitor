package com.syscom.snmp.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API Token（api_tokens）
 */
final class ApiTokenRepository {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenRepository.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;

    ApiTokenRepository(Db db) {
        this.db = db;
    }

    /** 新增 Token，回傳 token 字串 */
    String create(String description, String expiresAt) throws SQLException {
        String token = generateToken();
        db.update("INSERT INTO api_tokens(token, description, enabled, expires_at, created_at) VALUES(?,?,1,?,?)",
            token, description, expiresAt, Db.now());
        return token;
    }

    /** 列出所有 token（不含 token 明文） */
    List<Map<String, Object>> queryAll() {
        String sql = "SELECT id, description, enabled, expires_at, created_at, last_used_at FROM api_tokens ORDER BY created_at DESC";
        try {
            return db.query(sql, rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",          rs.getLong("id"));
                row.put("description", rs.getString("description"));
                row.put("enabled",     Db.bool(rs, "enabled"));
                row.put("expiresAt",   rs.getString("expires_at"));
                row.put("createdAt",   rs.getString("created_at"));
                row.put("lastUsedAt",  rs.getString("last_used_at"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("queryAllApiTokens 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 驗證 token：token 存在、enabled=1、未過期 → 更新 last_used_at 並回傳 {id, description}
     * 否則回 null
     */
    Map<String, Object> validate(String token) {
        try {
            Map<String, Object> info = db.queryOne(
                "SELECT id, description, expires_at FROM api_tokens WHERE token=? AND enabled=1", rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          rs.getLong("id"));
                    m.put("description", rs.getString("description"));
                    m.put("expiresAt",   rs.getString("expires_at"));
                    return m;
                }, token);
            if (info == null) return null;

            String expiresAt = (String) info.remove("expiresAt");
            if (expiresAt != null && Instant.now().isAfter(Instant.parse(expiresAt))) return null;

            db.update("UPDATE api_tokens SET last_used_at=? WHERE id=?", Db.now(), info.get("id"));
            return info;
        } catch (SQLException ex) {
            log.error("validateApiToken 失敗: {}", ex.getMessage());
            return null;
        }
    }

    void update(long id, String description, boolean enabled, String expiresAt) throws SQLException {
        db.update("UPDATE api_tokens SET description=?, enabled=?, expires_at=? WHERE id=?",
            description, Db.flag(enabled), expiresAt, id);
    }

    void delete(long id) throws SQLException {
        db.update("DELETE FROM api_tokens WHERE id=?", id);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "snmp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
