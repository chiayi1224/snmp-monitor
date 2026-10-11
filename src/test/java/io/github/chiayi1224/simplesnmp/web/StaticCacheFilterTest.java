package io.github.chiayi1224.simplesnmp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class StaticCacheFilterTest {

    private void check(String path, String expected) throws Exception {
        HttpServletRequest  req   = mock(HttpServletRequest.class);
        HttpServletResponse resp  = mock(HttpServletResponse.class);
        FilterChain         chain = mock(FilterChain.class);
        when(req.getServletPath()).thenReturn(path);
        new StaticCacheFilter().doFilter(req, resp, chain);
        if (expected == null) verify(resp, never()).setHeader(eq("Cache-Control"), anyString());
        else verify(resp).setHeader("Cache-Control", expected);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void 首頁不快取_資源長期快取_API不設定() throws Exception {
        check("/", StaticCacheFilter.NO_CACHE);
        check("/index.html", StaticCacheFilter.NO_CACHE);
        check("/assets/index-AbC123.js", StaticCacheFilter.IMMUTABLE);
        check("/api/v1/status", null);
    }
}
