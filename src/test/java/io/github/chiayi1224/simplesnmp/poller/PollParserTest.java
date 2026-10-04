package io.github.chiayi1224.simplesnmp.poller;

import io.github.chiayi1224.simplesnmp.config.OidConstants;
import org.junit.jupiter.api.Test;

import io.github.chiayi1224.simplesnmp.model.DiskInfo;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PollParserTest {

    @Test
    void CPU_使用率為100減idle() {
        assertEquals(25.0, PollParser.cpuUsedPct(Map.of(OidConstants.CPU_IDLE, "75")), 0.001);
    }

    @Test
    void CPU_idle未回傳時為null() {
        assertNull(PollParser.cpuUsedPct(Map.of()));
    }

    @Test
    void CPU_idle非數字時為null不拋例外() {
        assertNull(PollParser.cpuUsedPct(Map.of(OidConstants.CPU_IDLE, "noSuchObject")));
    }

    @Test
    void Memory_扣除free_buffer_cache後計算使用率() {
        PollParser.Memory mem = PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL,  "1000",
            OidConstants.MEM_FREE,   "200",
            OidConstants.MEM_BUFFER, "100",
            OidConstants.MEM_CACHE,  "200"));

        assertNotNull(mem);
        assertEquals(1000, mem.totalKb());
        assertEquals(500,  mem.usedKb());
        assertEquals(50.0, mem.usedPct(), 0.001);
    }

    @Test
    void Memory_total為0時使用率為0() {
        PollParser.Memory mem = PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL,  "0",
            OidConstants.MEM_FREE,   "0",
            OidConstants.MEM_BUFFER, "0",
            OidConstants.MEM_CACHE,  "0"));

        assertNotNull(mem);
        assertEquals(0.0, mem.usedPct(), 0.001);
    }

    @Test
    void Memory_缺任一OID時為null() {
        assertNull(PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL, "1000",
            OidConstants.MEM_FREE,  "200",
            OidConstants.MEM_CACHE, "200")));
    }

    @Test
    void Memory_任一值非數字時為null不拋例外() {
        assertNull(PollParser.memory(Map.of(
            OidConstants.MEM_TOTAL,  "1000",
            OidConstants.MEM_FREE,   "noSuchInstance",
            OidConstants.MEM_BUFFER, "100",
            OidConstants.MEM_CACHE,  "200")));
    }

    // ─── 磁碟（hrStorage）─────────────────────────────────────────

    private static VariableBinding vb(String col, int idx, org.snmp4j.smi.Variable v) {
        return new VariableBinding(new OID(col + "." + idx), v);
    }

    @Test
    void Disk_只保留固定磁碟並以分配單位換算位元組() {
        OID fixed = new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK);
        List<VariableBinding> rows = List.of(
            // 欄位順序打散，確認依 index 組合
            vb(OidConstants.HR_STORAGE_USED,  31, new Integer32(250)),
            vb(OidConstants.HR_STORAGE_TYPE,  1,  new OID("1.3.6.1.2.1.25.2.1.2")),   // RAM
            vb(OidConstants.HR_STORAGE_TYPE,  31, fixed),
            vb(OidConstants.HR_STORAGE_DESCR, 31, new OctetString("/data")),
            vb(OidConstants.HR_STORAGE_UNIT,  31, new Integer32(4096)),
            vb(OidConstants.HR_STORAGE_SIZE,  31, new Integer32(1000)),
            vb(OidConstants.HR_STORAGE_DESCR, 1,  new OctetString("Physical memory")));

        List<DiskInfo> disks = PollParser.disks(rows);

        assertEquals(1, disks.size());
        assertEquals("/data", disks.get(0).getMountPoint());
        assertEquals(4096L * 1000, disks.get(0).getTotalBytes());
        assertEquals(4096L * 250,  disks.get(0).getUsedBytes());
        assertEquals(25.0, disks.get(0).getUsedPercent(), 0.001);
    }

    @Test
    void Disk_缺少欄位時使用預設值() {
        List<VariableBinding> rows = List.of(
            vb(OidConstants.HR_STORAGE_TYPE, 5, new OID(OidConstants.HR_STORAGE_TYPE_FIXED_DISK)),
            vb(OidConstants.HR_STORAGE_SIZE, 5, new Integer32(10)));

        DiskInfo d = PollParser.disks(rows).get(0);
        assertEquals("unknown", d.getMountPoint());
        assertEquals(10, d.getTotalBytes());     // allocationUnits 預設 1
        assertEquals(0,  d.getUsedBytes());
    }
}
