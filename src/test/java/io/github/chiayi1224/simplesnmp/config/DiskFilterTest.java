package io.github.chiayi1224.simplesnmp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiskFilterTest {

    @Test
    void 空白或null代表全部() {
        assertTrue(DiskFilter.parse(null).isAll());
        assertTrue(DiskFilter.parse("").isAll());
        assertTrue(DiskFilter.parse(" , \n ,").isAll());
        assertTrue(DiskFilter.parse("").matches("/anything"));
    }

    @Test
    void 完整名稱比對_不是子字串() {
        DiskFilter f = DiskFilter.parse("/");
        assertTrue(f.matches("/"));
        assertFalse(f.matches("/boot/efi"));
        assertFalse(f.matches("/run"));
    }

    @Test
    void 萬用字元() {
        DiskFilter f = DiskFilter.parse("/mnt/*");
        assertTrue(f.matches("/mnt/data"));
        assertTrue(f.matches("/mnt/"));
        assertFalse(f.matches("/mnt"));
        assertTrue(DiskFilter.parse("*data*").matches("/var/data/x"));
    }

    @Test
    void 特殊字元當一般字元處理() {
        DiskFilter f = DiskFilter.parse("C:\\");
        assertTrue(f.matches("C:\\"));
        assertFalse(f.matches("C:"));
        assertFalse(DiskFilter.parse("/a.b").matches("/axb"));
    }

    @Test
    void 不分大小寫() {
        assertTrue(DiskFilter.parse("c:\\").matches("C:\\"));
    }

    @Test
    void 多個條件以逗號或換行分隔() {
        DiskFilter f = DiskFilter.parse("/, /data\n/mnt/*");
        assertTrue(f.matches("/"));
        assertTrue(f.matches("/data"));
        assertTrue(f.matches("/mnt/x"));
        assertFalse(f.matches("/run"));
    }

    @Test
    void 整理成儲存字串_去空白與重複() {
        assertEquals("/,/data", DiskFilter.normalize(" / , /data,, / "));
        assertEquals("", DiskFilter.normalize(null));
    }
}
