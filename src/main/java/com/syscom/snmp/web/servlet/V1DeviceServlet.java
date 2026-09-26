package com.syscom.snmp.web.servlet;

import com.syscom.snmp.storage.MetricStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 設備查詢 API（唯讀）
 *
 *   POST /api/v1/devices          — 取得所有設備清單  body: {} 或不傳
 *   POST /api/v1/devices/{name}   — 取得單台設備      body: {} 或不傳
 *
 * 供外部系統以 Bearer Token 或 Session 呼叫。
 */
public class V1DeviceServlet extends BaseJsonServlet {

    private final MetricStore store;

    public V1DeviceServlet(MetricStore store) {
        this.store = store;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                writeJson(resp, store.queryAllDevices());
            } else {
                String name = pathInfo.substring(1);
                Map<String, Object> device = store.queryDeviceByName(name);
                if (device == null) {
                    writeError(resp, 404, "找不到設備：" + name);
                } else {
                    writeJson(resp, device);
                }
            }
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }
}
