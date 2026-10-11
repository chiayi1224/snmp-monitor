package io.github.chiayi1224.simplesnmp.i18n;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * 依 Accept-Language 設定本次請求的語系（{@link Messages#current()}），請求結束後清除。
 * 必須排在所有會產生使用者訊息的 Filter（例如 AuthFilter）之前。
 */
public class LocaleFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String header = req instanceof HttpServletRequest http ? http.getHeader("Accept-Language") : null;
        Messages.setCurrent(Messages.resolve(header));
        try {
            chain.doFilter(req, res);
        } finally {
            Messages.clearCurrent();
        }
    }
}
