package io.github.chiayi1224.simplesnmp.web;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SessionRevocationTest {

    /** 以 Map 模擬 session 屬性 */
    private static HttpSession fakeSession() {
        Map<String, Object> attrs = new HashMap<>();
        HttpSession s = mock(HttpSession.class);
        doAnswer(inv -> attrs.put(inv.getArgument(0), inv.getArgument(1)))
            .when(s).setAttribute(anyString(), any());
        when(s.getAttribute(anyString())).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        return s;
    }

    @Test
    void 未撤銷的帳號_session有效() {
        SessionRevocation r = new SessionRevocation();
        HttpSession s = fakeSession();
        r.markLogin(s);
        assertFalse(r.isRevoked(1L, s));
    }

    @Test
    void 撤銷前登入的session_失效() {
        SessionRevocation r = new SessionRevocation();
        HttpSession s = fakeSession();
        r.markLogin(s);
        r.revokeUser(1L);
        assertTrue(r.isRevoked(1L, s));
    }

    @Test
    void 撤銷後重新登入的session_有效() {
        SessionRevocation r = new SessionRevocation();
        HttpSession old = fakeSession();
        r.markLogin(old);
        r.revokeUser(1L);
        HttpSession fresh = fakeSession();
        r.markLogin(fresh);
        assertTrue(r.isRevoked(1L, old));
        assertFalse(r.isRevoked(1L, fresh));
    }

    @Test
    void 撤銷只影響該帳號() {
        SessionRevocation r = new SessionRevocation();
        HttpSession other = fakeSession();
        r.markLogin(other);
        r.revokeUser(1L);
        assertFalse(r.isRevoked(2L, other));
    }
}
