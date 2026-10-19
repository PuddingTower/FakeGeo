package io.github.fakegeo.environment;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A single access point captured from a real scan. Immutable; safe to hand to system services. */
public final class WifiFingerprint {
    public static final int MAX_SSID_BYTES = 32;
    public final String ssid, bssid, capabilities;
    public final int rssi, frequency;
    public final boolean connected;

    public WifiFingerprint(String ssid, String bssid, int rssi, int frequency, String capabilities, boolean connected) {
        this.ssid = ssid(ssid);
        this.bssid = mac(bssid);
        if (rssi >= 0 || rssi < -127) throw new IllegalArgumentException("Wi-Fi RSSI 超出范围");
        this.rssi = rssi;
        if (frequency != 0 && (frequency < 2400 || frequency > 7125))
            throw new IllegalArgumentException("Wi-Fi 频率超出范围");
        this.frequency = frequency == 0 ? 2412 : frequency;
        String value = capabilities == null ? "" : capabilities.trim();
        this.capabilities = value.length() > 128 ? value.substring(0, 128) : value;
        this.connected = connected;
    }

    public boolean sameAccessPoint(WifiFingerprint other) {
        return other != null && bssid.equals(other.bssid);
    }

    public JSONObject json() {
        JSONObject json = new JSONObject();
        try {
            json.put("ssid", ssid).put("bssid", bssid).put("rssi", rssi)
                    .put("frequency", frequency).put("capabilities", capabilities).put("connected", connected);
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return json;
    }

    /** Invalid captured entries are skipped by the caller instead of failing the whole import. */
    public static WifiFingerprint parse(JSONObject json) {
        if (json == null) return null;
        String bssid = json.optString("bssid", "");
        if (bssid.isEmpty()) return null;
        return new WifiFingerprint(json.optString("ssid", ""), bssid,
                json.optInt("rssi", -70), json.optInt("frequency", 2412),
                json.optString("capabilities", ""), json.optBoolean("connected", false));
    }

    private static String ssid(String value) {
        String result = value == null ? "" : value;
        int bytes = result.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > MAX_SSID_BYTES) throw new IllegalArgumentException("SSID 需要 1–32 字节");
        return result;
    }

    private static String mac(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
            throw new IllegalArgumentException("BSSID 格式无效");
        if ((Integer.parseInt(result.substring(0, 2), 16) & 1) != 0)
            throw new IllegalArgumentException("BSSID 不能是组播地址");
        return result;
    }
}
