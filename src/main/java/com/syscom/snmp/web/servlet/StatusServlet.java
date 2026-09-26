package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * GET /api/status
 *
 * 回傳每台設備最新的 CPU / Memory / Disk 狀態
 *
 * Response 範例：
 * [
 *   {
 *     "deviceName": "Server-01",
 *     "cpu":    { "usedPct": 42.3, "ts": "2026-03-28T10:00:00Z" },
 *     "memory": { "usedPct": 68.1, "totalKb": 16384000, "usedKb": 11157504, "ts": "..." },
 *     "disks": [
 *       { "mountPoint": "/",     "usedPct": 55.2, "totalBytes": 107374182400, "usedBytes": 59263655936 },
 *       { "mountPoint": "/data", "usedPct": 80.1, "totalBytes": 214748364800, "usedBytes": 172025479782 }
 *     ]
 *   }
 * ]
 */
public class StatusServlet extends BaseJsonServlet {

    private final MetricStore store;

    public StatusServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryLatestStatus());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
