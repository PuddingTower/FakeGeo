package io.github.fakegeo.hook;

import android.telephony.TelephonyManager;
import android.telephony.CellLocation;
import android.telephony.CellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.os.Build;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.cdma.CdmaCellLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import io.github.fakegeo.config.Profile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.HashSet;
import java.util.Set;

final class CellHooks {
    private final RuntimeConfig config;
    private final Set<Class<?>> callbackTypes = new HashSet<>();
    private final ThreadLocal<Boolean> original = ThreadLocal.withInitial(() -> false);
    CellHooks(RuntimeConfig config) { this.config = config; }
    private boolean active() { Profile p = config.get(); return p.enabled && p.cell; }
    void install() {
        Hooks.all(TelephonyManager.class, "listen", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                PhoneStateListener listener = LocationHooks.find(call.args, PhoneStateListener.class);
                if (listener != null) hookCallback(listener.getClass());
            }
        });
        if (Build.VERSION.SDK_INT >= 31) Hooks.all(TelephonyManager.class, "registerTelephonyCallback", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                TelephonyCallback callback = LocationHooks.find(call.args, TelephonyCallback.class);
                if (callback != null) hookCallback(callback.getClass());
            }
        });
        for (String name : new String[]{"getAllCellInfo", "getNeighboringCellInfo"})
            Hooks.all(TelephonyManager.class, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (!active() || call.hasThrowable()) return;
                    if (name.equals("getAllCellInfo")) call.setResult(injected(call.getResult()));
                    else call.setResult(new ArrayList<>());
                }
            });
        Hooks.all(TelephonyManager.class, "getCellLocation", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable()) return;
                Profile profile = config.get();
                CellLocation fake = profile.hasCellEnvironment() ? CellFactory.legacyLocation(profile) : null;
                call.setResult(fake != null ? fake : CellLocation.getEmpty());
            }
        });
        Hooks.all(TelephonyManager.class, "requestCellInfoUpdate", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (!active() || !config.locationAllowed()) return;
                Executor executor = LocationHooks.find(call.args, Executor.class);
                TelephonyManager.CellInfoCallback callback = LocationHooks.find(call.args, TelephonyManager.CellInfoCallback.class);
                if (executor != null && callback != null) {
                    executor.execute(() -> callback.onCellInfo(injected(null)));
                    call.setResult(null);
                }
            }
        });
        // Listener callbacks can deliver CellInfo without using getAllCellInfo; sanitize legacy identities too.
        sentinel(GsmCellLocation.class, "getLac", -1); sentinel(GsmCellLocation.class, "getCid", -1);
        sentinel(GsmCellLocation.class, "getPsc", -1);
        sentinel(CdmaCellLocation.class, "getBaseStationId", -1);
        sentinel(CdmaCellLocation.class, "getBaseStationLatitude", Integer.MAX_VALUE);
        sentinel(CdmaCellLocation.class, "getBaseStationLongitude", Integer.MAX_VALUE);
        sentinel(CdmaCellLocation.class, "getSystemId", -1); sentinel(CdmaCellLocation.class, "getNetworkId", -1);
        // The exact technology is retained, but geographic identity getters become unavailable.
        for (String technology : new String[]{"Gsm", "Cdma", "Lte", "Wcdma", "Tdscdma", "Nr"}) {
            Class<?> type;
            try { type = Class.forName("android.telephony.CellIdentity" + technology); }
            catch (ClassNotFoundException absent) { continue; }
            for (String getter : new String[]{"getCid", "getLac", "getTac", "getCi", "getPci", "getPsc",
                    "getBasestationId", "getNetworkId", "getSystemId", "getLatitude", "getLongitude", "getMcc", "getMnc"})
                sentinel(type, getter, Integer.MAX_VALUE);
            sentinel(type, "getNci", Long.MAX_VALUE);
            sentinel(type, "getMccString", null); sentinel(type, "getMncString", null);
            sentinel(type, "getMobileNetworkOperator", null);
            for (String name : new String[]{"writeToParcel", "toString"}) Hooks.all(type, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) throws Throwable {
                    if (!active() || original.get() || config.get().hasCellEnvironment()) return;
                    original.set(true);
                    try {
                        Object empty = XposedHelpers.newInstance(call.thisObject.getClass());
                        call.setResult(XposedBridge.invokeOriginalMethod(call.method, empty, call.args));
                    } finally { original.set(false); }
                }
            });
        }
    }
    private synchronized void hookCallback(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (!callbackTypes.add(current)) continue;
            for (String name : new String[]{"onCellInfoChanged", "onCellLocationChanged"}) Hooks.all(current, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active() || call.args.length == 0) return;
                    if (name.equals("onCellInfoChanged")) call.args[0] = injected(call.args[0]);
                    else if (call.args[0] instanceof CellLocation) {
                        Profile profile = config.get();
                        CellLocation fake = profile.hasCellEnvironment() ? CellFactory.legacyLocation(profile) : null;
                        call.args[0] = fake != null ? fake : CellLocation.getEmpty();
                    } else if (call.args[0] != null) call.args[0] = XposedHelpers.newInstance(call.args[0].getClass());
                }
            });
        }
    }
    private void sentinel(Class<?> type, String name, Object value) {
        Hooks.all(type, name, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (active() && !original.get() && !call.hasThrowable() && !config.get().hasCellEnvironment())
                    call.setResult(value);
            }
        });
    }
    @SuppressWarnings("unchecked")
    private List<CellInfo> injected(Object real) {
        Profile profile = config.get();
        return CellFactory.build(real instanceof List ? (List<CellInfo>) real : null, profile);
    }
}
