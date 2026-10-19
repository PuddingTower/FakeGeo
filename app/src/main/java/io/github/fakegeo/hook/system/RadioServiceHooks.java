package io.github.fakegeo.hook.system;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import io.github.fakegeo.config.Profile;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Hooks service implementations, not app-side WifiManager/TelephonyManager getters. */
public final class RadioServiceHooks {
    private final ServiceConfig config;
    private final ClassLoader loader;
    private final List<String> coverage, errors;
    private final Set<Method> installed = Collections.synchronizedSet(new HashSet<>());
    private final Set<ClassLoader> wifiLoaders = Collections.synchronizedSet(new HashSet<>());
    private final ConnectivityHooks connectivity;
    public RadioServiceHooks(ServiceConfig config, ClassLoader loader, List<String> coverage, List<String> errors) {
        this.config = config; this.loader = loader; this.coverage = coverage; this.errors = errors;
        connectivity = new ConnectivityHooks(config, coverage, errors);
    }
    private boolean cell() { return config.active() && config.get().cell; }
    private boolean wifi() { return config.active() && config.get().wifi; }
    private boolean bluetooth() { return config.active() && config.get().bluetooth; }
    public void installFramework() {
        installWifiParcel();
        installWifi(loader);
        connectivity.install(loader);
        Class<?> manager = find("com.android.server.SystemServiceManager", loader);
        hook(manager, "startService", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!call.hasThrowable() && call.getResult() != null) {
                    ClassLoader serviceLoader = call.getResult().getClass().getClassLoader();
                    installWifi(serviceLoader); connectivity.install(serviceLoader);
                }
            }
        });
        Class<?> registry = find("com.android.server.TelephonyRegistry", loader);
        for (String method : new String[]{"notifyCellInfo", "notifyCellInfoForSubscriber", "notifyCellLocation", "notifyCellLocationForSubscriber"})
            hook(registry, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!cell()) return;
                    for (int i = 0; i < call.args.length; i++) {
                        Object arg = call.args[i];
                        if (arg instanceof List && method.contains("CellInfo")) call.args[i] = new ArrayList<>();
                        else if (arg != null && arg.getClass().getName().startsWith("android.telephony.CellIdentity")) {
                            try { call.args[i] = XposedHelpers.newInstance(arg.getClass()); }
                            catch (Throwable failure) { error("cell identity", failure); }
                        } else if (arg instanceof android.os.Bundle && method.contains("CellLocation")) {
                            android.os.Bundle empty = new android.os.Bundle();
                            empty.putInt("lac", -1); empty.putInt("cid", -1); empty.putInt("psc", -1);
                            empty.putInt("baseStationId", -1); empty.putInt("baseStationLatitude", Integer.MAX_VALUE);
                            empty.putInt("baseStationLongitude", Integer.MAX_VALUE); empty.putInt("systemId", -1); empty.putInt("networkId", -1);
                            call.args[i] = empty;
                        }
                    }
                }
            });
        Class<?> btManager = find("com.android.server.BluetoothManagerService", loader);
        after(btManager, "getAddress", this::bluetooth, value -> config.get().bluetoothMac);
        after(btManager, "getName", this::bluetooth, value -> config.get().bluetoothName);
        suppressGnss(loader);
    }
    public void installPhone() {
        Class<?> phone = find("com.android.phone.PhoneInterfaceManager", loader);
        for (String method : new String[]{"getAllCellInfo", "getNeighboringCellInfo"})
            afterTyped(phone, method, this::cell, (declared, value) -> value == null ? null : ReturnValues.listResult(declared.getReturnType(), Collections.emptyList()));
        after(phone, "getCellLocation", this::cell, value -> {
            if (value instanceof android.os.Bundle) {
                android.os.Bundle bundle = new android.os.Bundle(); bundle.putInt("lac", -1); bundle.putInt("cid", -1); bundle.putInt("psc", -1); return bundle;
            }
            return value == null ? null : XposedHelpers.newInstance(value.getClass());
        });
        for (String name : new String[]{"requestCellInfoUpdate", "requestCellInfoUpdateWithWorkSource"}) hook(phone, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (!cell() || !CallerPolicy.application(android.os.Binder.getCallingUid())) return;
                Class<?> callbackType = find("android.telephony.ICellInfoCallback", loader);
                if (callbackType == null) return;
                for (int i = 0; i < call.args.length; i++) if (callbackType.isInstance(call.args[i])) {
                    Object delegate = call.args[i];
                    call.args[i] = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, args) -> {
                        Object[] forwarded = args == null ? null : args.clone();
                        if (cell() && method.getName().equals("onCellInfo") && forwarded != null && forwarded.length > 0)
                            forwarded[0] = new ArrayList<>();
                        try { return method.invoke(delegate, forwarded); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
            }
        });
    }
    public void installWifi(ClassLoader classLoader) {
        if (!wifiLoaders.add(classLoader)) return;
        installWifiParcel();
        Class<?> wifiService = find("com.android.server.wifi.WifiServiceImpl", classLoader);
        after(wifiService, "getConnectionInfo", this::wifi, value -> value instanceof WifiInfo ? cleanWifi((WifiInfo) value, config.get()) : value);
        afterTyped(wifiService, "getScanResults", this::wifi, (declared, value) -> value == null ? null : ReturnValues.listResult(declared.getReturnType(), scans(config.get())));
        for (String name : new String[]{"getConfiguredNetworks", "getPrivilegedConfiguredNetworks"})
            afterTyped(wifiService, name, this::wifi, (declared, value) -> value == null ? null : ReturnValues.listResult(declared.getReturnType(), Collections.emptyList()));
    }
    public void installBluetooth() {
        Class<?> adapter = find("com.android.bluetooth.btservice.AdapterService", loader);
        Class<?> binder = find("com.android.bluetooth.btservice.AdapterService$AdapterServiceBinder", loader);
        // Hook binder-facing methods; keep AdapterProperties and the controller's real identities intact.
        for (String method : new String[]{"getAddress", "getAddressWithAttribution"})
            after(binder, method, this::bluetooth, value -> config.get().bluetoothMac);
        for (String method : new String[]{"getName", "getNameWithAttribution"})
            after(binder, method, this::bluetooth, value -> config.get().bluetoothName);
        after(binder, "getBondedDevices", this::bluetooth, ReturnValues::emptyDevices);
        if (binder == null) after(adapter, "getBondedDevices", this::bluetooth, ReturnValues::emptyDevices);
        Class<?> gatt = find("com.android.bluetooth.gatt.GattService", loader);
        for (String method : new String[]{"onScanResult", "onScanResultInternal", "onTrackAdvFoundLost", "onPeriodicAdvertisingReport"})
            hook(gatt, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (bluetooth() && ((Method) call.method).getReturnType() == void.class) call.setResult(null);
                }
            });
        for (String name : new String[]{"onBatchScanReports", "onBatchScanReportsInternal"}) hook(gatt, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                // Keep the scan manager's completion bookkeeping while returning an empty batch.
                if (bluetooth() && call.args.length == 5 && call.args[3] instanceof Integer && call.args[4] instanceof byte[]) {
                    call.args[3] = 0; call.args[4] = new byte[0];
                }
            }
        });
        for (String name : new String[]{"com.android.bluetooth.gatt.PeriodicScanManager", "com.android.bluetooth.le_scan.PeriodicScanManager"})
            hook(find(name, loader), "onSyncReport", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) { if (bluetooth() && ((Method) call.method).getReturnType() == void.class) call.setResult(null); }
            });
        Class<?> properties = find("com.android.bluetooth.btservice.RemoteDevices$DeviceProperties", loader);
        hook(properties, "sendDeviceFoundIntent", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) { if (bluetooth() && ((Method) call.method).getReturnType() == void.class) call.setResult(null); }
        });
        // Other releases publish ACTION_FOUND directly in RemoteDevices.deviceFoundCallback.
        hook(find("com.android.bluetooth.btservice.RemoteDevices", loader), "deviceFoundCallback", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) { if (bluetooth() && ((Method) call.method).getReturnType() == void.class) call.setResult(null); }
        });
    }
    private void suppressGnss(ClassLoader classLoader) {
        for (String name : new String[]{"com.android.server.location.gnss.GnssNmeaProvider", "com.android.server.location.gnss.GnssMeasurementsProvider",
                "com.android.server.location.gnss.GnssNavigationMessageProvider", "com.android.server.location.GnssNmeaProvider",
                "com.android.server.location.GnssMeasurementsProvider", "com.android.server.location.GnssNavigationMessageProvider"}) {
            Class<?> type = find(name, classLoader);
            for (String method : new String[]{"onReportNmea", "onReportMeasurements", "onReportNavigationMessage",
                    "onNmeaReceived", "onMeasurementsAvailable", "onNavigationMessageAvailable"}) hook(type, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (config.active() && config.get().location && ((Method) call.method).getReturnType() == void.class) call.setResult(null);
                }
            });
        }
    }
    private void installWifiParcel() {
        // Sanitize app-initiated Binder replies without mutating controller state.
        // System-originated asynchronous callbacks need recipient-aware filtering.
        hook(WifiInfo.class, "writeToParcel", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (!wifi() || !CallerPolicy.application(android.os.Binder.getCallingUid())) return;
                try {
                    WifiInfo copy = cleanWifi((WifiInfo) call.thisObject, config.get());
                    Object result = XposedBridge.invokeOriginalMethod(call.method, copy, call.args);
                    call.setResult(result);
                } catch (Throwable failure) { error("WifiInfo parcel", failure); }
            }
        });
    }
    private Class<?> find(String name, ClassLoader classLoader) {
        try { return XposedHelpers.findClassIfExists(name, classLoader); }
        catch (Throwable failure) { error(name, failure); return null; }
    }
    private void after(Class<?> type, String method, BooleanSupplier active, Function<Object, Object> transform) {
        afterTyped(type, method, active, (declared, value) -> transform.apply(value));
    }
    private interface ResultTransform { Object apply(Method declared, Object value) throws ReflectiveOperationException; }
    private void afterTyped(Class<?> type, String method, BooleanSupplier active, ResultTransform transform) {
        hook(type, method, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                Method declared = (Method) call.method;
                if (!active.getAsBoolean() || call.hasThrowable() || declared.getReturnType() == void.class
                        || !CallerPolicy.application(android.os.Binder.getCallingUid())) return;
                try {
                    Object replacement = transform.apply(declared, call.getResult());
                    if (!ReturnValues.accepts(declared.getReturnType(), replacement)) {
                        error(method, new IllegalArgumentException("Expected " + declared.getReturnType().getName()
                                + ", got " + (replacement == null ? "null" : replacement.getClass().getName())));
                        return;
                    }
                    call.setResult(replacement);
                }
                catch (Throwable failure) { error(method, failure); }
            }
        });
    }
    private void hook(Class<?> type, String name, XC_MethodHook hook) {
        if (type == null) return;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || Modifier.isAbstract(method.getModifiers()) || !installed.add(method)) continue;
            try { XposedBridge.hookMethod(method, hook); coverage.add(type.getName() + "." + name + " -> " + method.getReturnType().getName()); }
            catch (Throwable failure) { error(type.getName() + "." + name, failure); }
        }
    }
    private void error(String stage, Throwable failure) {
        String message = stage + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: radio " + message); }
    }
    private static Object ssid(Profile p) {
        Class<?> type = XposedHelpers.findClass("android.net.wifi.WifiSsid", null);
        byte[] bytes = p.ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { return XposedHelpers.callStaticMethod(type, "fromBytes", bytes); }
        catch (Throwable old) { return XposedHelpers.callStaticMethod(type, "createFromByteArray", bytes); }
    }
    private static WifiInfo cleanWifi(WifiInfo source, Profile p) {
        try { return WifiIdentity.copy(source, p); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Wi-Fi identity copy failed", failure); }
    }
    public boolean connectivityProtected() { return connectivity.protectedRecipients(); }
    public long connectivityReplacements() { return connectivity.replacements(); }
    public long connectivityFallbacks() { return connectivity.fallbacks(); }
    private static List<ScanResult> scans(Profile p) {
        ScanResult scan = (ScanResult) XposedHelpers.newInstance(ScanResult.class);
        scan.SSID = p.ssid; scan.BSSID = p.bssid; scan.level = WifiIdentity.signal(android.os.SystemClock.elapsedRealtime());
        scan.frequency = 2412;
        scan.capabilities = "[WPA2-PSK-CCMP][ESS]"; scan.timestamp = android.os.SystemClock.elapsedRealtimeNanos() / 1000;
        XposedHelpers.setObjectField(scan, "wifiSsid", ssid(p));
        return new ArrayList<>(Collections.singletonList(scan));
    }
}
