package io.github.chiayi1224.simplesnmp.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用者可見的文字必須經由訊息代碼（i18n/messages*.properties），程式中不可直接寫中文字串。
 * 註解、log（一律英文，由程式碼審查把關）不在檢查範圍；資料識別值與升級轉換的舊資料列入例外。
 */
class NoHardcodedUserTextTest {

    private static final Path SRC = Paths.get("src/main/java");

    private static final Pattern CHINESE_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*[\\u4e00-\\u9fff](?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\(");

    /** 例外：資料識別值（內建範本名稱）與舊版升級轉換寫入的資料 */
    private static final Set<String> ALLOWED = Set.of(
        "profile/BuiltinProfiles.java:public static final String HOST_RESOURCES",
        "storage/PollOidMigration.java:static final String PROFILE_NAME",
        "storage/PollOidMigration.java:\"由舊版 Poll OID 設定轉換");

    @Test
    void 程式中沒有寫死的中文使用者文字() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                found.addAll(scan(f));
            }
        }
        assertTrue(found.isEmpty(), "以下字串應改用訊息代碼：\n" + String.join("\n", found));
    }

    static List<String> scan(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        String rel = (file.startsWith(SRC) ? SRC.relativize(file) : file.getFileName()).toString()
            .replace('\\', '/').replaceFirst("^io/github/chiayi1224/simplesnmp/", "");
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String code = stripComment(line).trim();
            if (code.isEmpty() || code.startsWith("*") || code.startsWith("/*")) continue;
            if (LOG_CALL.matcher(code).find()) continue;
            if (!CHINESE_LITERAL.matcher(code).find()) continue;
            if (ALLOWED.stream().anyMatch(a -> a.startsWith(rel + ":") && code.contains(a.substring(rel.length() + 1)))) continue;
            out.add(rel + ":" + (i + 1) + "  " + code);
        }
        return out;
    }

    /** 去掉行尾的 // 註解（不在字串內者） */
    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString && c == '/' && line.charAt(i + 1) == '/') return line.substring(0, i);
        }
        return line;
    }

    @Test
    void 檢查程式能找出中文字串() throws IOException {
        Path tmp = Files.createTempFile("sample", ".java");
        try {
            Files.writeString(tmp, "class A {\n  // 註解不算\n  String s = \"設備\";\n  void f() { log.info(\"中文 log\"); }\n}\n");
            List<String> found = scan(tmp);
            assertEquals(1, found.size(), found.toString());
            assertTrue(found.get(0).contains("設備"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
