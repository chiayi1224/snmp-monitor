package com.example.snmp;

import com.example.snmp.config.TrapOidMapping;
import com.example.snmp.config.V3UserConfig;
import com.example.snmp.poller.SnmpPoller;
import com.example.snmp.storage.MetricStore;
import com.example.snmp.trap.TrapReceiver;
import com.example.snmp.web.JettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        MetricStore store = new MetricStore("snmp_monitor.db");

        // ── 2. SNMPv3 Trap user（仍在程式碼設定，通常不常變動）──
        List<V3UserConfig> v3Users = List.of(
            V3UserConfig.authPriv("trapUser", "myAuthPass", "myPrivPass")
        );

        // ── 3. Poller（設備從 DB 動態讀取）───────────────────────
        SnmpPoller poller = new SnmpPoller(store, 60);
        poller.start();

        // ── 4. Trap Receiver ──────────────────────────────────────
        TrapReceiver trapReceiver = new TrapReceiver(
            1162, v3Users, store,
            TrapOidMapping.defaults(),
            store.loadEnabledDevices()  // 啟動時載入一次用於建立 IP→名稱表
        );
        trapReceiver.start();

        // ── 5. Web Server（設備管理頁面在這）─────────────────────
        JettyServer jetty = new JettyServer(8080, store);
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
        log.info("管理頁面 → http://localhost:8080");
        Thread.currentThread().join();
    }
}
