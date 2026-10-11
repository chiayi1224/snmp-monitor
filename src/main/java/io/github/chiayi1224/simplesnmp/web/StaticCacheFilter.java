package io.github.chiayi1224.simplesnmp.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 前端靜態檔的快取標頭：
 *   首頁（/、/index.html）每次向伺服器確認，升級後重新整理即取得新版；
 *   /assets/*（建置產生、檔名含內容雜湊）可長期快取，內容變更時檔名也會改變。
 */
public class StaticCacheFilter implements Filter {

    static final String NO_CACHE  = "no-cache";
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (req instanceof HttpServletRequest request && res instanceof HttpServletResponse response) {
            String path = (request.getServletPath() == null ? "" : request.getServletPath())
                        + (request.getPathInfo() == null ? "" : request.getPathInfo());
            if (path.isEmpty() || path.equals("/") || path.equals("/index.html")) {
                response.setHeader("Cache-Control", NO_CACHE);
            } else if (path.startsWith("/assets/")) {
                response.setHeader("Cache-Control", IMMUTABLE);
            }
        }
        chain.doFilter(req, res);
    }
}
