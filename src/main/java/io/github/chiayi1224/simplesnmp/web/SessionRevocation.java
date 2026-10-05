package io.github.chiayi1224.simplesnmp.web;

import jakarta.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session 撤銷登記（強制踢出）
 *
 * 管理員重設他人密碼、修改或刪除他人帳號時，撤銷該帳號在此之前建立的所有 session，
 * 下一個請求即由 AuthFilter 判定失效並要求重新登入。使用者自己改自己的密碼不撤銷。
 *
 * 以遞增序號判斷先後，不依賴系統時鐘：
 *   - 登入時在 session 記下登入序號（markLogin）
 *   - 撤銷時記下該帳號的撤銷序號（revokeUser）
 *   - session 的登入序號不大於撤銷序號即視為失效（isRevoked）
 *
 * 只保存在記憶體中：Jetty 的 session 本身也只在記憶體中，重啟後所有 session 都會失效。
 */
public class SessionRevocation {

    /** session 屬性：登入序號 */
    static final String SESSION_ATTR = "auth_seq";

    private final AtomicLong seq = new AtomicLong();
    private final Map<Long, Long> revokedAt = new ConcurrentHashMap<>();

    /** 登入成功後呼叫：在 session 記下登入序號 */
    public void markLogin(HttpSession session) {
        session.setAttribute(SESSION_ATTR, seq.incrementAndGet());
    }

    /** 撤銷該帳號目前所有已建立的 session */
    public void revokeUser(long userId) {
        revokedAt.put(userId, seq.incrementAndGet());
    }

    /** 該 session 是否已被撤銷（沒有登入序號的 session 在帳號被撤銷後一律視為失效） */
    public boolean isRevoked(long userId, HttpSession session) {
        Long revoked = revokedAt.get(userId);
        if (revoked == null) return false;
        Object loginSeq = session.getAttribute(SESSION_ATTR);
        return !(loginSeq instanceof Long) || (Long) loginSeq <= revoked;
    }
}
