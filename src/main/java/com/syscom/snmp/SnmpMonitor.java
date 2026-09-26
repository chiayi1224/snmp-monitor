package com.syscom.snmp;

import com.syscom.snmp.config.TrapOidMapping;
import com.syscom.snmp.config.V3UserConfig;
import com.syscom.snmp.poller.SnmpPoller;
import com.syscom.snmp.storage.MetricStore;
import com.syscom.snmp.trap.TrapReceiver;
import com.syscom.snmp.web.JettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 主程式入口
 *
 * 設備清單改由 Web 介面維護（http://localhost:8080），
 * 修改後下一個 poll 週期（60秒）自動生效，不需要重啟。
 *
 * 第一次啟動時設備清單是空的，請先到管理頁面新增設備。
 */
public class SnmpMonitor {

    private static final Logger log = LoggerFactory.getLogger(SnmpMonitor.class);

    public static void main(String[] args) throws Exception {

        // ── 1. 儲存（設備清單也在這裡）────────────────────────────
        // DB 放在 JAR 同一目錄，不受 JVM 工作目錄影響
        Path jarDir = Paths.get(
            SnmpMonitor.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()
        ).getParent();
        String dbPath = jarDir.resolve("snmp_monitor.db").toString();
        MetricStore store = new MetricStore(dbPath);

        // ── 2. 從 DB 讀取設定 ─────────────────────────────────────
        int     webPort        = store.getSettingInt ("web_port",              8080);
        int     trapPort       = store.getSettingInt ("trap_port",             1162);
        int     pollInterval   = store.getSettingInt ("poll_interval_seconds", 60);
        boolean httpsEnabled   = store.getSettingBool("https_enabled",         false);
        int     httpsPort      = store.getSettingInt ("https_port",            8443);
        String  keystoreFile   = store.getSetting    ("keystore_path",         "keystore.jks");
        String  keystorePass   = store.getSetting    ("keystore_password",     "changeit");
        Path    keystorePath   = jarDir.resolve(keystoreFile);
        List<V3UserConfig> v3Users = store.loadEnabledV3Users();

        // ── 3. Poller（設備從 DB 動態讀取）───────────────────────
        SnmpPoller poller = new SnmpPoller(store, pollInterval);
        poller.start();

        // ── 4. Trap Receiver（OID 對應從 DB 載入，支援 Web 維護）──
        TrapReceiver trapReceiver = new TrapReceiver(
            trapPort, v3Users, store,
            store.loadTrapMappings(),   // 從 DB 載入（首次啟動已 seed 預設值）
            store.loadEnabledDevices()
        );
        trapReceiver.start();

        // ── 5. Web Server（設備管理頁面在這）─────────────────────
        JettyServer jetty = new JettyServer(
            webPort, httpsEnabled, httpsPort, keystorePath, keystorePass,
            store, trapReceiver);
        jetty.start();

        // ── 6. 優雅關閉 ───────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("關閉中...");
            poller.stop();
            trapReceiver.stop();
            try { jetty.stop();  } catch (Exception ignored) {}
            try { store.close(); } catch (Exception ignored) {}
        }));

        log.info("SNMP Monitor 啟動完成");
        log.info("管理頁面 → http://localhost:{}", webPort);
        if (httpsEnabled) log.info("HTTPS 管理頁面 → https://localhost:{}", httpsPort);
        Thread.currentThread().join();
    }
}
