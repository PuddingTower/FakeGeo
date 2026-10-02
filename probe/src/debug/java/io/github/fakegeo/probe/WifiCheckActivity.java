package io.github.fakegeo.probe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground client of real system APIs. Stores hashes, never raw network names/addresses. */
@SuppressLint("MissingPermission")
public final class WifiCheckActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONObject record = new JSONObject();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private LocationManager locations;
    private final List<LocationListener> listeners = new ArrayList<>();
    private final List<String> providers = new ArrayList<>();
    private TextView text;
    private boolean registered;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); text = new TextView(this); text.setTextSize(18); text.setPadding(32,100,32,40);
        text.setText("Wi-Fi 系统回调检查\n\n仅记录身份摘要，不修改虚拟环境配置。"); setContentView(text);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 41);
        else start();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) start();
        else fail(new SecurityException("Precise location permission required"));
    }
    private void start() {
        try {
            record.put("api", Build.VERSION.SDK_INT); record.put("uid", android.os.Process.myUid());
            record.put("targetSdk", getApplicationInfo().targetSdkVersion);
            record.put("startedAt", System.currentTimeMillis());
            callback = Build.VERSION.SDK_INT >= 31 ? new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) { receive(nc); }
            } : new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) { receive(nc); }
            };
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, handler);
            registered = true;
            locations = (LocationManager) getSystemService(LOCATION_SERVICE);
            for (String provider : new String[]{"gps", "network", "passive", "fused"}) {
                if (!locations.getAllProviders().contains(provider)) continue;
                providers.add(provider);
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location value) { check(provider + "Callback", () -> point(provider + "Callback", value)); }
                    @Override public void onProviderEnabled(String name) {}
                    @Override public void onProviderDisabled(String name) {}
                    @Override public void onStatusChanged(String name, int status, Bundle extras) {}
                };
                check(provider + "Register", () -> {
                    locations.requestLocationUpdates(provider, 1000, 0, listener, Looper.getMainLooper()); listeners.add(listener);
                });
            }
            for (int i = 0; i < 10; i++) handler.postDelayed(this::snapshot, i * 1000L);
            handler.postDelayed(()-> { try { record.put("completed", true); save(); stop(); } catch(Exception failure) { fail(failure); } }, 11000);
        } catch(Exception failure) { fail(failure); }
    }
    private void receive(NetworkCapabilities nc) {
        try { record.put("callbacks", record.optInt("callbacks") + 1); identity("callback", nc); save(); }
        catch(Exception failure) { fail(failure); }
    }
    private void snapshot() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        // Keep sampling other routes if one API is denied or fails.
        check("direct", () -> wifi("direct", wifi.getConnectionInfo()));
        check("scan", () -> {
            List<ScanResult> values = wifi.getScanResults();
            record.put("scanSamples", record.optInt("scanSamples") + 1);
            record.put("scanLastSize", values == null ? -1 : values.size());
            if (values != null) for (ScanResult value : values) {
                unique("scanBssidHashes", hash(value.BSSID)); unique("scanSsidHashes", hash(value.SSID));
            }
        });
        check("query", () -> {
            Network active = connectivity.getActiveNetwork(); identity("query", active == null ? null : connectivity.getNetworkCapabilities(active));
        });
        check("legacyActive", () -> legacy("legacyActive", connectivity.getActiveNetworkInfo()));
        check("legacyWifi", () -> legacy("legacyWifi", connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI)));
        for (String provider : providers)
            check(provider + "Last", () -> point(provider + "Last", locations.getLastKnownLocation(provider)));
        save();
    }
    private interface Checked { void run() throws Exception; }
    private void check(String stage, Checked action) {
        try { action.run(); }
        catch (Exception failure) {
            try { record.put(stage + "Error", failure.getClass().getSimpleName()); } catch (Exception ignored) {}
        }
    }
    private void legacy(String kind, NetworkInfo info) throws Exception {
        if (info == null) { record.put(kind + "Nulls", record.optInt(kind + "Nulls") + 1); return; }
        record.put(kind + "Count", record.optInt(kind + "Count") + 1);
        record.put(kind + "Wifi", info.getType() == ConnectivityManager.TYPE_WIFI);
        record.put(kind + "Connected", info.isConnected()); unique(kind + "ExtraHashes", hash(info.getExtraInfo()));
    }
    private void point(String kind, Location value) throws Exception {
        if (value == null) { record.put(kind + "Nulls", record.optInt(kind + "Nulls") + 1); return; }
        record.put(kind + "Count", record.optInt(kind + "Count") + 1);
        unique(kind + "PointHashes", hash(String.format(Locale.US, "%.8f,%.8f", value.getLatitude(), value.getLongitude())));
    }
    private void identity(String kind, NetworkCapabilities nc) throws Exception {
        if (nc == null) { record.put(kind + "Nulls", record.optInt(kind + "Nulls") + 1); return; }
        record.put(kind + "Wifi", nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        record.put(kind + "Validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        if (nc.getTransportInfo() instanceof WifiInfo) wifi(kind, (WifiInfo) nc.getTransportInfo());
        else record.put(kind + "NoWifiInfo", record.optInt(kind + "NoWifiInfo") + 1);
    }
    private void wifi(String kind, WifiInfo info) throws Exception {
        if (info == null) return;
        record.put(kind + "Count", record.optInt(kind + "Count") + 1);
        unique(kind + "BssidHashes", hash(info.getBSSID())); unique(kind + "SsidHashes", hash(info.getSSID()));
        record.put(kind + "Redacted", info.getBSSID() == null || "02:00:00:00:00:00".equals(info.getBSSID()));
    }
    private void unique(String key, String value) throws Exception {
        JSONArray values = record.optJSONArray(key); if (values == null) { values = new JSONArray(); record.put(key, values); }
        for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return;
        values.put(value);
    }
    private static String hash(String value) throws Exception {
        if (value == null) return "null";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(Locale.US, "%02x", b & 255)); return result.toString();
    }
    private void save() {
        getSharedPreferences("wifi_check", MODE_PRIVATE).edit().putString("result", record.toString()).apply();
        text.setText("Wi-Fi 系统回调检查\n\n" + (record.optBoolean("completed") ? "检查完成" : "正在检查…")
                + "\n直接查询：" + record.optInt("directCount") + "\n网络查询：" + record.optInt("queryCount")
                + "\n扫描检查：" + record.optInt("scanSamples") + "\n回调：" + record.optInt("callbacks")
                + "\n接口兼容级别：" + getApplicationInfo().targetSdkVersion
                + "\n\n仅保存身份及位置摘要，不修改虚拟环境配置。");
    }
    private void fail(Exception failure) { try { record.put("error", failure.getClass().getSimpleName()); save(); } catch(Exception ignored) {} }
    private void stop() {
        if (registered) { registered = false; check("unregisterNetwork", () -> connectivity.unregisterNetworkCallback(callback)); }
        if (locations != null) for (LocationListener listener : listeners) check("unregisterLocation", () -> locations.removeUpdates(listener));
        listeners.clear();
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); stop(); super.onDestroy(); }
}
