package io.github.chiayi1224.simplesnmp.web.servlet;

import io.github.chiayi1224.simplesnmp.config.DeviceConfig;
import io.github.chiayi1224.simplesnmp.config.V3UserConfig;
import io.github.chiayi1224.simplesnmp.profile.MetricException;
import io.github.chiayi1224.simplesnmp.profile.MetricValue;
import io.github.chiayi1224.simplesnmp.profile.ProfileCollector;
import io.github.chiayi1224.simplesnmp.snmp.SnmpException;
import io.github.chiayi1224.simplesnmp.storage.MetricStore;
import io.github.chiayi1224.simplesnmp.trap.TrapReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 設備管理 API
 *
 *   GET    /api/devices          — 取得所有設備
 *   GET    /api/devices/{name}   — 取得單台設備
 *   POST   /api/devices          — 新增設備
 *   POST   /api/devices/{name}   — 更新設備（body 含設備欄位）
 *                                  或刪除設備（body 含 "action":"delete"）
 *
 * 不支援 PUT / DELETE HTTP 方法，所有寫入操作皆透過 POST。
 *
 * 新增 body（JSON）：
 * {
 *   "name":      "Server-01",
 *   "host":      "192.168.1.10",
 *   "port":      161,
 *   "community": "public",
 *   "retries":   2,
 *   "timeoutMs": 3000,
 *   "snmpVersion":    "2c",          ← 選填：1 / 2c / 3，預設 2c
 *   "v3User":         "monitor",     ← 以下 v3 欄位僅 snmpVersion=3 時使用
 *   "v3AuthProtocol": "SHA256",      ← MD5 / SHA / SHA224 / SHA256 / SHA384 / SHA512
 *   "v3AuthPassword": "********",    ← 至少 8 字元；省略代表不設定（更新時代表沿用舊值）
 *   "v3PrivProtocol": "AES256",      ← DES / AES128 / AES192 / AES256
 *   "v3PrivPassword": "********",    ← 需先有認證密碼；更新時傳 "" 代表清除
 *   "profileId":      3              ← 監控範本；null 代表依 sysObjectID 自動套用，更新時省略代表不變
 *   "diskFilter":     "/,/data,/mnt/*" ← 只記錄符合的磁碟掛載點（逗號分隔、可用 *）；"" 代表全部，更新時省略代表不變
 * }
 *
 * 回應不包含 community 與 v3 密碼，只有 communityConfigured / v3AuthConfigured / v3PrivConfigured。
 *
 * 更新 body（JSON）：
 * {
 *   "host":      "192.168.1.20",
 *   "port":      161,
 *   "community": "private",       ← 省略或留空代表沿用舊值
 *   "enabled":   true,
 *   "retries":   2,
 *   "timeoutMs": 3000
 * }
 *
 * 刪除 body（JSON）：
 * { "action": "delete" }
 *
 * 探測磁碟（不儲存，供設備對話框挑選要監控的掛載點）：
 *   POST /api/admin/devices          body 為尚未儲存的連線欄位 + "action":"probe-disks"
 *   POST /api/admin/devices/{name}   同上；community 與 v3 密碼省略或留空時使用已儲存的值
 *   回應：{ "disks": [ { "mountPoint", "usedPct", "totalBytes", "usedBytes" } ] }
 *
 * 每次增修刪後會呼叫 TrapReceiver.reloadDevices()，
 * 確保 Trap 來源 IP → 設備名稱的對應表即時更新。
 */
public class DeviceServlet extends BaseJsonServlet {

    /** 探測用的連線參數：失敗要快，不重試太久 */
    private static final int  PROBE_RETRIES    = 1;
    private static final long PROBE_TIMEOUT_MS = 2000L;

    private final MetricStore      store;
    private final TrapReceiver     trapReceiver;
    private final ProfileCollector collector;   // 探測磁碟用；null 代表未啟用

    public DeviceServlet(MetricStore store, TrapReceiver trapReceiver) {
        this(store, trapReceiver, null);
    }

    public DeviceServlet(MetricStore store, TrapReceiver trapReceiver, ProfileCollector collector) {
        this.store        = store;
        this.trapReceiver = trapReceiver;
        this.collector    = collector;
    }

    // GET /api/devices  或  GET /api/devices/{name}
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String name = pathName(req);
            if (name == null) {
                writeJson(resp, store.queryAllDevices());
            } else {
                Map<String, Object> device = store.queryDeviceByName(name);
                if (device == null) {
                    writeError(resp, 404, "找不到設備：" + name);
                } else {
                    writeJson(resp, device);
                }
            }
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    // POST /api/devices          → 新增設備
    // POST /api/devices/{name}   → 更新設備，或 {"action":"delete"} 刪除設備
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String name = pathName(req);
            JsonNode body = parseBody(req, resp);
            if (body == null) return;

