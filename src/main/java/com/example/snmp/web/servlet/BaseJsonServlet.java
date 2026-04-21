package com.example.snmp.web.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 所有 API Servlet 的基底類別
 * 提供 JSON 輸出的共用方法
 */
public abstract class BaseJsonServlet extends HttpServlet {

    protected static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 把物件序列化成 JSON 寫入 response
     */
    protected void writeJson(HttpServletResponse resp, Object data) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*"); // 開發時方便前端呼叫
        JSON.writeValue(resp.getWriter(), data);
    }

    /**
     * 回傳錯誤 JSON
     */
    protected void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        writeJson(resp, java.util.Map.of("error", message));
    }
}
