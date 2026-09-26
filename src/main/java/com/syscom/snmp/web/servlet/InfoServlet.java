package com.syscom.snmp.web.servlet;

import com.syscom.snmp.AppVersion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/info
 *
 * 回傳應用程式版本資訊（不需要登入）
 *
 * Response：
 * {
 *   "title":     "SNMP Monitor",
 *   "version":   "1.0.0",
 *   "buildTime": "2026-03-28 12:00:00"
 * }
 */
public class InfoServlet extends BaseJsonServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("title",     AppVersion.title());
        info.put("version",   AppVersion.version());
        info.put("buildTime", AppVersion.buildTime());
        writeJson(resp, info);
    }
}
