package io.github.fakegeo.hook;

import android.net.wifi.ScanResult;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.environment.WifiFingerprint;
import de.robv.android.xposed.XposedHelpers;

/** Builds app-facing scan snapshots for every captured anchor, app and system side. */
public final class WifiScanFactory {
    private WifiScanFactory() {}

    public static int signal(long elapsedRealtime, int base, double phase) {
        double t = elapsedRealtime / 1000.0;
        int value = base + (int) Math.round(3 * Math.sin(t / 5.0 + phase));
        return Math.max(-95, Math.min(-20, value));
    }

    public static int signal(long elapsedRealtime) { return signal(elapsedRealtime, -45, 0); }

    public static int signal(WifiFingerprint point) {
        return signal(SystemClock.elapsedRealtime(), point.rssi, phase(point.bssid));
    }

    public static double phase(String key) {
        return ((key == null ? 0 : key.hashCode() & 0xFF) / 255.0) * Math.PI * 2;
    }

    public static List<ScanResult> results(Profile profile) {
        List<ScanResult> result = new ArrayList<>();
        long now = SystemClock.elapsedRealtimeNanos() / 1000;
        List<WifiFingerprint> points = profile.wifiPoints;
        for (int i = 0; i < points.size(); i++) result.add(result(points.get(i), now, i));
        return result;
    }

    public static ScanResult result(WifiFingerprint point, long nowMicros, int index) {
        ScanResult result = (ScanResult) XposedHelpers.newInstance(ScanResult.class);
        result.SSID = point.ssid;
        result.BSSID = point.bssid;
        result.capabilities = point.capabilities;
        result.level = signal(SystemClock.elapsedRealtime(), point.rssi, phase(point.bssid));
        result.frequency = point.frequency;
        result.timestamp = Math.max(0, nowMicros - index * 1500L);
        try { XposedHelpers.setObjectField(result, "wifiSsid", wifiSsid(point.ssid)); }
        catch (Throwable olderLayout) { /* SSID is still exposed through the public field. */ }
        return result;
    }

    public static Object wifiSsid(String value) {
        Class<?> type = XposedHelpers.findClass("android.net.wifi.WifiSsid", null);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try { return XposedHelpers.callStaticMethod(type, "fromBytes", bytes); }
        catch (Throwable oldApi) { return XposedHelpers.callStaticMethod(type, "createFromByteArray", bytes); }
    }

    public static String quotedSsid(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
