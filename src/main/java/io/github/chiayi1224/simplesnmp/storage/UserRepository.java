package io.github.chiayi1224.simplesnmp.storage;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理帳號（users）
 */
final class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final Db db;

    UserRepository(Db db) {
        this.db = db;
    }

    /**
     * 第一次啟動時若沒有任何帳號，自動建立預設管理員
     * 帳號：admin  密碼：admin（請登入後立即修改）
     */
    void seedDefaults() {
        try {
            if (db.queryLong("SELECT COUNT(*) FROM users") == 0) {
                add("admin", "admin", "admin", true);
                // 初始密碼固定並記載於 README，不寫入 log
                log.warn("已建立預設管理員帳號 admin（初始密碼見 README），首次登入須修改密碼！");
            }
        } catch (SQLException ex) {
            log.error("seedDefaultAdmin 失敗: {}", ex.getMessage());
        }
    }

    /** 驗證帳號密碼，成功回傳帳號資訊 Map，失敗回傳 null */
    Map<String, Object> authenticate(String username, String password) {
        String sql = "SELECT id, username, password_hash, role, must_change FROM users WHERE username=? AND enabled=1";
        try {
            return db.queryOne(sql, rs -> {
                if (!BCrypt.checkpw(password, rs.getString("password_hash"))) return null;
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("id",         rs.getLong("id"));
                user.put("username",   rs.getString("username"));
                user.put("role",       rs.getString("role"));
                user.put("mustChange", Db.bool(rs, "must_change"));
                return user;
            }, username);
        } catch (SQLException ex) {
            log.error("authenticate 失敗: {}", ex.getMessage());
            return null;
        }
    }

    /** 取得所有帳號（不含密碼 hash） */
    List<Map<String, Object>> queryAll() {
        String sql = "SELECT id, username, role, enabled, must_change, created, updated FROM users ORDER BY username";
        try {
            return db.query(sql, rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",         rs.getLong("id"));
                row.put("username",   rs.getString("username"));
                row.put("role",       rs.getString("role"));
                row.put("enabled",    Db.bool(rs, "enabled"));
                row.put("mustChange", Db.bool(rs, "must_change"));
                row.put("created",    rs.getString("created"));
                row.put("updated",    rs.getString("updated"));
                return row;
            });
        } catch (SQLException ex) {
            log.error("queryAllUsers 失敗: {}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 新增帳號（mustChange=true 代表第一次登入必須改密碼） */
    void add(String username, String password, String role, boolean mustChange) throws SQLException {
        String now  = Db.now();
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        db.update("INSERT INTO users(username, password_hash, role, enabled, must_change, created, updated) VALUES(?,?,?,1,?,?,?)",
            username, hash, role, Db.flag(mustChange), now, now);
        log.info("新增帳號: {} ({}) mustChange={}", username, role, mustChange);
    }

    /** 修改密碼，並清除強制改密碼旗標 */
    void changePassword(long id, String newPassword) throws SQLException {
        String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        db.update("UPDATE users SET password_hash=?, must_change=0, updated=? WHERE id=?", hash, Db.now(), id);
        log.info("帳號 id={} 密碼已更新，must_change 已清除", id);
    }

    /** 更新帳號（不含密碼） */
    void update(long id, String username, String role, boolean enabled) throws SQLException {
        db.update("UPDATE users SET username=?, role=?, enabled=?, updated=? WHERE id=?",
            username, role, Db.flag(enabled), Db.now(), id);
        log.info("更新帳號 id={}: {} enabled={}", id, username, enabled);
    }

    /** 刪除帳號（保護：至少留一個啟用帳號） */
    void delete(long id) throws SQLException {
        if (db.queryLong("SELECT COUNT(*) FROM users WHERE enabled=1 AND id != ?", id) == 0) {
            throw new SQLException("至少需要保留一個啟用的帳號");
        }
        db.update("DELETE FROM users WHERE id=?", id);
        log.info("刪除帳號 id={}", id);
    }
}
