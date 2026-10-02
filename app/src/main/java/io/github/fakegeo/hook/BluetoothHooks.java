package io.github.fakegeo.hook;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.os.Build;
import java.util.HashSet;
import io.github.fakegeo.config.Profile;
import de.robv.android.xposed.XC_MethodHook;
import java.lang.reflect.Method;

final class BluetoothHooks {
    private final RuntimeConfig config;
    BluetoothHooks(RuntimeConfig config) { this.config = config; }
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
        for (String name : new String[]{"startScan", "flushPendingScanResults"})
            Hooks.all(BluetoothLeScanner.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active() || !scanAllowed()) return;
                    if (((Method) call.method).getReturnType() == int.class) call.setResult(0);
                    else call.setResult(null); // An accepted empty scan; no real radio request or callback.
                }
            });
    }
    private boolean scanAllowed() {
        return Build.VERSION.SDK_INT >= 31 ? config.permission(Manifest.permission.BLUETOOTH_SCAN)
                : config.permission(Manifest.permission.BLUETOOTH_ADMIN) && config.locationAllowed();
    }
}
