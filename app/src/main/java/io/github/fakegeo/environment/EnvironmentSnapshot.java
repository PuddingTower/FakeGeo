package io.github.fakegeo.environment;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A reusable, filtered picture of one physical place: coordinates plus wireless anchors. */
public final class EnvironmentSnapshot {
    public static final int MAX_WIFI = 32, MAX_BLE = 16, MAX_CELLS = 8;
    public final String id, name;
    public final long capturedAt;
    public final boolean hasLocation;
    public final double latitude, longitude, altitude;
    public final float accuracy;
    public final List<WifiFingerprint> wifi;
    public final List<BleFingerprint> ble;
    public final List<CellFingerprint> cells;

    public EnvironmentSnapshot(String id, String name, long capturedAt, boolean hasLocation,
                               double latitude, double longitude, double altitude, float accuracy,
                               List<WifiFingerprint> wifi, List<BleFingerprint> ble, List<CellFingerprint> cells) {
        this.capturedAt = capturedAt <= 0 ? System.currentTimeMillis() : capturedAt;
        this.id = validId(id) ? id : Long.toString(this.capturedAt);
        String label = clean(name);
        if (label.isEmpty()) label = "环境 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(this.capturedAt));
        if (label.length() > 64) label = label.substring(0, 64).trim();
        this.name = label;
        boolean usable = hasLocation && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180 && !(latitude == 0 && longitude == 0);
        this.hasLocation = usable;
        this.latitude = usable ? latitude : 0;
        this.longitude = usable ? longitude : 0;
        this.altitude = usable && Double.isFinite(altitude) ? Math.max(-1000, Math.min(20000, altitude)) : 0;
        this.accuracy = usable && Float.isFinite(accuracy) ? Math.max(0.1f, Math.min(10000f, accuracy)) : 10f;
        this.wifi = unmodifiable(sortedWifi(wifi));
        this.ble = unmodifiable(sortedBle(ble));
        this.cells = unmodifiable(sortedCells(cells));
    }

    public boolean hasConnectedWifi() {
        for (WifiFingerprint point : wifi) if (point.connected) return true;
        return false;
    }

    public boolean isEmpty() { return wifi.isEmpty() && ble.isEmpty() && cells.isEmpty() && !hasLocation; }

    public String summary() {
        return "Wi-Fi " + wifi.size() + " · 蓝牙 " + ble.size() + " · 小区 " + cells.size();
    }

    public EnvironmentSnapshot renamed(String value) {
        return new EnvironmentSnapshot(id, value, capturedAt, hasLocation, latitude, longitude, altitude, accuracy, wifi, ble, cells);
    }

    public JSONObject json() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id).put("name", name).put("capturedAt", capturedAt);
            if (hasLocation) json.put("latitude", latitude).put("longitude", longitude)
                    .put("altitude", altitude).put("accuracy", accuracy);
            JSONArray wifiJson = new JSONArray();
            for (WifiFingerprint point : wifi) wifiJson.put(point.json());
            JSONArray bleJson = new JSONArray();
            for (BleFingerprint point : ble) bleJson.put(point.json());
            JSONArray cellJson = new JSONArray();
            for (CellFingerprint point : cells) cellJson.put(point.json());
            json.put("wifi", wifiJson).put("ble", bleJson).put("cells", cellJson);
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return json;
    }

    /** Never throws on one bad entry; a corrupt capture must not make the published profile unreadable. */
    public static EnvironmentSnapshot parse(JSONObject json) {
        if (json == null) return null;
        long capturedAt = json.optLong("capturedAt", System.currentTimeMillis());
        String id = json.optString("id", "");
        String name = json.optString("name", "");
        boolean hasLocation = json.has("latitude") && json.has("longitude");
        double latitude = json.optDouble("latitude", 0);
        double longitude = json.optDouble("longitude", 0);
        double altitude = json.optDouble("altitude", 0);
        float accuracy = (float) json.optDouble("accuracy", 10);
        List<WifiFingerprint> wifi = new ArrayList<>();
        JSONArray wifiJson = json.optJSONArray("wifi");
        if (wifiJson != null) for (int i = 0; i < wifiJson.length() && wifi.size() < MAX_WIFI; i++)
            try { WifiFingerprint point = WifiFingerprint.parse(wifiJson.optJSONObject(i)); if (point != null) wifi.add(point); }
            catch (RuntimeException skipped) { /* Skip one malformed anchor. */ }
        List<BleFingerprint> ble = new ArrayList<>();
        JSONArray bleJson = json.optJSONArray("ble");
        if (bleJson != null) for (int i = 0; i < bleJson.length() && ble.size() < MAX_BLE; i++)
            try { BleFingerprint point = BleFingerprint.parse(bleJson.optJSONObject(i)); if (point != null) ble.add(point); }
            catch (RuntimeException skipped) { /* Skip one malformed advertiser. */ }
        List<CellFingerprint> cells = new ArrayList<>();
        JSONArray cellJson = json.optJSONArray("cells");
        if (cellJson != null) for (int i = 0; i < cellJson.length() && cells.size() < MAX_CELLS; i++)
            try { CellFingerprint point = CellFingerprint.parse(cellJson.optJSONObject(i)); if (point != null) cells.add(point); }
            catch (RuntimeException skipped) { /* Skip one malformed cell. */ }
        return new EnvironmentSnapshot(id, name, capturedAt, hasLocation, latitude, longitude, altitude, accuracy, wifi, ble, cells);
    }

    private static List<WifiFingerprint> sortedWifi(List<WifiFingerprint> source) {
        Map<String, WifiFingerprint> unique = new LinkedHashMap<>();
        if (source != null) for (WifiFingerprint point : source) {
            if (point == null) continue;
            WifiFingerprint previous = unique.get(point.bssid);
            if (previous == null || (!previous.connected && point.connected)) unique.put(point.bssid, point);
        }
        List<WifiFingerprint> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing((WifiFingerprint point) -> !point.connected)
                .thenComparingInt(point -> -point.rssi));
        return result.size() > MAX_WIFI ? new ArrayList<>(result.subList(0, MAX_WIFI)) : result;
    }

    private static List<BleFingerprint> sortedBle(List<BleFingerprint> source) {
        Map<String, BleFingerprint> unique = new LinkedHashMap<>();
        if (source != null) for (BleFingerprint point : source) if (point != null) unique.putIfAbsent(point.address, point);
        List<BleFingerprint> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing((BleFingerprint point) -> !point.beaconLike)
                .thenComparingInt(point -> -point.rssi));
        return result.size() > MAX_BLE ? new ArrayList<>(result.subList(0, MAX_BLE)) : result;
    }

    private static List<CellFingerprint> sortedCells(List<CellFingerprint> source) {
        Map<String, CellFingerprint> unique = new LinkedHashMap<>();
        if (source != null) for (CellFingerprint point : source) {
            if (point == null) continue;
            CellFingerprint previous = unique.get(point.key());
            if (previous == null || (!previous.registered && point.registered)
                    || (previous.registered == point.registered && point.signal > previous.signal)) unique.put(point.key(), point);
        }
        List<CellFingerprint> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing((CellFingerprint point) -> !point.registered)
                .thenComparingInt(point -> -point.signal));
        return result.size() > MAX_CELLS ? new ArrayList<>(result.subList(0, MAX_CELLS)) : result;
    }

    private static String clean(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            result.append(Character.isISOControl(character) ? ' ' : character);
        }
        return result.toString().trim();
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static <T> List<T> unmodifiable(List<T> values) { return Collections.unmodifiableList(values); }
}
