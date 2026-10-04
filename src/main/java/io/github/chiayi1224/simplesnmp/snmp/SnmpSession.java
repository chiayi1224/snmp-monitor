package io.github.chiayi1224.simplesnmp.snmp;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依設備設定（v1 / v2c / v3）發出 SNMP 查詢的共用元件，供 Poller 與 OID 掃描使用。
 *
 * - 使用自己的 SecurityProtocols / USM，不依賴 SNMP4J 全域 singleton（全域實例預設沒有任何協定）
 * - v3 使用者依設備的 engine ID 本地化後加入 USM：USM 以（engineID, username）為鍵，
 *   未本地化時兩台設備使用相同 username、不同密碼會互相覆蓋
 * - 只發出 GET / GETNEXT / GETBULK，不提供 SET
 */
public class SnmpSession implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SnmpSession.class);

    /** GETBULK 每次最多取回的筆數 */
    static final int BULK_MAX_REPETITIONS = 20;

    private final SecurityProtocols securityProtocols =
        new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility);
    private final USM  usm  = new USM(securityProtocols, new OctetString(MPv3.createLocalEngineID()), 0);
    private final Snmp snmp;

    /** host:port → 設備的 authoritative engine ID */
    private final Map<String, OctetString> engineIds = new ConcurrentHashMap<>();

    public SnmpSession() throws IOException {
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());
        dispatcher.addMessageProcessingModel(new MPv3(usm.getLocalEngineID().getValue(), null,
            securityProtocols, SecurityModels.getCollection(new SecurityModel[]{ usm }),
            CounterSupport.getInstance()));
        snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping());
        snmp.listen();
    }

    // ─── 查詢 ─────────────────────────────────────────────────────

    /**
     * SNMP GET。回傳 OID → 值（保留請求順序）；設備不支援的 OID（noSuchObject 等）不會出現在結果中。
     * v1 設備遇到不存在的 OID 會讓整個請求回 noSuchName，此時移除該 OID 後重送，其餘 OID 照常取得。
     *
     * @throws SnmpException 設備無回應、v3 認證失敗或回應錯誤狀態
     */
    public Map<String, Variable> get(DeviceConfig device, List<String> oids) throws IOException {
        Map<String, Variable> result = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(oids);
        Target<UdpAddress> target = targetFor(device);
        while (!pending.isEmpty()) {
            PDU pdu = newPdu(device, PDU.GET);
            for (String oid : pending) pdu.add(new VariableBinding(new OID(oid)));

            PDU response = sendRaw(device, pdu, target);
            int index = response.getErrorIndex();
            if (response.getErrorStatus() == PDU.noSuchName && index >= 1 && index <= pending.size()) {
                pending.remove(index - 1);
                continue;
            }
            checkErrorStatus(response);
            for (VariableBinding vb : response.getVariableBindings()) {
                if (!vb.isException()) result.put(vb.getOid().toString(), vb.getVariable());
            }
            break;
        }
        return result;
    }

    /**
     * 走訪 root 底下的所有 OID（v2c / v3 用 GETBULK，v1 用 GETNEXT）。
     *
     * @param maxRows    最多收集筆數，達到時停止並標記 truncated
     * @param deadlineMs 截止時間（System.currentTimeMillis），超過時停止並標記 truncated；0 代表不限
     * @throws SnmpException 第一次請求就失敗時拋出；中途失敗則回傳已取得的部分並附上錯誤
     */
    public WalkResult walk(DeviceConfig device, String root, int maxRows, long deadlineMs) throws IOException {
        OID rootOid = new OID(root);
        Target<UdpAddress> target = targetFor(device);
        boolean bulk = device.getSnmpVersion() != DeviceConfig.SnmpVersion.V1;

        List<VariableBinding> rows = new ArrayList<>();
        OID current = rootOid;
        while (true) {
            if (rows.size() >= maxRows || (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs)) {
                return new WalkResult(rows, true, null);
            }
            PDU pdu = newPdu(device, bulk ? PDU.GETBULK : PDU.GETNEXT);
            if (bulk) pdu.setMaxRepetitions(BULK_MAX_REPETITIONS);
            pdu.add(new VariableBinding(current));

            PDU response;
            try {
                response = send(device, pdu, target);
            } catch (SnmpException e) {
                if (rows.isEmpty()) throw e;
                return new WalkResult(rows, false, e.getMessage());
            }

            boolean advanced = false;
            for (VariableBinding vb : response.getVariableBindings()) {
                if (vb.isException() || !vb.getOid().startsWith(rootOid)) {
                    return new WalkResult(rows, false, null);   // endOfMibView 或已離開子樹
                }
                if (vb.getOid().compareTo(current) <= 0) {
                    return new WalkResult(rows, false, "設備回傳的 OID 未遞增，停止走訪");
                }
                rows.add(vb);
                current = vb.getOid();
                advanced = true;
                if (rows.size() >= maxRows) break;
            }
            if (!advanced) return new WalkResult(rows, false, null);
        }
    }

    /** 走訪結果；truncated 表示因筆數或時間上限而停止，error 為中途失敗的原因（可為 null） */
    public record WalkResult(List<VariableBinding> rows, boolean truncated, String error) {}

    // ─── 連線目標 ─────────────────────────────────────────────────

    /** 依設備版本建立 SNMP4J target；v3 會先探索 engine ID 並加入本地化使用者 */
    Target<UdpAddress> targetFor(DeviceConfig device) throws IOException {
        UdpAddress address = (UdpAddress) GenericAddress.parse(device.getUdpAddress());
        if (address == null) throw new SnmpException("設備位址不合法：" + device.getHost());

        if (device.getSnmpVersion() != DeviceConfig.SnmpVersion.V3) {
            CommunityTarget<UdpAddress> t = new CommunityTarget<>(address, new OctetString(device.getCommunity()));
            t.setVersion(device.getSnmpVersion() == DeviceConfig.SnmpVersion.V1
                ? SnmpConstants.version1 : SnmpConstants.version2c);
            applyTiming(t, device);
            return t;
        }

        V3UserConfig user = device.toV3User();
        OctetString engineId = engineIdFor(device, address);
        usm.addUser(new OctetString(user.getUsername()), engineId, toUsmUser(user));

        UserTarget<UdpAddress> t = new UserTarget<>(address, new OctetString(user.getUsername()), engineId.getValue());
        t.setVersion(SnmpConstants.version3);
        t.setSecurityLevel(user.hasPriv() ? SecurityLevel.AUTH_PRIV
                         : user.hasAuth() ? SecurityLevel.AUTH_NOPRIV
                         : SecurityLevel.NOAUTH_NOPRIV);
        applyTiming(t, device);
        return t;
    }

    /** 清除設備的 engine ID 快取（設備設定變更、重灌或認證失敗時呼叫） */
    public void invalidate(DeviceConfig device) {
        engineIds.remove(cacheKey(device));
    }

    private OctetString engineIdFor(DeviceConfig device, UdpAddress address) throws SnmpException {
        OctetString cached = engineIds.get(cacheKey(device));
        if (cached != null) return cached;

        long timeout = Math.max(device.getTimeoutMs(), 1000L);
        byte[] id = snmp.discoverAuthoritativeEngineID(address, timeout);
        if (id == null) throw new SnmpException("SNMPv3 無法取得設備的 engine ID（設備無回應或未啟用 v3）");
        OctetString engineId = new OctetString(id);
        engineIds.put(cacheKey(device), engineId);
        log.debug("[{}] engine ID = {}", device.getName(), engineId.toHexString());
        return engineId;
    }

    private static String cacheKey(DeviceConfig device) {
        return device.getHost() + ":" + device.getPort();
    }

    private static UsmUser toUsmUser(V3UserConfig cfg) {
        OctetString name = new OctetString(cfg.getUsername());
        if (!cfg.hasAuth()) return new UsmUser(name, null, null, null, null);
        OctetString authPass = new OctetString(cfg.getAuthPassword());
        if (!cfg.hasPriv()) return new UsmUser(name, cfg.getAuthProtocolOid(), authPass, null, null);
        return new UsmUser(name, cfg.getAuthProtocolOid(), authPass,
            cfg.getPrivProtocolOid(), new OctetString(cfg.getPrivPassword()));
    }

    private static void applyTiming(Target<UdpAddress> t, DeviceConfig device) {
        t.setRetries(device.getRetries());
        t.setTimeout(device.getTimeoutMs());
    }

    // ─── 送出與錯誤判讀 ───────────────────────────────────────────

    private static PDU newPdu(DeviceConfig device, int type) {
        PDU pdu = device.getSnmpVersion() == DeviceConfig.SnmpVersion.V3 ? new ScopedPDU() : new PDU();
        pdu.setType(type);
        return pdu;
    }

    private PDU send(DeviceConfig device, PDU pdu, Target<UdpAddress> target) throws IOException {
        PDU response = sendRaw(device, pdu, target);
        checkErrorStatus(response);
        return response;
    }

    /** 送出請求；無回應或 v3 REPORT 時拋出例外，不檢查回應的錯誤狀態 */
    private PDU sendRaw(DeviceConfig device, PDU pdu, Target<UdpAddress> target) throws IOException {
        ResponseEvent<UdpAddress> event = snmp.send(pdu, target);
        PDU response = event == null ? null : event.getResponse();
        if (response == null) {
            // v3 逾時可能是 engine ID 已變（設備重灌），清除快取讓下次重新探索
            if (device.getSnmpVersion() == DeviceConfig.SnmpVersion.V3) invalidate(device);
            Exception error = event == null ? null : event.getError();
            throw new SnmpException("設備無回應" + (error != null ? "：" + error.getMessage() : ""));
        }
        if (response.getType() == PDU.REPORT) {
            invalidate(device);
            throw new SnmpException("SNMPv3 認證失敗：" + describeReport(response));
        }
        return response;
    }

    private static void checkErrorStatus(PDU response) throws SnmpException {
        if (response.getErrorStatus() != PDU.noError) {
            throw new SnmpException("設備回應錯誤：" + response.getErrorStatusText());
        }
    }

    private static String describeReport(PDU report) {
        if (report.size() == 0) return "未知原因";
        OID oid = report.get(0).getOid();
        if (oid.startsWith(SnmpConstants.usmStatsUnknownUserNames))  return "使用者名稱不存在";
        if (oid.startsWith(SnmpConstants.usmStatsWrongDigests))      return "認證密碼或協定錯誤";
        if (oid.startsWith(SnmpConstants.usmStatsDecryptionErrors))  return "加密密碼或協定錯誤";
        if (oid.startsWith(SnmpConstants.usmStatsUnsupportedSecLevels)) return "設備不支援此安全等級";
        if (oid.startsWith(SnmpConstants.usmStatsNotInTimeWindows))  return "時間視窗不同步";
        if (oid.startsWith(SnmpConstants.usmStatsUnknownEngineIDs))  return "engine ID 不符";
        return oid.toString();
    }

    @Override
    public void close() throws IOException {
        snmp.close();
    }

    /** 判斷值是否為 SNMP 例外值（noSuchObject / noSuchInstance / endOfMibView）或 Null */
    public static boolean isMissing(Variable v) {
        return v == null || v instanceof Null || v.isException();
    }
}
