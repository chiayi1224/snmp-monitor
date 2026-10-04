package io.github.chiayi1224.simplesnmp.profile;

import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.util.List;
import java.util.Map;

/**
 * 一次輪詢取得的 SNMP 資料（唯讀），供計算器使用，不依賴 SNMP 連線
 *
 * @param scalars GET 結果：OID → 值（設備不支援的 OID 不會出現）
 * @param walks   走訪結果：子樹根 OID → 該子樹下的所有 VarBind
 */
public record SnmpResult(Map<String, Variable> scalars, Map<String, List<VariableBinding>> walks) {

    public SnmpResult {
        scalars = Map.copyOf(scalars);
        walks   = Map.copyOf(walks);
    }

    /** GET 取得的值；不存在時回傳 null */
    public Variable scalar(String oid) {
        return scalars.get(oid);
    }

    /** 子樹走訪結果；沒有走訪或沒有資料時回傳空清單 */
    public List<VariableBinding> walk(String root) {
        return walks.getOrDefault(root, List.of());
    }
}