            if ("probe-disks".equals(optText(body, "action", ""))) { probeDisks(body, name, resp); return; }

            if (name == null) {
                // 新增設備
                String dName = requireText(body, "name", resp); if (dName == null) return;
                String host  = requireText(body, "host", resp); if (host  == null) return;

                DeviceConfig device = parseDevice(body, dName, host, null);
                String error = device.validate();
                if (error == null) error = validateProfile(device);
                if (error != null) { writeError(resp, 400, error); return; }

                store.addDevice(device);
                // 此 IP 已登錄為設備，不再是未登錄來源
                store.deleteUnknownSourceByIp(host);
                trapReceiver.reloadDevices(store.loadEnabledDevices());
                resp.setStatus(201);
                writeJson(resp, Map.of("message", "新增成功", "name", dName));

            } else {
                // 更新或刪除
                String action = optText(body, "action", "update");
                if ("delete".equals(action)) {
                    store.deleteDeviceByName(name);
                    trapReceiver.reloadDevices(store.loadEnabledDevices());
                    writeJson(resp, Map.of("message", "刪除成功", "name", name));
                } else {
                    String host = requireText(body, "host", resp); if (host == null) return;

                    DeviceConfig existing = store.loadDevice(name);
                    DeviceConfig device   = parseDevice(body, name, host, existing);
                    // 以「合併舊密碼後」的結果驗證，確保 auth / priv 搭配與長度正確
                    String error = mergePasswords(device, existing).validate();
                    if (error == null) error = validateProfile(device);
                    if (error != null) { writeError(resp, 400, error); return; }

                    boolean enabled = optBool(body, "enabled", true);
                    store.updateDeviceByName(device, enabled);
                    trapReceiver.reloadDevices(store.loadEnabledDevices());
                    writeJson(resp, Map.of("message", "更新成功", "name", name));
                }
            }
        } catch (Exception e) {
            writeError(resp, 500, e.getMessage());
        }
    }

    /** 以對話框目前填的連線資料探測磁碟；已存在的設備可沿用已儲存的 community 與 v3 密碼 */
    private void probeDisks(JsonNode body, String name, HttpServletResponse resp) throws IOException {
        if (collector == null) { writeError(resp, 503, "磁碟探測功能未啟用"); return; }
        String host = requireText(body, "host", resp); if (host == null) return;

        DeviceConfig existing = name == null ? null : store.loadDevice(name);
        if (name != null && existing == null) { writeError(resp, 404, "找不到設備：" + name); return; }

        DeviceConfig parsed = parseDevice(body, name == null ? "(探測)" : name, host, existing);
        String error = mergePasswords(parsed, existing).validate();
        if (error != null) { writeError(resp, 400, error); return; }

        DeviceConfig target = withStoredSecrets(parsed, existing);
        List<Map<String, Object>> disks = new ArrayList<>();
        try {
            List<MetricValue> values = new ArrayList<>(collector.probeDisks(target));
            values.sort(Comparator.comparing(v -> String.valueOf(v.instance())));
            for (MetricValue v : values) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("mountPoint", v.instance());
                d.put("usedPct",    v.value());
                d.put("totalBytes", v.totalBytes());
                d.put("usedBytes",  v.usedBytes());
                disks.add(d);
            }
        } catch (SnmpException e) {
            writeError(resp, 502, "設備無法查詢：" + e.getMessage());
            return;
        } catch (MetricException e) {
            writeError(resp, 502, "設備沒有提供磁碟資訊：" + e.getMessage());
            return;
        }
        writeJson(resp, Map.of("disks", disks));
    }

    /** 組出實際連線用的設備：community 與 v3 密碼未提供時使用已儲存的值；逾時與重試改用探測專用的較短設定 */
    private static DeviceConfig withStoredSecrets(DeviceConfig d, DeviceConfig existing) {
        String community = d.getCommunity() != null ? d.getCommunity()
            : existing != null ? existing.getCommunity() : "public";
        String auth = d.getV3AuthPassword() != null ? d.getV3AuthPassword()
            : existing != null ? existing.getV3AuthPassword() : "";
        String priv = d.getV3PrivPassword() != null ? d.getV3PrivPassword()
            : existing != null ? existing.getV3PrivPassword() : "";
        return new DeviceConfig.Builder(d.getName(), d.getHost())
            .port(d.getPort()).community(community)
            .retries(PROBE_RETRIES).timeoutMs(PROBE_TIMEOUT_MS)
            .snmpVersion(d.getSnmpVersion()).v3User(d.getV3User())
            .v3AuthProtocol(d.getV3AuthProtocol()).v3AuthPassword(auth)
            .v3PrivProtocol(d.getV3PrivProtocol()).v3PrivPassword(priv)
            .build();
    }

    /**
     * 由 request body 組出設備設定。
     * 新增時（existing == null）未提供的欄位用預設值；更新時 SNMP 版本與 v3 非密碼欄位未提供則沿用舊值。
     * community 與 v3 密碼一樣不會由 API 回傳：更新時未提供或留空代表沿用舊值。
     * v3 密碼：欄位不存在 → null（新增時視為未設定、更新時沿用舊值）；存在 → 原值（"" 代表清除）。
     */
    private DeviceConfig parseDevice(JsonNode body, String name, String host, DeviceConfig existing) {
        DeviceConfig.Builder b = new DeviceConfig.Builder(name, host)
            .port     (optInt (body, "port",      161))
            .community(optText(body, "community", existing != null ? null : "public"))
            .retries  (optInt (body, "retries",   2))
            .timeoutMs(optLong(body, "timeoutMs", 3000L));

        String version = rawText(body, "snmpVersion");
        b.snmpVersion(version != null || existing == null
            ? DeviceConfig.SnmpVersion.parse(version)
            : existing.getSnmpVersion());

        String user = rawText(body, "v3User");
        b.v3User(user != null ? user.trim() : existing != null ? existing.getV3User() : "");

        String authProto = rawText(body, "v3AuthProtocol");
        b.v3AuthProtocol(authProto != null || existing == null
            ? V3UserConfig.AuthProtocol.parse(authProto) : existing.getV3AuthProtocol());

        String privProto = rawText(body, "v3PrivProtocol");
        b.v3PrivProtocol(privProto != null || existing == null
            ? V3UserConfig.PrivProtocol.parse(privProto) : existing.getV3PrivProtocol());

        // 監控範本：欄位存在（含 null）就採用，省略時沿用舊值
        JsonNode profile = body.path("profileId");
        if (profile.isMissingNode()) {
            b.profileId(existing != null ? existing.getProfileId() : null);
        } else if (profile.isNumber()) {
            b.profileId(profile.asLong());
        } else if (profile.isTextual() && !profile.asText().isBlank() && !"auto".equals(profile.asText().trim())) {
            b.profileId(parseId(profile.asText().trim()));   // 非數字時為 -1，由 validateProfile 回 400
        } else {
            b.profileId(null);                               // null / "" / "auto"：依 sysObjectID 自動套用
        }

        // 磁碟過濾：欄位存在就採用（"" 代表全部），省略時沿用舊值
        String diskFilter = rawText(body, "diskFilter");
        b.diskFilter(diskFilter != null ? diskFilter : existing != null ? existing.getDiskFilter() : "");

        String authPass = rawText(body, "v3AuthPassword");
        String privPass = rawText(body, "v3PrivPassword");
        b.v3AuthPassword(authPass != null || existing != null ? authPass : "");
        b.v3PrivPassword(privPass != null || existing != null ? privPass : "");
        return b.build();
    }

    /** 手動指定的範本必須存在；合法回傳 null */
    private String validateProfile(DeviceConfig d) {
        if (d.getProfileId() == null) return null;
        if (d.getProfileId() < 0 || store.loadProfile(d.getProfileId()) == null) {
            return "找不到監控範本 #" + d.getProfileId();
        }
        return null;
    }

    /** 密碼為 null（沿用舊值）時填入舊密碼，供驗證使用 */
    private static DeviceConfig mergePasswords(DeviceConfig d, DeviceConfig existing) {
        String auth = d.getV3AuthPassword() != null ? d.getV3AuthPassword()
            : existing != null ? existing.getV3AuthPassword() : "";
        String priv = d.getV3PrivPassword() != null ? d.getV3PrivPassword()
            : existing != null ? existing.getV3PrivPassword() : "";
        return new DeviceConfig.Builder(d.getName(), d.getHost())
            .snmpVersion(d.getSnmpVersion()).v3User(d.getV3User())
            .v3AuthProtocol(d.getV3AuthProtocol()).v3AuthPassword(auth)
            .v3PrivProtocol(d.getV3PrivProtocol()).v3PrivPassword(priv)
            .diskFilter(d.getDiskFilter())
            .build();
    }

    /** 欄位不存在或為 JSON null 時回傳 null，否則回傳文字（不 trim，保留密碼原樣） */
    private static String rawText(JsonNode body, String field) {
        JsonNode node = body.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
