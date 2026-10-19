package io.github.fakegeo.probe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.Priority;
import java.util.ArrayList;

/** A separate diagnostic app. Every permission-sensitive call runs through run(), which reports denials. */
@SuppressLint("MissingPermission")
public final class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView log;
    private LocationManager manager;
    private FusedLocationProviderClient fused;
    private PendingIntent pending;
    private int callbacks, gmsCallbacks;
    private String lastPending = "";
    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { append("系统回调 #" + (++callbacks) + ": " + format(location)); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };
    private final LocationCallback gmsCallback = new LocationCallback() {
        @Override public void onLocationResult(LocationResult result) { append("GMS 回调 #" + (++gmsCallbacks) + ": " + format(result.getLastLocation())); }
    };
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, android.bluetooth.le.ScanResult result) {
            if (result == null) { append("BLE 回调返回空结果"); return; }
            android.bluetooth.le.ScanRecord record = result.getScanRecord();
            append("BLE 发现设备：" + result.getDevice() + " rssi=" + result.getRssi()
                    + " 名称=" + (record == null ? "无" : record.getDeviceName())
                    + " 厂商数据=" + (record == null ? "无" : record.getManufacturerSpecificData(0x004C))
                    + " 原始广播=" + (record == null ? "无" : java.util.Arrays.toString(record.getBytes())));
        }
        @Override public void onBatchScanResults(java.util.List<android.bluetooth.le.ScanResult> results) {
            for (android.bluetooth.le.ScanResult result : results) onScanResult(0, result);
        }
        @Override public void onScanFailed(int error) { append("BLE 扫描错误：" + error); }
    };
    private final Runnable pollPending = new Runnable() {
        @Override public void run() {
            String value = getSharedPreferences("probe", MODE_PRIVATE).getString("pending", "");
            if (!value.equals(lastPending)) { lastPending = value; append(value); }
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        fused = LocationServices.getFusedLocationProviderClient(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(20, 48, 20, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        TextView title = new TextView(this); title.setText("FakeGeo Probe\n验证系统模式时，请勿把本 App 加入 LSPosed 作用域"); title.setTextSize(20); body.addView(title);
        button(body, "申请权限", this::permissions);
        button(body, "读取配置／位置／无线快照", this::snapshot);
        button(body, "系统持续定位 + PendingIntent", this::startSystem);
        button(body, "单次定位与预先取消检查", this::current);
        button(body, "GMS 最近位置与持续定位", this::startGms);
        button(body, "BLE 空扫描检查（观察 5 秒）", this::scan);
        button(body, "考勤防作弊八项自检", this::audit);
        button(body, "停止定位并检查取消回调", this::stop);
        log = new TextView(this); log.setTextIsSelectable(true); body.addView(log);
        append("包名：" + getPackageName() + " · API " + Build.VERSION.SDK_INT);
        append("系统模式无需读取模块配置；配置 IPC 拒绝是正常的。自行构造的位置应保持 (1, 2)，系统回调应为地图选点。应用兼容模式才需要本 App 的作用域和 URI 授权。");
    }
    @Override public void onStart() { super.onStart(); handler.post(pollPending); }
    @Override public void onStop() { handler.removeCallbacks(pollPending); stop(); super.onStop(); }
    private void permissions() {
        ArrayList<String> values = new ArrayList<>();
        values.add(Manifest.permission.ACCESS_FINE_LOCATION); values.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) { values.add(Manifest.permission.BLUETOOTH_SCAN); values.add(Manifest.permission.BLUETOOTH_CONNECT); }
        if (Build.VERSION.SDK_INT >= 33) values.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        requestPermissions(values.toArray(new String[0]), 1);
    }
    private void snapshot() {
        run("配置 IPC", () -> {
            try (Cursor c = getContentResolver().query(Uri.parse("content://" + BuildConfig.FG_AUTHORITY + "/profile"), null, null, null, null)) {
                append(c != null && c.moveToFirst() ? "配置：" + c.getString(0) : "未读到配置");
            }
        });
        run("位置", () -> {
            for (String provider : new String[]{"gps", "network", "fused"}) append(provider + ": " + format(manager.getLastKnownLocation(provider)));
            Location constructed = new Location("gps"); constructed.setLatitude(1); constructed.setLongitude(2);
            append("直接构造 Location 的 getter：" + format(constructed)); append("toString：" + constructed);
        });
        run("Wi-Fi", () -> {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            append("Wi-Fi：" + wifi.getConnectionInfo());
            append("Wi-Fi 扫描：" + wifi.getScanResults()); append("DHCP：" + wifi.getDhcpInfo());
        });
        run("网络能力中的无线身份", () -> {
            android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            append("NetworkCapabilities：" + capabilities);
        });
        run("基站", () -> append("基站：" + ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getAllCellInfo()));
        run("异步基站", () -> ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).requestCellInfoUpdate(getMainExecutor(), new TelephonyManager.CellInfoCallback() {
            @Override public void onCellInfo(java.util.List<android.telephony.CellInfo> values) {
                if (!isDestroyed()) append("异步基站：" + values);
            }
            @Override public void onError(int code, Throwable detail) {
                if (!isDestroyed()) append("异步基站错误：" + code + " / " + detail);
            }
        }));
        run("蓝牙", () -> {
            BluetoothAdapter adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
            if (adapter == null) { append("无蓝牙硬件"); return; }
            append("蓝牙：" + adapter.getName() + " / " + adapter.getAddress() + " · 已绑定=" + adapter.getBondedDevices());
        });
    }
    private void startSystem() {
        run("系统订阅", () -> {
            manager.requestLocationUpdates("gps", 1000, 0, listener, Looper.getMainLooper());
            manager.requestLocationUpdates("network", 1000, 0, listener, Looper.getMainLooper());
            pending = PendingIntent.getBroadcast(this, 10, new Intent(this, ProbeReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            manager.requestLocationUpdates("gps", 1000, 0, pending); append("已注册 GPS／network／PendingIntent。");
        });
    }
    private void current() {
        if (Build.VERSION.SDK_INT < 30) { append("Android 10 请使用持续定位按钮。"); return; }
        run("单次定位", () -> {
            manager.getCurrentLocation("gps", null, getMainExecutor(), loc -> append("单次：" + format(loc)));
            CancellationSignal canceled = new CancellationSignal(); canceled.cancel();
            manager.getCurrentLocation("gps", canceled, getMainExecutor(), loc -> append("失败：已取消请求仍回调"));
        });
    }
    private void startGms() {
        run("GMS", () -> {
            fused.getLastLocation().addOnSuccessListener(loc -> append("GMS 最近：" + format(loc))).addOnFailureListener(e -> append("GMS 失败：" + e));
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build();
            fused.requestLocationUpdates(request, gmsCallback, Looper.getMainLooper())
                    .addOnSuccessListener(v -> append("GMS 订阅成功")).addOnFailureListener(e -> append("GMS 订阅失败：" + e));
        });
    }
    private void scan() {
        run("BLE", () -> {
            BluetoothAdapter adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
            if (adapter == null || adapter.getBluetoothLeScanner() == null) { append("请开启蓝牙再检查。"); return; }
            adapter.getBluetoothLeScanner().startScan(scanCallback);
            handler.postDelayed(() -> run("停止 BLE", () -> {
                adapter.getBluetoothLeScanner().stopScan(scanCallback); append("BLE 观察结束；启用隔离后应无发现设备日志。");
            }), 5000);
        });
    }
    private void stop() {
        run("停止", () -> {
            manager.removeUpdates(listener);
            if (statusCallback != null) { manager.unregisterGnssStatusCallback((android.location.GnssStatus.Callback) statusCallback); statusCallback = null; }
            if (pending != null) { manager.removeUpdates(pending); pending.cancel(); pending = null; }
            fused.removeLocationUpdates(gmsCallback);
            append("已请求停止；随后不应再收到系统／GMS／PendingIntent 位置回调。");
        });
    }
    private Object statusCallback;
    /** The eight checks from the plan's verification matrix, reported the way an attendance SDK reads them. */
    private void audit() {
        run("1 系统与厂商定位一致性", () -> {
            StringBuilder line = new StringBuilder();
            for (String provider : new String[]{"gps", "network", "fused"}) {
                Location fix = manager.getLastKnownLocation(provider);
                line.append(provider).append(fix == null ? "=无 " : String.format(java.util.Locale.US, "=(%.6f,%.6f)±%.1fm ",
                        fix.getLatitude(), fix.getLongitude(), fix.getAccuracy()));
            }
            append("三路定位：" + line + "（三路坐标应一致，并随时间有分米级变化）");
        });
        run("2/3 Mock 标志、时钟不确定度与 Extras", () -> {
            Location fix = manager.getLastKnownLocation("gps");
            if (fix == null) { append("gps 无缓存，请先运行一次持续定位"); return; }
            boolean mock = Build.VERSION.SDK_INT >= 31 ? fix.isMock() : fix.isFromMockProvider();
            Bundle extras = fix.getExtras();
            append("isMock=" + mock + " · 时钟不确定度=" + (fix.hasElapsedRealtimeUncertaintyNanos()
                    ? fix.getElapsedRealtimeUncertaintyNanos() + " ns" : "无")
                    + " · satellites=" + (extras == null ? "无 Bundle" : extras.getInt("satellites"))
                    + " · maxCn0=" + (extras == null ? "-" : extras.getFloat("maxCn0"))
                    + " · meanCn0=" + (extras == null ? "-" : extras.getFloat("meanCn0")));
        });
        run("4 双频 GNSS 卫星状态", () -> {
            android.location.GnssStatus.Callback callback = new android.location.GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(android.location.GnssStatus status) {
                    int gps = 0, beidou = 0, dual = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        int type = status.getConstellationType(i);
                        if (type == android.location.GnssStatus.CONSTELLATION_GPS) gps++;
                        if (type == android.location.GnssStatus.CONSTELLATION_BEIDOU) beidou++;
                        if (status.hasCarrierFrequencyHz(i) && Math.abs(status.getCarrierFrequencyHz(i) - 1176.45e6f) < 1e3) dual++;
                    }
                    append("GNSS：" + status.getSatelliteCount() + " 颗 · GPS=" + gps + " · 北斗=" + beidou + " · L5/B2a=" + dual);
                }
            };
            statusCallback = callback;
            manager.registerGnssStatusCallback(callback, new Handler(Looper.getMainLooper()));
            append("已注册 GNSS 状态回调，观察下方输出（应用兼容模式下应每秒一条）。");
        });
        run("5 Wi-Fi 打卡链路一致性", () -> {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            String connected = wifi.getConnectionInfo().getBSSID();
            java.util.List<android.net.wifi.ScanResult> scans = wifi.getScanResults();
            long newest = 0; String scanned = null;
            for (android.net.wifi.ScanResult scan : scans) { newest = Math.max(newest, scan.timestamp); if (scanned == null) scanned = scan.BSSID; }
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network active = cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            append("已连接=" + connected + " · 扫描首位=" + scanned + " · 扫描条数=" + scans.size()
                    + " · 时间戳=" + newest + "（应与系统运行时间同量级且不断变大）");
            append("能力：WIFI=" + (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                    + " · CELLULAR=" + (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))
                    + " · TransportInfo=" + (caps == null ? "无" : caps.getTransportInfo())
                    + " · 计费=" + cm.isActiveNetworkMetered()
                    + " · 网卡=" + (cm.getLinkProperties(active) == null ? "无" : cm.getLinkProperties(active).getInterfaceName()));
        });
        run("6 基站", () -> append("基站：" + ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getAllCellInfo()));
        run("7 坐标系偏移", () -> append("系统定位为 WGS84；本探针不含高德 SDK，请在飞书内直接看地图落点是否与选点一致。"));
        run("8 模块痕迹", () -> {
            append("开发者选项模拟定位开关=" + android.provider.Settings.Secure.getInt(getContentResolver(), "allow_mock_location", -999));
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            append("AppOps mock_location=" + ops.unsafeCheckOpNoThrow("android:mock_location", android.os.Process.myUid(), getPackageName())
                    + "（0=允许 1=忽略 2=报错）");
            int frames = 0;
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                String owner = frame.getClassName() + frame.getFileName();
                if (owner.contains("xposed") || owner.contains("lsposed") || owner.contains("fakegeo") || owner.contains("LSPHooker")) frames++;
            }
            append("调用栈中的模块痕迹=" + frames + " 帧");
            try (Cursor c = getContentResolver().query(Uri.parse("content://" + BuildConfig.FG_AUTHORITY + "/profile"), null, null, null, null)) {
                append("配置 Provider：" + (c == null ? "未解析（无痕）" : c.moveToFirst() ? "可读取（说明仍在走 Provider）" : "空结果"));
            }
        });
    }
    private String format(Location location) {
        if (location == null) return "null";
        return location.getProvider() + " " + location.getLatitude() + ", " + location.getLongitude()
                + " · altitude=" + location.getAltitude() + " accuracy=" + location.getAccuracy();
    }
    private void run(String name, Runnable task) {
        try { task.run(); }
        catch (SecurityException denied) { append(name + "：权限被拒绝，请点击申请权限。" + denied.getMessage()); }
        catch (Exception e) { append(name + "：" + e); }
    }
    private void append(String value) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> append(value)); return; }
        log.append(value + "\n\n");
        if (log.length() > 30000) log.setText(log.getText().subSequence(log.length() - 20000, log.length()));
    }
    private void button(LinearLayout body, String label, Runnable task) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> task.run()); body.addView(button);
    }
}
