package com.syscom.snmp;

import java.io.InputStream;
import java.util.jar.Manifest;

/**
 * 從 JAR MANIFEST.MF 讀取版本資訊
 *
 * 打包後可讀到完整資訊；直接從 IDE 執行時 fallback 為 "dev"。
 */
public final class AppVersion {

    private static final String VERSION;
    private static final String BUILD_TIME;
    private static final String TITLE;

    static {
        String ver = "dev", time = "-", title = "SNMP Monitor";
        try (InputStream is = AppVersion.class
                .getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (is != null) {
                Manifest mf = new Manifest(is);
                var attrs = mf.getMainAttributes();
                String v = attrs.getValue("Implementation-Version");
                String t = attrs.getValue("Build-Time");
                String n = attrs.getValue("Implementation-Title");
                if (v != null && !v.isBlank()) ver  = v;
                if (t != null && !t.isBlank()) time = t;
                if (n != null && !n.isBlank()) title = n;
            }
        } catch (Exception ignored) {}
        VERSION    = ver;
        BUILD_TIME = time;
        TITLE      = title;
    }

    private AppVersion() {}

    public static String version()   { return VERSION;    }
    public static String buildTime() { return BUILD_TIME; }
    public static String title()     { return TITLE;      }
}
