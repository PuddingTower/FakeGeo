package io.github.fakegeo.environment;

import org.json.JSONObject;
import java.util.Locale;

/** One captured BLE advertiser, carrying the exact advertising payload for lossless replay. */
public final class BleFingerprint {
    public static final int MAX_PAYLOAD_BYTES = 255;
    public final String address, name, payload;
    public final int rssi;
    public final boolean beaconLike;

    public BleFingerprint(String address, String name, int rssi, String payload) {
        this.address = mac(address);
        String label = name == null ? "" : name;
        if (label.length() > 64) label = label.substring(0, 64);
        this.name = label;
        if (rssi >= 0 || rssi < -127) throw new IllegalArgumentException("蓝牙 RSSI 超出范围");
        this.rssi = rssi;
        String raw = hex(payload);
        if (raw.length() / 2 > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("蓝牙广播超过 255 字节");
        this.payload = raw;
        this.beaconLike = isBeaconPayload(raw);
    }

    public boolean isLocallyAdministered() {
        return (Integer.parseInt(address.substring(0, 2), 16) & 0x02) != 0;
    }

    /** A stable per-device phase keeps the synthetic RSSI curve from moving in lockstep. */
    public double phase() {
        return ((address.hashCode() & 0xFF) / 255.0) * Math.PI * 2;
    }

    public JSONObject json() {
        JSONObject json = new JSONObject();
        try {
            json.put("address", address).put("name", name).put("rssi", rssi).put("payload", payload);
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return json;
    }

    public static BleFingerprint parse(JSONObject json) {
        if (json == null) return null;
        String address = json.optString("address", "");
        if (address.isEmpty()) return null;
        return new BleFingerprint(address, json.optString("name", ""),
                json.optInt("rssi", -70), json.optString("payload", ""));
    }

    /**
     * iBeacon and Eddystone payloads are prioritised during filtering because they are the frames
     * attendance terminals and indoor-positioning SDKs actually look for.
     */
    public static boolean isBeaconPayload(String hex) {
        if (hex == null || hex.length() < 8) return false;
        byte[] raw;
        try {
            raw = bytes(hex);
        } catch (RuntimeException invalid) { return false; }
        for (int cursor = 0; cursor + 1 < raw.length; ) {
            int length = raw[cursor] & 0xFF;
            if (length == 0 || cursor + 1 + length > raw.length) break;
            int type = raw[cursor + 1] & 0xFF;
            int data = cursor + 2, end = cursor + 1 + length;
            if (type == 0xFF && end - data >= 4) {
                int company = (raw[data] & 0xFF) | ((raw[data + 1] & 0xFF) << 8);
                if (company == 0x004C && end - data >= 6
                        && (raw[data + 2] & 0xFF) == 0x02 && (raw[data + 3] & 0xFF) == 0x15) return true;
            } else if (type == 0x16 && end - data >= 2) {
                int uuid = (raw[data] & 0xFF) | ((raw[data + 1] & 0xFF) << 8);
                if (uuid == 0xFEAA) return true;
            }
            cursor = end;
        }
        return false;
    }

    public static byte[] bytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    public static String hex(byte[] raw) {
        StringBuilder result = new StringBuilder(raw.length * 2);
        for (byte value : raw) result.append(String.format(Locale.ROOT, "%02X", value));
        return result.toString();
    }

    private static String hex(String value) {
        String result = value == null ? "" : value.replace(" ", "").replace(":", "").trim().toUpperCase(Locale.ROOT);
        if (result.isEmpty()) return "";
        if (!result.matches("[0-9A-F]+") || result.length() % 2 != 0)
            throw new IllegalArgumentException("蓝牙广播必须是完整的十六进制字节");
        return result;
    }

    private static String mac(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") || "00:00:00:00:00:00".equals(result))
            throw new IllegalArgumentException("蓝牙地址格式无效");
        return result;
    }
}
