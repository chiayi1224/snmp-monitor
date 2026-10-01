package io.github.chiayi1224.simplesnmp.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 共用工具：統一處理 PreparedStatement 參數綁定與 ResultSet 關閉，
 * 讓各 Repository 只需專注在 SQL 與欄位對應。
 */
final class Db {

    /** 將目前 ResultSet 列轉為物件 */
    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection conn;

    Db(Connection conn) {
        this.conn = conn;
    }

    Connection connection() {
        return conn;
    }

    /** 目前時間（ISO-8601 字串，與既有資料格式一致） */
    static String now() {
        return Instant.now().toString();
    }

    /** boolean → SQLite INTEGER（1/0） */
    static int flag(boolean value) {
        return value ? 1 : 0;
    }

    /** SQLite INTEGER（1/0）→ boolean */
    static boolean bool(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) == 1;
    }

    /** 執行 INSERT / UPDATE / DELETE，回傳影響筆數 */
    int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        }
    }

    /** 以同一個 PreparedStatement 批次寫入多筆資料 */
    void batch(String sql, List<Object[]> rows) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : rows) {
                bind(ps, row);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 查詢多筆 */
    <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> list = new ArrayList<>();
        try (PreparedStatement ps = prepare(sql, params);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapper.map(rs));
        }
        return list;
    }

    /** 查詢單筆，查無資料回傳 null */
    <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? mapper.map(rs) : null;
        }
    }

    /** 查詢單一數值（例如 COUNT(*)），查無資料回傳 0 */
    long queryLong(String sql, Object... params) throws SQLException {
        Long value = queryOne(sql, rs -> rs.getLong(1), params);
        return value == null ? 0 : value;
    }

    /** 執行 DDL，失敗時直接忽略（用於欄位已存在的 ALTER TABLE） */
    boolean executeQuietly(String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        try {
            bind(ps, params);
            return ps;
        } catch (SQLException ex) {
            ps.close();
            throw ex;
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
