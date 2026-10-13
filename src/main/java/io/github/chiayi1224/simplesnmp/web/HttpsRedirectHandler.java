package io.github.chiayi1224.simplesnmp.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;

/**
 * 啟用 HTTPS 時，把明文 HTTP 請求轉址到 HTTPS，避免 Session cookie 與 Bearer Token 長期走明文。
 *
 * 使用 307（暫時轉址、保留請求方法）：瀏覽器不會永久快取，日後關閉 HTTPS 也不會被舊的轉址卡住，
 * POST 等寫入請求也不會被降級成 GET。
 */
public class HttpsRedirectHandler extends AbstractHandler {

    private final int httpsPort;

    public HttpsRedirectHandler(int httpsPort) {
        this.httpsPort = httpsPort;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        if (request.isSecure()) return;

        String host = request.getServerName();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        String query = request.getQueryString();
        String location = "https://" + host + ":" + httpsPort + request.getRequestURI()
            + (query == null ? "" : "?" + query);

        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        response.setHeader("Location", location);
        baseRequest.setHandled(true);
    }
}
