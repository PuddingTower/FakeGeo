package io.github.fakegeo.environment;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.TelephonyManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import io.github.fakegeo.config.ConfigStore;

/**
 * A foreground, one-shot capture. It never starts a background scan: Wi-Fi is sampled three times,
 * BLE for fourteen seconds, cell identity twice, and the whole session stops itself after 22 s.
 */
public final class EnvironmentCapture {
    public interface Listener {
        void progress(String message);
        void finished(EnvironmentSnapshot snapshot);
        void failed(String message);
    }
    private static final int WIFI_ROUND_MS = 5000;
    private static final long BLE_DURATION_MS = 14000;
    private static final long TOTAL_MS = 22000;
    private static final long BLE_SAMPLE_GAP_MS = 250;
    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WifiFingerprint> wifiSamples = new ArrayList<>();
    private final List<BleFingerprint> bleSamples = new ArrayList<>();
    private final List<CellFingerprint> cellSamples = new ArrayList<>();
    private final Set<String> collectedCells = new HashSet<>();
    private WifiManager wifiManager;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BroadcastReceiver wifiReceiver;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location bestLocation;
    private final java.util.Map<String, Long> lastBleAt = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastWifiAt;
    private long lastBleProgress;
    private boolean started;
    private volatile boolean finished;

    public EnvironmentCapture(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public static String missingPermission(Activity activity) {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "精确定位";
        if (Build.VERSION.SDK_INT >= 31) {
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                return "蓝牙扫描";
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                return "蓝牙连接";
        }
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            return "附近的 Wi-Fi 设备";
        return null;
    }

    public static String[] permissions() {
        List<String> result = new ArrayList<>();
        result.add(Manifest.permission.ACCESS_FINE_LOCATION);
        result.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            result.add(Manifest.permission.BLUETOOTH_SCAN);
            result.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) result.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        return result.toArray(new String[0]);
    }

