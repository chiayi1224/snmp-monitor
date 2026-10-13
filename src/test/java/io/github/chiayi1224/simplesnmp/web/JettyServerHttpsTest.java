package io.github.chiayi1224.simplesnmp.web;

import io.github.chiayi1224.simplesnmp.discovery.DiscoveryService;
import io.github.chiayi1224.simplesnmp.profile.ProfileCollector;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 啟用 HTTPS 時：HTTP 轉址到 HTTPS，且 HTTPS 登入的 Session cookie 帶 Secure／HttpOnly／SameSite */
class JettyServerHttpsTest {

    private JettyServer server;
    private MetricStore store;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (store != null) store.close();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void start(boolean https, int httpPort, int httpsPort, Path dir) throws Exception {
        store = new MetricStore(":memory:");
        store.addUser("tester", "Tester@12345", "admin");
        server = new JettyServer(httpPort, https, httpsPort, dir.resolve("test.p12"), "changeit",
            store, mock(TrapReceiver.class), mock(DiscoveryService.class), mock(ProfileCollector.class));
        server.start();
    }

    private static HttpClient trustAllClient() throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, null);
        return HttpClient.newBuilder().sslContext(ctx).build();   // 預設不跟隨轉址
    }

    private static HttpRequest login(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"tester\",\"password\":\"Tester@12345\"}"))
            .build();
    }

    @Test
    void 啟用HTTPS後HTTP請求以307轉址到HTTPS_且保留路徑與查詢(@TempDir Path dir) throws Exception {
        int http = freePort(), https = freePort();
        start(true, http, https, dir);

        HttpResponse<String> r = trustAllClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + http + "/api/info?x=1")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(307, r.statusCode());
        assertEquals("https://localhost:" + https + "/api/info?x=1", r.headers().firstValue("Location").orElse(""));
    }

    @Test
    void 啟用HTTPS後HTTP登入不會被處理_不會取得cookie(@TempDir Path dir) throws Exception {
        int http = freePort(), https = freePort();
        start(true, http, https, dir);

        HttpResponse<String> r = trustAllClient().send(login("http://localhost:" + http + "/api/auth/login"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(307, r.statusCode());
        assertTrue(r.headers().allValues("Set-Cookie").isEmpty());
    }

    @Test
    void HTTPS登入的session_cookie帶Secure_HttpOnly_SameSite(@TempDir Path dir) throws Exception {
        int http = freePort(), https = freePort();
        start(true, http, https, dir);

        HttpResponse<String> r = trustAllClient().send(login("https://localhost:" + https + "/api/auth/login"),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, r.statusCode(), r.body());
        String cookie = String.join("\n", r.headers().allValues("Set-Cookie"));
        assertTrue(cookie.contains("JSESSIONID"), cookie);
        assertTrue(cookie.contains("Secure"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
    }

    @Test
    void 憑證無法建立時退回HTTP_服務仍可使用(@TempDir Path dir) throws Exception {
        int http = freePort();
        Path blocker = dir.resolve("blocker");
        java.nio.file.Files.writeString(blocker, "x");   // 以檔案當目錄，使 keystore 無法建立
        store = new MetricStore(":memory:");
        server = new JettyServer(http, true, freePort(), blocker.resolve("test.p12"), "changeit",
            store, mock(TrapReceiver.class), mock(DiscoveryService.class), mock(ProfileCollector.class));
        server.start();

        HttpResponse<String> r = trustAllClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + http + "/api/info")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, r.statusCode());
    }

    @Test
    void 未啟用HTTPS時HTTP照常服務(@TempDir Path dir) throws Exception {
        int http = freePort();
        start(false, http, freePort(), dir);

        HttpResponse<String> r = trustAllClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + http + "/api/info")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, r.statusCode());
    }
}
