package com.syscom.snmp.web.servlet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 所有 API Servlet 的基底類別
 * 提供 JSON 輸出、Body 解析與欄位驗證的共用方法。
 */
public abstract class BaseJsonServlet extends HttpServlet {

    protected static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** 把物件序列化成 JSON 寫入 response */
    protected void writeJson(HttpServletResponse resp, Object data) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        JSON.writeValue(resp.getWriter(), data);
    }

    /** 回傳錯誤 JSON */
    protected void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        writeJson(resp, java.util.Map.of("error", message));
    }

    /**
     * 解析 request body 為 JsonNode。
     * JSON 格式錯誤時自動回傳 400 並回傳 null（呼叫方收到 null 應立即 return）。
     */
    protected JsonNode parseBody(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            return JSON.readTree(req.getReader());
        } catch (JsonProcessingException e) {
            writeError(resp, 400, "JSON 格式錯誤：" + e.getOriginalMessage());
            return null;
        }
    }

    /**
     * 取出必填文字欄位（使用 path() 確保不 NPE）。
     * 欄位不存在、為 null 或空白時，寫入 400 並回傳 null（呼叫方應立即 return）。
     */
    protected String requireText(JsonNode body, String field, HttpServletResponse resp) throws IOException {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            writeError(resp, 400, "欄位 " + field + " 為必填");
            return null;
        }
        return node.asText().trim();
    }

    /** 取出選填文字欄位，缺失或空白時回傳 def */
    protected String optText(JsonNode body, String field, String def) {
        JsonNode node = body.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) return def;
        return node.asText().trim();
    }

    /** 取出選填整數欄位，非數字或缺失時回傳 def */
    protected int optInt(JsonNode body, String field, int def) {
        JsonNode node = body.path(field);
        return node.isNumber() ? node.asInt() : def;
    }

    /** 取出選填長整數欄位，非數字或缺失時回傳 def */
    protected long optLong(JsonNode body, String field, long def) {
        JsonNode node = body.path(field);
        return node.isNumber() ? node.asLong() : def;
    }

    /** 取出選填布林欄位，缺失時回傳 def */
    protected boolean optBool(JsonNode body, String field, boolean def) {
        return body.path(field).asBoolean(def);
    }

    /** 取出 pathInfo 去掉開頭 "/" 後的字串（如 "/Router-01" → "Router-01"）；無路徑時回傳 null */
    protected String pathName(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.equals("/")) return null;
        return info.substring(1);
    }

    /** 從 pathInfo（如 "/123"）解析數字 ID；無效時回傳 -1 */
    protected long pathId(HttpServletRequest req) {
        return parseId(pathName(req));
    }

    /** 解析數字 ID；null 或非數字時回傳 -1 */
    protected static long parseId(String s) {
        if (s == null) return -1;
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return -1; }
    }
}
