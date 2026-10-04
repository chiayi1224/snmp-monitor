package io.github.chiayi1224.simplesnmp.web;

import io.github.chiayi1224.simplesnmp.discovery.DiscoveryService;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import io.github.chiayi1224.simplesnmp.web.servlet.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.DispatcherType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

public class JettyServer {

    private static final Logger log = LoggerFactory.getLogger(JettyServer.class);

    private final int          httpPort;
    private final boolean      httpsEnabled;
    private final int          httpsPort;
    private final Path         keystorePath;
    private final String       keystorePassword;
    private final MetricStore  store;
    private final TrapReceiver trapReceiver;
    private final DiscoveryService discovery;
    private Server             server;

    public JettyServer(int httpPort, boolean httpsEnabled, int httpsPort,
                       Path keystorePath, String keystorePassword,
                       MetricStore store, TrapReceiver trapReceiver, DiscoveryService discovery) {
        this.httpPort         = httpPort;
        this.httpsEnabled     = httpsEnabled;
        this.httpsPort        = httpsPort;
        this.keystorePath     = keystorePath;
        this.keystorePassword = keystorePassword;
        this.store            = store;
        this.trapReceiver     = trapReceiver;
        this.discovery        = discovery;
    }

    public void start() throws Exception {
        server = new Server();

        // ── HTTP connector ─────────────────────────────────────────
        ServerConnector httpConnector = new ServerConnector(server);
        httpConnector.setPort(httpPort);
        server.addConnector(httpConnector);

        // ── HTTPS connector（選用）────────────────────────────────
        if (httpsEnabled) {
            ensureKeystore();

            SslContextFactory.Server sslCtx = new SslContextFactory.Server();
            sslCtx.setKeyStorePath(keystorePath.toString());
            sslCtx.setKeyStorePassword(keystorePassword);
            sslCtx.setKeyManagerPassword(keystorePassword);

            HttpConfiguration httpsConfig = new HttpConfiguration();
            httpsConfig.addCustomizer(new SecureRequestCustomizer());

            ServerConnector httpsConnector = new ServerConnector(server,
                new SslConnectionFactory(sslCtx, "http/1.1"),
                new HttpConnectionFactory(httpsConfig));
            httpsConnector.setPort(httpsPort);
            server.addConnector(httpsConnector);
            log.info("HTTPS 已啟用 → https://localhost:{}", httpsPort);
        }

        // ── Servlet context ────────────────────────────────────────
        ServletContextHandler ctx = new ServletContextHandler(
            ServletContextHandler.SESSIONS);
        ctx.setContextPath("/");

        // ── 認證 Filter ────────────────────────────────────────────
        ctx.addFilter(
            new FilterHolder(new AuthFilter(store)),
            "/*",
            EnumSet.of(DispatcherType.REQUEST)
        );

        // ── 公開 API ──────────────────────────────────────────────
        ctx.addServlet(new ServletHolder(new InfoServlet()),                   "/api/info");
        ctx.addServlet(new ServletHolder(new AuthServlet(store)),              "/api/auth/*");

        // ── v1 外部 API（POST only，Session 或 Bearer Token）─────
        ctx.addServlet(new ServletHolder(new V1StatusServlet(store)),          "/api/v1/status");
        ctx.addServlet(new ServletHolder(new V1AlertServlet(store)),           "/api/v1/alerts");
        ctx.addServlet(new ServletHolder(new V1DeviceServlet(store)),          "/api/v1/devices/*");

        // ── Admin API（Session 限定）──────────────────────────────
        ctx.addServlet(new ServletHolder(new DeviceServlet(store, trapReceiver)),       "/api/admin/devices/*");
        ctx.addServlet(new ServletHolder(new UserServlet(store)),                       "/api/admin/users/*");
        ctx.addServlet(new ServletHolder(new TrapMappingServlet(store, trapReceiver)),  "/api/admin/trap-mappings/*");
        ctx.addServlet(new ServletHolder(new UnknownTrapServlet(store)),                "/api/admin/unknown-traps");
        ctx.addServlet(new ServletHolder(new PollOidServlet(store)),                    "/api/admin/poll-oids/*");
        ctx.addServlet(new ServletHolder(new SettingsServlet(store)),                   "/api/admin/settings/*");
        ctx.addServlet(new ServletHolder(new ThresholdServlet(store)),                  "/api/admin/thresholds/*");
        ctx.addServlet(new ServletHolder(new V3UserServlet(store, trapReceiver)),       "/api/admin/v3-users/*");
        ctx.addServlet(new ServletHolder(new ApiTokenServlet(store)),                   "/api/admin/tokens/*");
        ctx.addServlet(new ServletHolder(new AuditLogServlet(store)),                   "/api/admin/audit-logs");
        ctx.addServlet(new ServletHolder(new DiscoveryServlet(store, discovery)),       "/api/admin/discovery/*");

        // ── 靜態檔案 ──────────────────────────────────────────────
        String staticBase = JettyServer.class.getResource("/static").toExternalForm();
        ServletHolder staticHolder = new ServletHolder("static", DefaultServlet.class);
        staticHolder.setInitParameter("resourceBase", staticBase);
        staticHolder.setInitParameter("dirAllowed", "false");
        ctx.addServlet(staticHolder, "/");

        server.setHandler(ctx);
        server.start();
        log.info("Web Server 啟動 → http://localhost:{}", httpPort);
    }

    public void stop() throws Exception {
        if (server != null) server.stop();
    }

    /**
     * 若 KeyStore 不存在，呼叫 keytool 自動建立自簽憑證。
     * 憑證有效期 10 年，RSA 2048 位元。
     */
    private void ensureKeystore() throws Exception {
        if (Files.exists(keystorePath)) {
            log.info("使用既有 KeyStore：{}", keystorePath);
            return;
        }

        log.info("KeyStore 不存在，正在建立自簽憑證：{}", keystorePath);
        Files.createDirectories(keystorePath.getParent());

        ProcessBuilder pb = new ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias",     "snmp-monitor",
            "-keyalg",    "RSA",
            "-keysize",   "2048",
            "-validity",  "3650",
            "-keystore",  keystorePath.toString(),
            "-storepass", keystorePassword,
            "-keypass",   keystorePassword,
            "-dname",     "CN=localhost, OU=SNMP Monitor, O=Syscom, L=Taipei, ST=Taiwan, C=TW",
            "-storetype", "PKCS12",
            "-noprompt"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int rc = proc.waitFor();
        if (rc != 0) {
            throw new Exception("keytool 建立 KeyStore 失敗 (rc=" + rc + "):\n" + output);
        }
        log.info("自簽憑證建立完成：{}", keystorePath);
    }
}