    public void start() {
        if (started) return;
        started = true;
        String missing = missingPermission(activity);
        if (missing != null) { fail("采集需要" + missing + "权限，请授权后重试"); return; }
        if (ConfigStore.published(activity).enabled || ConfigStore.load(activity).enabled) {
            fail("请先停止虚拟环境再采集，否则会把伪造的指纹当成真实环境"); return;
        }
        locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null || !locationManager.isLocationEnabled()) {
            fail("请先打开系统定位开关，Wi-Fi 与基站字段也需要定位权限"); return;
        }
        listener.progress("正在读取当前位置…");
        readLastKnown();
        subscribeLocation();
        startWifi();
        startBle();
        handler.postDelayed(this::collectCells, 1500);
        handler.postDelayed(this::collectCells, 12000);
        handler.postDelayed(this::finishNow, TOTAL_MS);
    }

    /** Ends the capture early; the listener still receives the filtered snapshot. */
    public void finishNow() { finish(); }

    public void cancel() {
        if (finished) return;
        finished = true;
        stopWifi();
        stopBle();
        stopLocation();
        handler.removeCallbacksAndMessages(null);
    }

    private void startWifi() {
        wifiManager = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) { listener.progress("设备不支持 Wi-Fi 扫描，跳过"); return; }
        wifiReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { collectWifi(); }
        };
        try {
            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(wifiReceiver, filter);
        } catch (RuntimeException unavailable) { /* Cached results still work. */ }
        listener.progress("正在扫描 Wi-Fi…");
        collectWifi();
        scanWifi();
        handler.postDelayed(() -> { collectWifi(); scanWifi(); }, WIFI_ROUND_MS);
        handler.postDelayed(this::collectWifi, WIFI_ROUND_MS * 2 + 1000);
    }

    private void scanWifi() {
        if (wifiManager == null) return;
        try { wifiManager.startScan(); }
        catch (SecurityException denied) { listener.progress("Wi-Fi 扫描被系统拒绝，使用缓存结果"); }
        catch (RuntimeException unavailable) { /* A throttled scan keeps the cached snapshot. */ }
    }

    private void collectWifi() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastWifiAt < 800 || wifiManager == null) return;
        lastWifiAt = now;
        try {
            String connectedBssid = null;
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info != null) connectedBssid = info.getBSSID();
            List<ScanResult> results = wifiManager.getScanResults();
            if (results == null) return;
            for (ScanResult result : results) {
                if (result == null || result.BSSID == null) continue;
                String ssid = result.SSID == null ? "" : result.SSID;
                if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\""))
                    ssid = ssid.substring(1, ssid.length() - 1);
                if (ssid.isEmpty() || "<unknown ssid>".equals(ssid)) continue;
                try {
                    wifiSamples.add(new WifiFingerprint(ssid, result.BSSID, result.level, result.frequency,
                            result.capabilities, result.BSSID.equalsIgnoreCase(connectedBssid)));
                } catch (RuntimeException malformed) { /* Hidden network or unusable RSSI. */ }
            }
            if (!wifiSamples.isEmpty()) listener.progress("已采集 " + distinctWifi() + " 个 Wi-Fi 信号");
        } catch (SecurityException denied) { fail("Wi-Fi 扫描需要定位与附近设备权限"); }
        catch (RuntimeException unavailable) { /* Keep whatever the previous round produced. */ }
    }

    private int distinctWifi() {
        Set<String> values = new HashSet<>();
        for (WifiFingerprint point : wifiSamples) values.add(point.bssid);
        return values.size();
    }

    private void startBle() {
        try {
            BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { listener.progress("蓝牙未开启，跳过蓝牙采集"); return; }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { listener.progress("蓝牙扫描不可用，跳过"); return; }
            scanCallback = new ScanCallback() {
                @Override public void onScanResult(int type, android.bluetooth.le.ScanResult result) { addBle(result); }
                @Override public void onBatchScanResults(List<android.bluetooth.le.ScanResult> results) {
                    if (results != null) for (android.bluetooth.le.ScanResult result : results) addBle(result);
                }
            };
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            listener.progress("正在扫描蓝牙信标（14 秒）…");
            handler.postDelayed(this::stopBle, BLE_DURATION_MS);
        } catch (SecurityException denied) { listener.progress("蓝牙扫描权限被拒绝，跳过"); }
        catch (RuntimeException unavailable) { listener.progress("蓝牙扫描不可用，跳过"); }
    }

    private void addBle(android.bluetooth.le.ScanResult result) {
        if (finished || result == null || result.getDevice() == null) return;
        long now = SystemClock.elapsedRealtime();
        final String address;
        try { address = result.getDevice().getAddress(); }
        catch (RuntimeException unavailable) { return; }
        Long previous = lastBleAt.get(address);
        if (previous != null && now - previous < BLE_SAMPLE_GAP_MS) return;
        lastBleAt.put(address, now);
        final String name;
        final byte[] raw;
        try {
            android.bluetooth.le.ScanRecord record = result.getScanRecord();
            name = record == null ? null : record.getDeviceName();
            raw = record == null ? null : record.getBytes();
        } catch (RuntimeException unavailable) { return; }
        final int rssi = result.getRssi();
        handler.post(() -> recordBle(address, name, rssi, raw));
    }

    /** Runs on the capture handler so the sample lists never mutate while finish() is filtering. */
    private void recordBle(String address, String name, int rssi, byte[] raw) {
        if (finished || bleSamples.size() >= 4000) return;
        try {
            bleSamples.add(new BleFingerprint(address, name, rssi, raw == null ? "" : BleFingerprint.hex(raw)));
            long now = SystemClock.elapsedRealtime();
            if (now - lastBleProgress > 1000) {
                lastBleProgress = now;
                listener.progress("已采集 " + distinctBle() + " 个蓝牙设备");
            }
        } catch (RuntimeException malformed) { /* Invalid or empty advertisement. */ }
    }

    private int distinctBle() {
        Set<String> values = new HashSet<>();
        for (BleFingerprint point : bleSamples) values.add(point.address);
        return values.size();
    }

    private void stopBle() {
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) { /* Already stopped. */ }
        }
        scanner = null; scanCallback = null;
    }

    private void collectCells() {
        if (finished) return;
        TelephonyManager telephony = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephony == null) return;
        try {
            List<CellInfo> values = telephony.getAllCellInfo();
            if (values == null) return;
            for (CellInfo value : values) {
                CellFingerprint point = cell(value);
                if (point == null || !collectedCells.add(point.key())) continue;
                cellSamples.add(point);
            }
            if (!cellSamples.isEmpty()) listener.progress("已采集 " + cellSamples.size() + " 个小区");
        } catch (SecurityException denied) { /* Cell fields can be redacted without fine location. */ }
        catch (RuntimeException unavailable) { /* Vendor telephony may be temporarily unavailable. */ }
    }

    private static CellFingerprint cell(CellInfo info) {
        try {
            if (info instanceof CellInfoLte) {
                CellIdentityLte identity = ((CellInfoLte) info).getCellIdentity();
                if (identity == null) return null;
                int signal = ((CellInfoLte) info).getCellSignalStrength() == null ? -90
                        : ((CellInfoLte) info).getCellSignalStrength().getRsrp();
                return new CellFingerprint(CellFingerprint.LTE, text(identity.getMccString(), identity.getMcc(), 3),
                        text(identity.getMncString(), identity.getMnc(), 2), value(identity.getTac()),
                        value(identity.getCi()), value(identity.getPci()), value(identity.getEarfcn()), signal,
                        info.isRegistered());
            }
            if (info instanceof CellInfoNr) {
                CellIdentityNr identity = (CellIdentityNr) ((CellInfoNr) info).getCellIdentity();
                if (identity == null) return null;
                android.telephony.CellSignalStrength strength = ((CellInfoNr) info).getCellSignalStrength();
                int signal = strength instanceof android.telephony.CellSignalStrengthNr
                        ? ((android.telephony.CellSignalStrengthNr) strength).getSsRsrp() : -90;
                long nci = identity.getNci();
                if (nci < 0 || nci == Long.MAX_VALUE) nci = 0;
                return new CellFingerprint(CellFingerprint.NR, text(identity.getMccString(), 0, 3),
                        text(identity.getMncString(), 0, 2), value(identity.getTac()), nci,
                        value(identity.getPci()), value(identity.getNrarfcn()), signal, info.isRegistered());
            }
            if (info instanceof CellInfoGsm) {
                CellIdentityGsm identity = ((CellInfoGsm) info).getCellIdentity();
                if (identity == null) return null;
                int signal = ((CellInfoGsm) info).getCellSignalStrength() == null ? -90
                        : ((CellInfoGsm) info).getCellSignalStrength().getDbm();
                return new CellFingerprint(CellFingerprint.GSM, text(identity.getMccString(), identity.getMcc(), 3),
                        text(identity.getMncString(), identity.getMnc(), 2), value(identity.getLac()),
                        value(identity.getCid()), value(identity.getPsc()), value(identity.getArfcn()), signal,
                        info.isRegistered());
            }
            if (info instanceof CellInfoWcdma) {
                CellIdentityWcdma identity = ((CellInfoWcdma) info).getCellIdentity();
                if (identity == null) return null;
                int signal = ((CellInfoWcdma) info).getCellSignalStrength() == null ? -90
                        : ((CellInfoWcdma) info).getCellSignalStrength().getDbm();
                return new CellFingerprint(CellFingerprint.WCDMA, text(identity.getMccString(), identity.getMcc(), 3),
                        text(identity.getMncString(), identity.getMnc(), 2), value(identity.getLac()),
                        value(identity.getCid()), value(identity.getPsc()), value(identity.getUarfcn()), signal,
                        info.isRegistered());
            }
        } catch (RuntimeException unavailable) { /* Redacted or vendor-shaped cell. */ }
        return null;
    }

    private static String text(String text, int fallback, int digits) {
        if (text != null && text.matches("[0-9]{2,3}")) return text;
        if (fallback <= 0 || fallback == Integer.MAX_VALUE) return "";
        return String.format(java.util.Locale.ROOT, "%0" + digits + "d", fallback);
    }

    private static int value(int value) { return value == Integer.MAX_VALUE ? 0 : Math.max(0, value); }

    private void readLastKnown() {
        if (locationManager == null) return;
        for (String provider : new String[]{"gps", "fused", "network"}) {
            try { considerLocation(locationManager.getLastKnownLocation(provider)); }
            catch (RuntimeException unavailable) { /* Provider absent. */ }
        }
    }

    private void considerLocation(Location value) {
        if (value == null || isMock(value)) return;
        long age = System.currentTimeMillis() - value.getTime();
        if (age > 15 * 60 * 1000L) return;
        if (bestLocation == null) { bestLocation = value; return; }
        boolean fresher = value.getTime() > bestLocation.getTime() + 10_000;
        boolean muchBetter = value.getAccuracy() + 25 < bestLocation.getAccuracy();
        if (muchBetter || (fresher && value.getAccuracy() <= bestLocation.getAccuracy() + 50))
            bestLocation = value;
    }

    /** Another mock provider must not become the "real" anchor of a captured environment. */
    @SuppressWarnings("deprecation")
    private static boolean isMock(Location value) {
        return Build.VERSION.SDK_INT >= 31 ? value.isMock() : value.isFromMockProvider();
    }

    private void subscribeLocation() {
        if (locationManager == null) return;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { considerLocation(location); }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        for (String provider : new String[]{"gps", "network"}) {
            try { locationManager.requestLocationUpdates(provider, 1000, 0, locationListener, Looper.getMainLooper()); }
            catch (RuntimeException unavailable) { /* Provider absent or permission changed. */ }
        }
    }

    private void stopLocation() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (RuntimeException ignored) { /* Already gone. */ }
        }
        locationListener = null;
    }

    private void stopWifi() {
        if (wifiReceiver != null) {
            try { activity.unregisterReceiver(wifiReceiver); } catch (RuntimeException ignored) { /* Already gone. */ }
        }
        wifiReceiver = null;
    }

    private void finish() {
        if (finished) return;
        handler.removeCallbacksAndMessages(null);
        collectCells();
        finished = true;
        stopWifi();
        stopBle();
        stopLocation();
        listener.progress("正在清理重复信标并提取锚点…");
        List<WifiFingerprint> wifi = EnvironmentFilter.wifi(wifiSamples);
        List<BleFingerprint> ble = EnvironmentFilter.ble(bleSamples);
        List<CellFingerprint> cells = EnvironmentFilter.cells(cellSamples);
        Location fix = bestLocation;
        boolean hasFix = fix != null;
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(null, null, System.currentTimeMillis(), hasFix,
                hasFix ? fix.getLatitude() : 0, hasFix ? fix.getLongitude() : 0,
                hasFix ? fix.getAltitude() : 0, hasFix ? fix.getAccuracy() : 10, wifi, ble, cells);
        if (snapshot.isEmpty()) { listener.failed("没有采集到可用的 Wi-Fi、蓝牙、小区或位置，请靠近目标环境后重试"); return; }
        listener.finished(snapshot);
    }

    private void fail(String message) {
        cancel();
        listener.failed(message);
    }
}
