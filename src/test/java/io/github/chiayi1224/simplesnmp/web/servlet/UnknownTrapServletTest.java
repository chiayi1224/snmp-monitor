package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnknownTrapServletTest {

    @Mock MetricStore         store;
    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;

    UnknownTrapServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new UnknownTrapServlet(store);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @Test
    void 刪除未定義Trap() throws Exception {
        when(req.getPathInfo()).thenReturn("/oids/3");
        when(store.deleteUnknownTrap(3)).thenReturn(true);

        servlet.doDelete(req, resp);

        verify(store).deleteUnknownTrap(3);
        verify(resp, never()).setStatus(anyInt());
    }

    @Test
    void 刪除未登錄來源() throws Exception {
        when(req.getPathInfo()).thenReturn("/sources/4");
        when(store.deleteUnknownSource(4)).thenReturn(true);

        servlet.doDelete(req, resp);

        verify(store).deleteUnknownSource(4);
    }

    @Test
    void 記錄不存在回傳404() throws Exception {
        when(req.getPathInfo()).thenReturn("/oids/99");

        servlet.doDelete(req, resp);

        verify(resp).setStatus(404);
    }

    @Test
    void 未知路徑回傳404() throws Exception {
        when(req.getPathInfo()).thenReturn("/other/1");

        servlet.doDelete(req, resp);

        verify(resp).setStatus(404);
        verify(store, never()).deleteUnknownTrap(anyLong());
        verify(store, never()).deleteUnknownSource(anyLong());
    }
}
