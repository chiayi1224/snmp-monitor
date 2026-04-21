package com.example.snmp.web;

import com.example.snmp.storage.MetricStore;
import com.example.snmp.web.servlet.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;

public class JettyServer {

    private static final Logger log = LoggerFactory.getLogger(JettyServer.class);

    private final int         port;
    private final MetricStore store;
    private Server            server;

    public JettyServer(int port, MetricStore store) {
        this.port  = port;
        this.store = store;
    }

    public void start() throws Exception {
        server = new Server(port);

        ServletContextHandler ctx = new ServletContextHandler(
            ServletContextHandler.SESSIONS);  // 啟用 Session 支援
        ctx.setContextPath("/");

        // ── 認證 Filter（所有路徑）────────────────────────────────
        ctx.addFilter(
            new FilterHolder(new AuthFilter()),
            "/*",
            EnumSet.of(DispatcherType.REQUEST)
        );

        // ── API Servlet ────────────────────────────────────────────
        ctx.addServlet(new ServletHolder(new AuthServlet(store)),   "/api/auth/*");
        ctx.addServlet(new ServletHolder(new StatusServlet(store)),  "/api/status");
        ctx.addServlet(new ServletHolder(new DeviceServlet(store)),  "/api/devices/*");
        ctx.addServlet(new ServletHolder(new AlertServlet(store)),   "/api/alerts");
        ctx.addServlet(new ServletHolder(new UserServlet(store)),    "/api/users/*");

        // ── 靜態檔案 ──────────────────────────────────────────────
        String staticBase = JettyServer.class.getResource("/static").toExternalForm();
        ServletHolder staticHolder = new ServletHolder("static", DefaultServlet.class);
        staticHolder.setInitParameter("resourceBase", staticBase);
        staticHolder.setInitParameter("dirAllowed", "false");
        ctx.addServlet(staticHolder, "/");

        server.setHandler(ctx);
        server.start();
        log.info("Web Server 啟動 → http://localhost:{}", port);
    }

    public void stop() throws Exception {
        if (server != null) server.stop();
    }
}
