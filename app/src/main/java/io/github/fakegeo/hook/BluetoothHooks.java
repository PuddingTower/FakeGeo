package io.github.fakegeo.hook;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Executor;
import io.github.fakegeo.config.Profile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class BluetoothHooks {
    private final RuntimeConfig config;
    private final Beacon beacon;
    BluetoothHooks(RuntimeConfig config) { this.config = config; beacon = new Beacon(config); }
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
        // Do not enumerate nearby devices: inventing MACs for live peripherals breaks connections.
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
                if (callback != null && config.get().beaconEnabled() && beacon.subscribe(callback, executor(call.args))) {
                    call.setResult(((Method) call.method).getReturnType() == int.class ? 0 : null);
                    return; // The attendance beacon replaces the radio scan for this callback.
                }
                if (((Method) call.method).getReturnType() == int.class) call.setResult(0);
                else call.setResult(null); // An accepted empty scan; no real radio request or callback.
            }
        });
        Hooks.all(BluetoothLeScanner.class, "flushPendingScanResults", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (active() && scanAllowed()) call.setResult(null);
            }
        });
        beacon.installStopHooks();
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
    private boolean scanAllowed() {
        return Build.VERSION.SDK_INT >= 31 ? config.permission(Manifest.permission.BLUETOOTH_SCAN)
                : config.permission(Manifest.permission.BLUETOOTH_ADMIN) && config.locationAllowed();
    }
    /**
     * Delivers the configured attendance beacon frame to each registered callback. One frame per
     * second matches a real 1 Hz advertiser; a faster repetition is itself an anomaly the SDKs
     * de-duplicate.
     */
    private static final class Beacon {
        private static final int ALL_MATCHED = 1;
        private final RuntimeConfig config;
        private final Handler timer;
        private final Map<ScanCallback, Executor> subscribers = new IdentityHashMap<>();
        Beacon(RuntimeConfig config) {
            this.config = config;
            HandlerThread worker = new HandlerThread("FakeGeo-beacon"); worker.start(); timer = new Handler(worker.getLooper());
        }
        void installStopHooks() {
            Hooks.all(BluetoothLeScanner.class, "stopScan", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    ScanCallback callback = LocationHooks.find(call.args, ScanCallback.class);
                    if (callback != null) unsubscribe(callback);
                    // The original still runs so the scanner drops its own registration.
                }
            });
        }
        synchronized boolean subscribe(ScanCallback callback, Executor executor) {
            // Only accept the scan when a frame can actually be built, so the caller is never left waiting.
            if (result(config.get()) == null) return false;
            boolean first = subscribers.isEmpty();
            subscribers.put(callback, executor);
            if (first) timer.post(pump);
            return true;
        }
        synchronized void unsubscribe(ScanCallback callback) { subscribers.remove(callback); }
        private final Runnable pump = new Runnable() {
            @Override public void run() {
                Map<ScanCallback, Executor> current;
                synchronized (Beacon.this) {
                    if (subscribers.isEmpty()) return;
                    current = new IdentityHashMap<>(subscribers);
                }
                ScanResult frame = result(config.get());
                if (frame != null) for (Map.Entry<ScanCallback, Executor> entry : current.entrySet()) {
                    ScanResult delivered = frame;
                    entry.getValue().execute(() -> {
                        try { entry.getKey().onScanResult(ALL_MATCHED, delivered); }
                        catch (Throwable failure) { XposedBridge.log("FakeGeo: beacon callback rejected: " + failure); }
                    });
                }
                timer.postDelayed(this, 1000);
            }
        };
        private ScanResult result(Profile p) {
            if (!p.beaconEnabled()) return null;
            try {
                BluetoothAdapter adapter = ((BluetoothManager) config.context.getSystemService(BluetoothManager.class)).getAdapter();
                if (adapter == null) return null;
                BluetoothDevice device = adapter.getRemoteDevice(p.beaconMac);
                double t = SystemClock.elapsedRealtime() / 1000.0;
                return (ScanResult) XposedHelpers.newInstance(ScanResult.class, device, BeaconRecord.record(BeaconRecord.advertising(p)),
                        -60 + (int) Math.round(6 * Math.abs(Math.sin(t / 3.0))), SystemClock.elapsedRealtimeNanos());
            } catch (Throwable unavailable) {
                XposedBridge.log("FakeGeo: beacon delivery unavailable: " + unavailable);
                return null;
            }
        }
    }
}
