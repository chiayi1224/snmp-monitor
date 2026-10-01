package io.github.chiayi1224.simplesnmp.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Trap OID 對應設定
 *
 * 每一筆代表一組 TRIGGERED/RESOLVED 的對應關係。
 * OID 確認後直接修改常數即可，程式其他地方不用動。
 *
 * snmpTrapOID.0 的 OID = 1.3.6.1.6.3.1.1.4.1.0
 * trap 收到後從這個 VarBind 取出 trapOID，再來這裡查對應。
 */
public class TrapOidMapping {

    // ═══════════════════════════════════════════════════════════════
    //  ★ 待確認的 OID — 確認後直接改這裡
    // ═══════════════════════════════════════════════════════════════

    // CPU 超過閾值
    private static final String CPU_TRIGGERED = "1.3.6.1.4.1.TODO.cpu.triggered";
    private static final String CPU_RESOLVED  = "1.3.6.1.4.1.TODO.cpu.resolved";   // null = 無 resolved trap

    // Memory 超過閾值
    private static final String MEM_TRIGGERED = "1.3.6.1.4.1.TODO.mem.triggered";
    private static final String MEM_RESOLVED  = "1.3.6.1.4.1.TODO.mem.resolved";

    // Disk 超過閾值
    private static final String DISK_TRIGGERED = "1.3.6.1.4.1.TODO.disk.triggered";
    private static final String DISK_RESOLVED  = "1.3.6.1.4.1.TODO.disk.resolved";

    // ═══════════════════════════════════════════════════════════════

    /**
     * 單一 OID 對應規則
     *
     * @param triggeredOid  代表「告警發生」的 trap OID
     * @param resolvedOid   代表「告警恢復」的 trap OID（null = 該指標沒有 resolved trap）
     * @param metricName    對應的指標名稱（對應 alert_events.metric_name）
     * @param description   說明文字，例如 "CPU 使用率過高"
     */
    public record Mapping(String triggeredOid, String resolvedOid, String metricName, String description) {
        public boolean hasResolved() { return resolvedOid != null && !resolvedOid.isBlank(); }
    }

    /**
     * 預設 OID 對應清單（首次啟動時寫入 DB 作為種子資料）
     */
    public static List<Mapping> defaults() {
        List<Mapping> list = new ArrayList<>();
        list.add(new Mapping(CPU_TRIGGERED,  CPU_RESOLVED,  "cpu",    "CPU 使用率過高"));
        list.add(new Mapping(MEM_TRIGGERED,  MEM_RESOLVED,  "memory", "記憶體使用率過高"));
        list.add(new Mapping(DISK_TRIGGERED, DISK_RESOLVED, "disk",   "磁碟使用率過高"));
        return list;
    }
}
