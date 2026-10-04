package io.github.chiayi1224.simplesnmp.profile;

import io.github.chiayi1224.simplesnmp.config.OidConstants;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 計算類型測試。CPU / Memory / Disk 部分移植自原本的 PollParserTest（Net-SNMP 回歸基準），案例全數保留。
 */
class MetricKindTest {

    private static final String FIXED_DISK = OidConstants.HR_STORAGE_TYPE_FIXED_DISK;
    private static final String RAM        = "1.3.6.1.2.1.25.2.1.2";

    private static SnmpResult scalars(Object... kv) {
        Map<String, Variable> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof Integer n ? new Integer32(n) : v instanceof String s ? new OctetString(s) : (Variable) v);
        }
        return new SnmpResult(m, Map.of());
    }

    private static SnmpResult walk(String root, List<VariableBinding> rows) {
        return new SnmpResult(Map.of(), Map.of(root, rows));
    }

    private static double single(MetricKind kind, Map<String, String> p, SnmpResult r) throws MetricException {
        List<MetricValue> v = kind.calculate(p, r);
        assertEquals(1, v.size());
        return v.get(0).value();
    }

    // ─── 移植自 PollParserTest：CPU（HUNDRED_MINUS）─────────────────

    private static final Map<String, String> CPU = Map.of("oid", OidConstants.CPU_IDLE);

    @Test
    void CPU_使用率為100減idle() throws Exception {
        assertEquals(25.0, single(MetricKind.HUNDRED_MINUS, CPU, scalars(OidConstants.CPU_IDLE, 75)), 0.001);
    }

    @Test
    void CPU_idle未回傳時失敗() {
        MetricException e = assertThrows(MetricException.class, () -> MetricKind.HUNDRED_MINUS.calculate(CPU, scalars()));
        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    void CPU_idle非數字時失敗不拋其他例外() {
        MetricException e = assertThrows(MetricException.class,
            () -> MetricKind.HUNDRED_MINUS.calculate(CPU, scalars(OidConstants.CPU_IDLE, "noSuchObject")));
        assertTrue(e.getMessage().contains("不是數字"));
    }

    // ─── 移植自 PollParserTest：Memory（UCD_MEMORY）──────────────────

    @Test
    void Memory_扣除free_buffer_cache後計算使用率() throws Exception {
        MetricValue v = MetricKind.UCD_MEMORY.calculate(Map.of(), scalars(
            OidConstants.MEM_TOTAL, 1000, OidConstants.MEM_FREE, 200,
            OidConstants.MEM_BUFFER, 100, OidConstants.MEM_CACHE, 200)).get(0);
        assertEquals(50.0, v.value(), 0.001);
        assertEquals(1000L * 1024, v.totalBytes());
        assertEquals(500L * 1024,  v.usedBytes());
    }

    @Test
    void Memory_total為0時使用率為0() throws Exception {
        assertEquals(0.0, single(MetricKind.UCD_MEMORY, Map.of(), scalars(
            OidConstants.MEM_TOTAL, 0, OidConstants.MEM_FREE, 0,
            OidConstants.MEM_BUFFER, 0, OidConstants.MEM_CACHE, 0)), 0.001);
    }

    @Test
    void Memory_缺任一OID時失敗() {
        assertThrows(MetricException.class, () -> MetricKind.UCD_MEMORY.calculate(Map.of(), scalars(
            OidConstants.MEM_TOTAL, 1000, OidConstants.MEM_FREE, 200, OidConstants.MEM_CACHE, 200)));
    }

    @Test
    void Memory_任一值非數字時失敗() {
        assertThrows(MetricException.class, () -> MetricKind.UCD_MEMORY.calculate(Map.of(), scalars(
            OidConstants.MEM_TOTAL, 1000, OidConstants.MEM_FREE, "noSuchInstance",
            OidConstants.MEM_BUFFER, 100, OidConstants.MEM_CACHE, 200)));
    }

    // ─── 移植自 PollParserTest：Disk（STORAGE_TABLE）─────────────────

    private static VariableBinding hr(int column, int index, Variable v) {
        return new VariableBinding(new OID(OidConstants.HR_STORAGE_ENTRY + "." + column + "." + index), v);
    }

    private static final Map<String, String> DISK = Map.of("typeFilter", FIXED_DISK);

    @Test
    void Disk_只保留固定磁碟並以分配單位換算位元組() throws Exception {
        List<VariableBinding> rows = List.of(   // 欄位順序打散，確認依 index 組合
            hr(6, 31, new Integer32(250)),
            hr(2, 1,  new OID(RAM)),
            hr(2, 31, new OID(FIXED_DISK)),
            hr(3, 31, new OctetString("/data")),
            hr(4, 31, new Integer32(4096)),
            hr(5, 31, new Integer32(1000)),
            hr(3, 1,  new OctetString("Physical memory")));

        List<MetricValue> disks = MetricKind.STORAGE_TABLE.calculate(DISK, walk(OidConstants.HR_STORAGE_ENTRY, rows));

        assertEquals(1, disks.size());
        assertEquals("/data", disks.get(0).instance());
        assertEquals(4096L * 1000, disks.get(0).totalBytes());
        assertEquals(4096L * 250,  disks.get(0).usedBytes());
        assertEquals(25.0, disks.get(0).value(), 0.001);
    }

    @Test
    void Disk_缺少欄位時使用預設值() throws Exception {
        List<VariableBinding> rows = List.of(hr(2, 5, new OID(FIXED_DISK)), hr(5, 5, new Integer32(10)));

        MetricValue d = MetricKind.STORAGE_TABLE.calculate(DISK, walk(OidConstants.HR_STORAGE_ENTRY, rows)).get(0);
        assertEquals("unknown", d.instance());
        assertEquals(10, d.totalBytes());     // allocationUnits 預設 1
        assertEquals(0,  d.usedBytes());
    }

    // ─── STORAGE_TABLE 其他情境 ──────────────────────────────────

    @Test
    void Storage_first模式取第一列且不帶實例名稱_用於記憶體() throws Exception {
        List<VariableBinding> rows = List.of(
            hr(2, 1, new OID(RAM)), hr(3, 1, new OctetString("Physical memory")),
            hr(4, 1, new Integer32(1024)), hr(5, 1, new Integer32(8000)), hr(6, 1, new Integer32(2000)));

        MetricValue v = MetricKind.STORAGE_TABLE.calculate(Map.of("typeFilter", RAM, "first", "true"),
            walk(OidConstants.HR_STORAGE_ENTRY, rows)).get(0);

        assertNull(v.instance());
        assertEquals(25.0, v.value(), 0.001);
    }

    @Test
    void Storage_表格沒有資料時失敗_有資料但無符合類型時回傳空清單() throws Exception {
        assertThrows(MetricException.class, () -> MetricKind.STORAGE_TABLE.calculate(DISK, new SnmpResult(Map.of(), Map.of())));
        List<VariableBinding> ramOnly = List.of(hr(2, 1, new OID(RAM)));
        assertTrue(MetricKind.STORAGE_TABLE.calculate(DISK, walk(OidConstants.HR_STORAGE_ENTRY, ramOnly)).isEmpty());
        assertThrows(MetricException.class, () -> MetricKind.STORAGE_TABLE.calculate(
            Map.of("typeFilter", FIXED_DISK, "first", "true"), walk(OidConstants.HR_STORAGE_ENTRY, ramOnly)));
    }

    // ─── VALUE ────────────────────────────────────────────────────

    @Test
    void Value_原值與倍率() throws Exception {
        assertEquals(45.0, single(MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.9.1"), scalars("1.3.6.1.4.1.9.1", 45)), 0.001);
        assertEquals(4.5,  single(MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.9.1", "scale", "0.1"),
            scalars("1.3.6.1.4.1.9.1", 45)), 0.001);
    }

    @Test
    void Value_文字型數字也可解析() throws Exception {
        assertEquals(0.75, single(MetricKind.VALUE, Map.of("oid", "1.3.6.1.4.1.2021.10.1.3.1"),
            scalars("1.3.6.1.4.1.2021.10.1.3.1", "0.75")), 0.001);
    }

    @Test
    void Value_SNMP例外值視為不存在() {
        assertThrows(MetricException.class, () -> MetricKind.VALUE.calculate(Map.of("oid", "1.3.6.1"),
            scalars("1.3.6.1", Null.noSuchInstance)));
    }

    // ─── USED_TOTAL_PERCENT / USED_FREE_PERCENT ─────────────────

    @Test
    void UsedTotal_百分比與位元組換算() throws Exception {
        MetricValue v = MetricKind.USED_TOTAL_PERCENT.calculate(
            Map.of("used", "1.1", "total", "1.2", "scale", "1024"),
            scalars("1.1", new Gauge32(300), "1.2", new Gauge32(1200))).get(0);
        assertEquals(25.0, v.value(), 0.001);
        assertEquals(1200L * 1024, v.totalBytes());
        assertEquals(300L * 1024,  v.usedBytes());
    }

    @Test
    void UsedTotal_總量為0時失敗() {
        MetricException e = assertThrows(MetricException.class, () -> MetricKind.USED_TOTAL_PERCENT.calculate(
            Map.of("used", "1.1", "total", "1.2"), scalars("1.1", 0, "1.2", 0)));
        assertTrue(e.getMessage().contains("總量為 0"));
    }

    @Test
    void UsedFree_已用除以已用加剩餘() throws Exception {
        assertEquals(25.0, single(MetricKind.USED_FREE_PERCENT, Map.of("used", "1.5", "free", "1.6"),
            scalars("1.5", 50, "1.6", 150)), 0.001);
    }

    @Test
    void UsedFree_已用與剩餘皆為0時失敗() {
        assertThrows(MetricException.class, () -> MetricKind.USED_FREE_PERCENT.calculate(
            Map.of("used", "1.5", "free", "1.6"), scalars("1.5", 0, "1.6", 0)));
    }

    // ─── TABLE_AVERAGE ────────────────────────────────────────────

    @Test
    void TableAverage_多列平均() throws Exception {
        String col = "1.3.6.1.2.1.25.3.3.1.2";
        List<VariableBinding> rows = new ArrayList<>();
        rows.add(new VariableBinding(new OID(col + ".196608"), new Integer32(10)));
        rows.add(new VariableBinding(new OID(col + ".196609"), new Integer32(30)));

        assertEquals(20.0, single(MetricKind.TABLE_AVERAGE, Map.of("column", col), walk(col, rows)), 0.001);
    }

    @Test
    void TableAverage_沒有資料時失敗() {
        assertThrows(MetricException.class, () -> MetricKind.TABLE_AVERAGE.calculate(
            Map.of("column", "1.3.6.1.2.1.25.3.3.1.2"), new SnmpResult(Map.of(), Map.of())));
    }

    // ─── 參數驗證與需要的 OID ────────────────────────────────────

    @Test
    void 驗證_缺少或不合法的OID() {
        assertNotNull(MetricKind.VALUE.validateParams(Map.of()));
        assertNotNull(MetricKind.VALUE.validateParams(Map.of("oid", "abc")));
        assertTrue(MetricKind.USED_TOTAL_PERCENT.validateParams(Map.of("used", "1.1")).contains("total"));
        assertNotNull(MetricKind.STORAGE_TABLE.validateParams(Map.of()));
        assertNotNull(MetricKind.STORAGE_TABLE.validateParams(Map.of("typeFilter", FIXED_DISK, "entry", "x")));
        assertNull(MetricKind.UCD_MEMORY.validateParams(null));
    }

    @Test
    void 驗證_倍率必須為正數() {
        assertNotNull(MetricKind.VALUE.validateParams(Map.of("oid", "1.1", "scale", "0")));
        assertNotNull(MetricKind.VALUE.validateParams(Map.of("oid", "1.1", "scale", "abc")));
        assertNull(MetricKind.VALUE.validateParams(Map.of("oid", "1.1", "scale", "0.01")));
    }

    @Test
    void 需要的OID與子樹() {
        assertEquals(List.of("1.1", "1.2"), MetricKind.USED_TOTAL_PERCENT.requiredScalarOids(Map.of("used", "1.1", "total", "1.2")));
        assertEquals(List.of(OidConstants.HR_STORAGE_ENTRY), MetricKind.STORAGE_TABLE.requiredWalkRoots(DISK));
        assertEquals(4, MetricKind.UCD_MEMORY.requiredScalarOids(Map.of()).size());
        assertTrue(MetricKind.VALUE.requiredWalkRoots(Map.of("oid", "1.1")).isEmpty());
    }

    @Test
    void 解析計算類型() {
        assertEquals(MetricKind.TABLE_AVERAGE, MetricKind.parse("table_average"));
        assertNull(MetricKind.parse("EXPRESSION"));
        assertNull(MetricKind.parse(null));
    }
}
