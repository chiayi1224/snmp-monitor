package io.github.chiayi1224.simplesnmp.snmp;

import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.MessageProcessingModel;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 測試用的最小 SNMP agent：以記憶體中的 MIB 回應 GET / GETNEXT / GETBULK（v1 / v2c / v3），
 * 並記錄收到的 PDU 類型，供測試確認掃描與輪詢的行為。
 */
public class TestSnmpAgent implements CommandResponder, Closeable {

    private final NavigableMap<OID, Variable> mib = new ConcurrentSkipListMap<>();
    private final List<Integer> receivedPduTypes = new CopyOnWriteArrayList<>();
    private final SecurityProtocols protocols =
        new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility);
    private final USM usm = new USM(protocols, new OctetString(MPv3.createLocalEngineID()), 0);
    private final Snmp snmp;
    private final int port;
    private volatile String community = "public";
    private volatile boolean silent = false;

    public TestSnmpAgent() throws IOException {
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());
        dispatcher.addMessageProcessingModel(new MPv3(usm.getLocalEngineID().getValue(), null, protocols,
            SecurityModels.getCollection(new SecurityModel[]{ usm }), CounterSupport.getInstance()));
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(new UdpAddress("127.0.0.1/0"));
        snmp = new Snmp(dispatcher, transport);
        snmp.addCommandResponder(this);
        snmp.listen();
        port = transport.getListenAddress().getPort();
    }

    public int port() { return port; }

    public TestSnmpAgent community(String c) { this.community = c; return this; }

    /** 設為 true 時完全不回應（模擬設備離線） */
    public TestSnmpAgent silent(boolean s) { this.silent = s; return this; }

    public TestSnmpAgent put(String oid, Variable value) { mib.put(new OID(oid), value); return this; }

    public TestSnmpAgent put(String oid, String value) { return put(oid, new OctetString(value)); }

    public TestSnmpAgent put(String oid, int value) { return put(oid, new Integer32(value)); }

    public TestSnmpAgent putAll(Map<String, Variable> values) {
        values.forEach(this::put);
        return this;
    }

    /** 新增 v3 使用者（未本地化，對本 agent 的 engine ID 生效） */
    public TestSnmpAgent addUser(UsmUser user) {
        usm.addUser(user);
        return this;
    }

    public List<Integer> receivedPduTypes() { return receivedPduTypes; }

    @Override
    public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
        PDU request = event.getPDU();
        if (request == null || silent) return;
        receivedPduTypes.add(request.getType());

        if (event.getMessageProcessingModel() != MessageProcessingModel.MPv3) {
            String c = new String(event.getSecurityName(), StandardCharsets.UTF_8);
            if (!c.equals(community)) { event.setProcessed(true); return; }
        }

        PDU response = (PDU) request.clone();
        response.setType(PDU.RESPONSE);
        response.setErrorStatus(PDU.noError);
        response.setErrorIndex(0);
        response.clear();                               // clear() 會把 request ID 歸零，需補回
        response.setRequestID(request.getRequestID());

        switch (request.getType()) {
            case PDU.GET -> {
                boolean v1 = event.getMessageProcessingModel() == MessageProcessingModel.MPv1;
                for (VariableBinding vb : request.getVariableBindings()) {
                    Variable v = mib.get(vb.getOid());
                    if (v == null && v1) {
                        // v1 沒有 noSuchObject：整個請求回 noSuchName，errorIndex 指向第一個不存在的 OID
                        response.clear();
                        response.setRequestID(request.getRequestID());
                        response.setErrorStatus(PDU.noSuchName);
                        response.setErrorIndex(request.getVariableBindings().indexOf(vb) + 1);
                        response.addAll(request.getVariableBindings());
                        break;
                    }
                    response.add(new VariableBinding(vb.getOid(), v != null ? v : Null.noSuchObject));
                }
            }
            case PDU.GETNEXT -> {
                for (VariableBinding vb : request.getVariableBindings()) response.add(next(vb.getOid()));
            }
            case PDU.GETBULK -> {
                int reps = Math.max(1, request.getMaxRepetitions());
                for (VariableBinding vb : request.getVariableBindings()) {
                    OID cur = vb.getOid();
                    for (int i = 0; i < reps; i++) {
                        VariableBinding n = next(cur);
                        response.add(n);
                        if (n.getVariable() == Null.endOfMibView) break;
                        cur = n.getOid();
                    }
                }
            }
            default -> {
                response.setErrorStatus(PDU.genErr);
                response.addAll(request.getVariableBindings());
            }
        }

        try {
            event.getMessageDispatcher().returnResponsePdu(
                event.getMessageProcessingModel(), event.getSecurityModel(),
                event.getSecurityName(), event.getSecurityLevel(),
                response, event.getMaxSizeResponsePDU(),
                event.getStateReference(), new StatusInformation());
        } catch (MessageException e) {
            throw new IllegalStateException(e);
        }
        event.setProcessed(true);
    }

    private VariableBinding next(OID oid) {
        Map.Entry<OID, Variable> e = mib.higherEntry(oid);
        return e == null ? new VariableBinding(oid, Null.endOfMibView) : new VariableBinding(e.getKey(), e.getValue());
    }

    @Override
    public void close() throws IOException {
        snmp.close();
    }
}
