package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * POST /api/v1/status — 取得所有設備即時狀態
 *
 * body 忽略（傳 {} 或不傳皆可）
 *
 * 供外部系統以 Bearer Token 或 Session 呼叫。
 */
public class V1StatusServlet extends BaseJsonServlet {

    private final MetricStore store;

    public V1StatusServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            writeJson(resp, store.queryLatestStatus());
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
