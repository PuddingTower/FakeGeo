package io.github.fakegeo.hook;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.environment.BleFingerprint;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class BluetoothHooks {
    private final RuntimeConfig config;
    private final Advertiser advertiser;
    BluetoothHooks(RuntimeConfig config) { this.config = config; advertiser = new Advertiser(config); }
    private boolean active() { Profile p = config.get(); return p.enabled && p.bluetooth; }
    void install() {
        for (String name : new String[]{"getAddress", "getName", "getBondedDevices"})
            Hooks.all(BluetoothAdapter.class, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (!active() || call.hasThrowable()) return;
                    switch (name) {
                        case "getAddress": call.setResult(config.get().bluetoothMac); break;
                        case "getName": call.setResult(config.get().bluetoothName); break;
                        default: call.setResult(new HashSet<>());
                    }
                }
            });
        // Do not enumerate nearby devices through classic discovery; LE scans are replaced by captured anchors.
        for (String name : new String[]{"startDiscovery", "startLeScan"})
            Hooks.all(BluetoothAdapter.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (active() && scanAllowed()) call.setResult(false);
                }
            });
        Hooks.all(BluetoothAdapter.class, "isDiscovering", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (active() && !call.hasThrowable()) call.setResult(false);
            }
        });
        Hooks.all(BluetoothLeScanner.class, "startScan", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (!active() || !scanAllowed()) return;
                ScanCallback callback = LocationHooks.find(call.args, ScanCallback.class);
                if (callback != null) {
                    advertiser.subscribe(callback, executor(call.args),
                            filters(call.args), LocationHooks.find(call.args, ScanSettings.class));
                    call.setResult(accepted((Method) call.method));
                    return;
                }
                call.setResult(accepted((Method) call.method)); // PendingIntent scan: accepted but silent.
            }
        });
        Hooks.all(BluetoothLeScanner.class, "flushPendingScanResults", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (active() && scanAllowed()) call.setResult(null);
            }
        });
        advertiser.installStopHooks();
    }
    private static Object accepted(Method method) {
        return ((Method) method).getReturnType() == int.class ? 0 : null;
    }
    private static Executor executor(Object[] args) {
        Executor explicit = LocationHooks.find(args, Executor.class);
        if (explicit != null) return explicit;
        Handler handler = LocationHooks.find(args, Handler.class);
        if (handler != null) return task -> handler.post(task);
        Looper looper = Looper.myLooper();
        Handler fallback = new Handler(looper != null ? looper : Looper.getMainLooper());
        return task -> fallback.post(task);
    }
    private static ScanFilter[] filters(Object[] args) {
        for (Object arg : args) {
            if (!(arg instanceof List)) continue;
            List<?> list = (List<?>) arg;
            if (!list.isEmpty() && !(list.get(0) instanceof ScanFilter)) continue;
            return list.toArray(new ScanFilter[0]);
        }
        return new ScanFilter[0];
    }
    private boolean scanAllowed() {
        return Build.VERSION.SDK_INT >= 31 ? config.permission(Manifest.permission.BLUETOOTH_SCAN)
                : config.permission(Manifest.permission.BLUETOOTH_ADMIN) && config.locationAllowed();
    }
    /**
     * Delivers captured advertisers to every active LE scan callback. The cadence follows the
     * scanner's own ScanSettings (1 s low-latency, 2 s balanced, 5 s low-power), so a background
     * attendance SDK does not get a continuous 1 Hz timer.
     */
    private static final class Advertiser {
        private final RuntimeConfig config;
        private final Handler timer;
        private final Map<ScanCallback, Subscription> subscribers = new IdentityHashMap<>();
        Advertiser(RuntimeConfig config) {
            this.config = config;
            HandlerThread worker = new HandlerThread("EnvSim-ble"); worker.start(); timer = new Handler(worker.getLooper());
        }
        void installStopHooks() {
            Hooks.all(BluetoothLeScanner.class, "stopScan", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    ScanCallback callback = LocationHooks.find(call.args, ScanCallback.class);
                    if (callback != null) unsubscribe(callback);
                }
            });
        }
        synchronized boolean subscribe(ScanCallback callback, Executor executor, ScanFilter[] filters, ScanSettings settings) {
            if (points(config.get()).isEmpty()) return false;
            boolean first = subscribers.isEmpty();
            subscribers.put(callback, new Subscription(executor, filters, settings));
            if (first) timer.post(pump);
            return true;
        }
        synchronized void unsubscribe(ScanCallback callback) { subscribers.remove(callback); }
        private final Runnable pump = new Runnable() {
            @Override public void run() {
                Map<ScanCallback, Subscription> current;
                long now = SystemClock.elapsedRealtime();
                synchronized (Advertiser.this) {
                    if (subscribers.isEmpty()) return;
                    current = new IdentityHashMap<>(subscribers);
                }
                Profile profile = config.get();
                List<BleFingerprint> points = points(profile);
                if (profile.enabled && profile.bluetooth && !points.isEmpty())
                    for (Map.Entry<ScanCallback, Subscription> entry : current.entrySet()) {
                        Subscription subscription = entry.getValue();
                        if (now < subscription.next) continue;
                        subscription.next = now + subscription.interval;
                        List<ScanResult> batch = new ArrayList<>();
                        for (BleFingerprint point : points) {
                            if (!matches(subscription.filters, point)) continue;
                            if (subscription.firstOnly && subscription.delivered.contains(point.address)) continue;
                            ScanResult result = result(point, now);
                            if (result == null) continue;
                            subscription.delivered.add(point.address);
                            batch.add(result);
                        }
                        if (!batch.isEmpty()) deliver(entry.getKey(), subscription, batch);
                    }
                timer.postDelayed(this, 1000);
            }
        };
        private void deliver(ScanCallback callback, Subscription subscription, List<ScanResult> batch) {
            if (subscription.batch) {
                List<ScanResult> copy = new ArrayList<>(batch);
                subscription.executor.execute(() -> {
                    try { callback.onBatchScanResults(copy); }
                    catch (Throwable failure) { XposedBridge.log("EnvSim: BLE batch rejected: " + failure); }
                });
                return;
            }
            for (ScanResult result : batch) subscription.executor.execute(() -> {
                try { callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result); }
                catch (Throwable failure) { XposedBridge.log("EnvSim: BLE callback rejected: " + failure); }
            });
        }
        private List<BleFingerprint> points(Profile profile) {
            if (!profile.blePoints.isEmpty()) return profile.blePoints;
            if (!profile.beaconEnabled()) return Collections.emptyList();
            try {
                byte[] raw = BeaconRecord.advertising(profile);
                if (raw.length == 0) return Collections.emptyList();
                return Collections.singletonList(new BleFingerprint(profile.beaconMac, profile.beaconName, -60,
                        BleFingerprint.hex(raw)));
            } catch (RuntimeException invalid) { return Collections.emptyList(); }
        }
        private ScanResult result(BleFingerprint point, long now) {
            try {
                BluetoothAdapter adapter = ((BluetoothManager) config.context.getSystemService(BluetoothManager.class)).getAdapter();
                if (adapter == null) return null;
                byte[] raw = point.payload.isEmpty() ? BeaconRecord.named(point.name) : BleFingerprint.bytes(point.payload);
                if (raw.length == 0) return null;
                BluetoothDevice device = adapter.getRemoteDevice(point.address);
                ScanRecord record = (ScanRecord) BeaconRecord.record(raw);
                int rssi = WifiScanFactory.signal(now, point.rssi, point.phase());
                ScanResult result = (ScanResult) XposedHelpers.newInstance(ScanResult.class, device, record,
                        rssi, SystemClock.elapsedRealtimeNanos());
                try { XposedHelpers.setBooleanField(result, "mIsConnectable", false); }
                catch (Throwable absentOnVersion) { /* Optional field. */ }
                return result;
            } catch (Throwable unavailable) {
                XposedBridge.log("EnvSim: BLE frame unavailable: " + unavailable);
                return null;
            }
        }
        private static boolean matches(ScanFilter[] filters, BleFingerprint point) {
            if (filters == null || filters.length == 0) return true;
            ScanRecord record = null;
            String name = point.name;
            try {
                byte[] raw = point.payload.isEmpty() ? BeaconRecord.named(point.name) : BleFingerprint.bytes(point.payload);
                record = (ScanRecord) BeaconRecord.record(raw);
                if (record.getDeviceName() != null) name = record.getDeviceName();
            } catch (Throwable unavailable) { /* Address-only filters still work. */ }
            for (ScanFilter filter : filters) if (matches(filter, point, name, record)) return true;
            return false;
        }
        private static boolean matches(ScanFilter filter, BleFingerprint point, String name, ScanRecord record) {
            if (filter == null) return true;
            String address = filter.getDeviceAddress();
            if (address != null && !address.isEmpty() && !address.equalsIgnoreCase(point.address)) return false;
            String deviceName = filter.getDeviceName();
            if (deviceName != null && !deviceName.isEmpty() && !deviceName.equals(name)) return false;
            int manufacturer = filter.getManufacturerId();
            if (manufacturer >= 0) {
                byte[] data = record == null ? null : record.getManufacturerSpecificData(manufacturer);
                if (data == null) return false;
                byte[] expected = filter.getManufacturerData();
                byte[] mask = filter.getManufacturerDataMask();
                if (expected != null && !masked(data, expected, mask)) return false;
            }
            ParcelUuid service = filter.getServiceUuid();
            if (service != null) {
                List<ParcelUuid> advertised = record == null ? null : record.getServiceUuids();
                boolean found = false;
                if (advertised != null) for (ParcelUuid candidate : advertised)
                    if (uuid(service, filter.getServiceUuidMask(), candidate)) { found = true; break; }
                if (!found) return false;
            }
            return true;
        }
        private static boolean masked(byte[] actual, byte[] expected, byte[] mask) {
            if (actual.length != expected.length) return false;
            int length = mask == null ? actual.length : Math.min(actual.length, mask.length);
            for (int i = 0; i < length; i++) {
                int bits = mask == null ? 0xFF : mask[i] & 0xFF;
                if ((actual[i] & bits) != (expected[i] & bits)) return false;
            }
            return true;
        }
        private static boolean uuid(ParcelUuid filter, ParcelUuid mask, ParcelUuid candidate) {
            byte[] expected = bytes(filter.getUuid());
            byte[] actual = bytes(candidate.getUuid());
            byte[] bits = mask == null ? null : bytes(mask.getUuid());
            for (int i = 0; i < 16; i++) {
                int allowed = bits == null ? 0xFF : bits[i] & 0xFF;
                if ((expected[i] & allowed) != (actual[i] & allowed)) return false;
            }
            return true;
        }
        private static byte[] bytes(java.util.UUID uuid) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
            return buffer.array();
        }
        private static final class Subscription {
            final Executor executor;
            final ScanFilter[] filters;
            final boolean batch, firstOnly;
            final long interval;
            final Set<String> delivered = new HashSet<>();
            long next;
            Subscription(Executor executor, ScanFilter[] filters, ScanSettings settings) {
                this.executor = executor;
                this.filters = filters;
                boolean batched = settings != null && settings.getReportDelayMillis() > 0;
                this.batch = batched;
                int callbackType = settings == null ? ScanSettings.CALLBACK_TYPE_ALL_MATCHES : settings.getCallbackType();
                this.firstOnly = callbackType == ScanSettings.CALLBACK_TYPE_FIRST_MATCH;
                int mode = settings == null ? ScanSettings.SCAN_MODE_LOW_LATENCY : settings.getScanMode();
                this.interval = mode == ScanSettings.SCAN_MODE_LOW_POWER ? 5000
                        : mode == ScanSettings.SCAN_MODE_BALANCED ? 2000 : 1000;
            }
        }
    }
}
