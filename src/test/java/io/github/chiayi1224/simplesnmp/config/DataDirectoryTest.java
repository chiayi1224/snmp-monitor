package io.github.chiayi1224.simplesnmp.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataDirectoryTest {

    private static final Path JAR_DIR = Paths.get("/opt/snmp-monitor").toAbsolutePath();

    @Test
    void 未設定時為JAR目錄() {
        assertEquals(JAR_DIR, DataDirectory.resolve(null, null, JAR_DIR));
        assertEquals(JAR_DIR, DataDirectory.resolve("  ", "", JAR_DIR));
    }

    @Test
    void 系統屬性優先於環境變數() {
        assertEquals(Paths.get("/var/lib/a").toAbsolutePath(), DataDirectory.resolve("/var/lib/a", "/var/lib/b", JAR_DIR));
        assertEquals(Paths.get("/var/lib/b").toAbsolutePath(), DataDirectory.resolve(null, "/var/lib/b", JAR_DIR));
    }

    @Test
    void 相對路徑轉為絕對路徑並正規化() {
        Path expected = Paths.get("data").toAbsolutePath().normalize();
        assertEquals(expected, DataDirectory.resolve("./x/../data ", null, JAR_DIR));
    }
}
