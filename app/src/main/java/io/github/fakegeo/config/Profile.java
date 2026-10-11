package io.github.fakegeo.config;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

/** An immutable snapshot; never expose mutable preferences to a hook. */
public final class Profile {
    public final boolean enabled, systemMode, location, wifi, bluetooth, cell;
    public final long revision;
    public final double latitude, longitude, altitude;
    public final float accuracy;
    public final long interval;
    public final String ssid, bssid, wifiMac, bluetoothMac, bluetoothName;
    public final Set<String> targets;
    public final boolean gnss;
    public final String locationCacheTarget;
    public final boolean jitter;
    public final double jitterMeters, motionSpeed, motionInitialSpeed, motionBearing;
    public final long motionElapsed, motionWall, motionLease;
    public final int motionMode;
    public final long motionSeed;
    public final boolean sensors;
    public final Set<String> sensorTargets;

    public Profile(JSONObject json) {
        enabled = json.optBoolean("enabled", false);
        systemMode = json.optBoolean("systemMode", true);
        gnss = json.optBoolean("gnss", false);
        jitter = json.optBoolean("jitter", true);
        sensors = json.optBoolean("sensors", false);
        sensorTargets = Collections.unmodifiableSet(Validation.packages(json.optString("sensorTargets", "")));
        double mode = Validation.number(json.optString("motionMode", "0"), 0, 2, "移动模式");
        if (mode != Math.floor(mode)) throw new IllegalArgumentException("移动模式必须是整数");
        motionMode = (int) mode;
        motionSeed = json.optLong("motionSeed", 1);
        if (motionSeed < 0) throw new IllegalArgumentException("运动种子无效");
        jitterMeters = Validation.number(json.optString("jitterMeters", "2"), 0, 30, "定位漂移半径（米）");
        motionSpeed = Validation.number(json.optString("motionSpeed", "0"), 0, 20, "移动速度（米／秒）");
        motionInitialSpeed = Validation.number(json.optString("motionInitialSpeed", "0"), 0, 20, "初始移动速度");
        motionBearing = Validation.number(json.optString("motionBearing", "0"), 0, 360, "移动方向");
        motionElapsed = json.optLong("motionElapsed", 0);
        motionWall = json.optLong("motionWall", 0);
        motionLease = json.optLong("motionLease", 0);
        if (motionElapsed < 0 || motionWall < 0 || motionLease < 0 || motionLease > 6000)
            throw new IllegalArgumentException("移动指令时限无效");
        boolean legacyCache = json.optBoolean("campusCacheRefresh", false);
        locationCacheTarget = Validation.cachePackage(json.optString("locationCacheTarget",
                legacyCache ? "com.wisedu.cpdaily" : "").trim());
        revision = json.optLong("revision", 0);
        location = json.optBoolean("location", true);
        wifi = json.optBoolean("wifi", true);
        bluetooth = json.optBoolean("bluetooth", true);
        cell = json.optBoolean("cell", true);
        latitude = Validation.number(json.optString("latitude", "39.9042"), -90, 90, "纬度");
        longitude = Validation.number(json.optString("longitude", "116.4074"), -180, 180, "经度");
        if (enabled && systemMode && location && latitude == 0 && longitude == 0)
            throw new IllegalArgumentException("系统会丢弃 (0, 0) 非模拟位置，请选择其他坐标");
        altitude = Validation.number(json.optString("altitude", "50"), -1000, 20000, "海拔");
        accuracy = (float) Validation.number(json.optString("accuracy", "10"), 0.1, 10000, "精度");
        double ms = Validation.number(json.optString("interval", "1000"), 500, 3600000, "更新间隔（毫秒）");
        if (ms != Math.floor(ms)) throw new IllegalArgumentException("更新间隔必须是整数");
        interval = (long) ms;
        ssid = json.optString("ssid", "FakeGeo-Lab");
        if (ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32)
            throw new IllegalArgumentException("SSID 需要 1–32 字节");
        bssid = Validation.mac(json.optString("bssid", "02:00:00:00:00:10"));
        wifiMac = Validation.mac(json.optString("wifiMac", "02:00:00:00:00:11"));
        bluetoothMac = Validation.mac(json.optString("bluetoothMac", "02:00:00:00:00:12"));
        bluetoothName = json.optString("bluetoothName", "FakeGeo");
        if (bluetoothName.length() > 64) throw new IllegalArgumentException("蓝牙名称最长 64 字符");
        targets = Collections.unmodifiableSet(Validation.packages(json.optString("targets", "")));
        if (enabled && !systemMode && targets.isEmpty()) throw new IllegalArgumentException("应用模式启用前请添加目标应用包名");
    }
    public static Profile defaults() { return new Profile(new JSONObject()); }
    public static Profile parse(String text) throws JSONException { return new Profile(new JSONObject(text)); }
    public JSONObject json() {
        JSONObject j = new JSONObject();
        try {
            j.put("enabled", enabled); j.put("location", location); j.put("wifi", wifi);
            j.put("systemMode", systemMode); j.put("revision", revision);
            j.put("gnss", gnss);
            j.put("jitter", jitter); j.put("jitterMeters", jitterMeters);
            j.put("motionSpeed", motionSpeed); j.put("motionInitialSpeed", motionInitialSpeed);
            j.put("motionBearing", motionBearing); j.put("motionElapsed", motionElapsed);
            j.put("motionWall", motionWall); j.put("motionLease", motionLease);
            j.put("motionMode", motionMode); j.put("motionSeed", motionSeed);
            j.put("sensors", sensors); j.put("sensorTargets", String.join("\n", sensorTargets));
            j.put("locationCacheTarget", locationCacheTarget);
            j.put("bluetooth", bluetooth); j.put("cell", cell);
            j.put("latitude", latitude); j.put("longitude", longitude); j.put("altitude", altitude);
            j.put("accuracy", accuracy); j.put("interval", interval);
            j.put("ssid", ssid); j.put("bssid", bssid); j.put("wifiMac", wifiMac);
            j.put("bluetoothMac", bluetoothMac); j.put("bluetoothName", bluetoothName);
            j.put("targets", String.join("\n", targets));
        } catch (JSONException e) { throw new IllegalStateException(e); }
        return j;
    }
}
