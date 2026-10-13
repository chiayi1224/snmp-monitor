package io.github.chiayi1224.simplesnmp.web;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.Locale;

/**
 * 跨站請求偽造（CSRF）防護：只針對以 Session cookie 驗證、會改變狀態的請求。
 *
 * 兩道檢查，皆不需要前端額外帶 token：
 *   1. Origin 必須與 Host 同源：瀏覽器在跨站 POST 一定會帶 Origin，值為攻擊者的網站。
 *      沒有 Origin（非瀏覽器客戶端，例如 curl）不檢查，因其無法被他站借用使用者的 cookie。
 *   2. POST / PUT / PATCH 的 Content-Type 必須是 application/json：跨站表單只能送
 *      form-urlencoded、multipart 或 text/plain，要送 JSON 就需要 CORS preflight，
 *      而本系統不會回應 preflight。DELETE 等非「簡單請求」方法同樣需要 preflight，不另外檢查。
 */
final class CsrfGuard {

    enum Result { OK, CROSS_ORIGIN, BAD_CONTENT_TYPE }

    private CsrfGuard() {}

    static Result check(HttpServletRequest request) {
        String method = request.getMethod();
        if (isSafe(method)) return Result.OK;

        String origin = request.getHeader("Origin");
        if (origin != null && !sameOrigin(origin, request.getHeader("Host"))) {
            return Result.CROSS_ORIGIN;
        }

        if (hasBodyMethod(method)) {
            String type = request.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                return Result.BAD_CONTENT_TYPE;
            }
        }
        return Result.OK;
    }

    private static boolean isSafe(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
            || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method);
    }

    private static boolean hasBodyMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
            || "PATCH".equalsIgnoreCase(method);
    }

    /** Origin 的 host[:port] 必須等於 Host 標頭；"null" 或無法解析一律視為跨站 */
    private static boolean sameOrigin(String origin, String host) {
        if (host == null || host.isBlank()) return false;
        try {
            String authority = URI.create(origin).getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(host.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
