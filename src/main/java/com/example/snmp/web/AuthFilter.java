package com.example.snmp.web;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Set;

/**
 * 認證 Filter
 *
 * 所有請求都會經過這裡：
 *  - /login.html、/api/auth/login  → 不需要登入
 *  - 其他所有路徑                  → 需要有效 Session，否則 redirect 到 /login.html
 *
 * Session 屬性 "user" 存放登入的帳號資訊 Map。
 */
public class AuthFilter implements Filter {

    // 不需要認證的路徑
    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/login.html",
        "/change-password.html",
        "/api/auth/login"
    );

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();

        // 公開路徑直接放行
        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }

        // 有效 Session → 放行
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("user") != null) {
            chain.doFilter(req, res);
            return;
        }

        // 未登入：API 請求回 401，頁面請求 redirect 到登入頁
        if (path.startsWith("/api/")) {
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"未登入\"}");
        } else {
            response.sendRedirect("/login.html");
        }
    }

    @Override public void init(FilterConfig config) {}
    @Override public void destroy() {}
}
