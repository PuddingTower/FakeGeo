package io.github.fakegeo.hook.system;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.config.ServicePackages;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Answers configuration reads on the location service's own Binder, so a scoped application never
 * has to resolve the module's ContentProvider across a package-visibility or app-clone boundary.
 * Only caller UIDs that own a package named in the published allowlist receive a reply.
 */
public final class ConfigBinderBridge extends XC_MethodHook {
    public static final int TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1000;
    public static final int MAGIC = 0x46474731; // "FGG1"
    public static final int KIND_PROFILE = 0, KIND_SENSORS = 1;
    private static final long CACHE_MS = 3000;
    private final ServiceConfig config;
    private final List<String> coverage, errors;
    private final Map<Integer, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong served = new AtomicLong(), denied = new AtomicLong();

    private ConfigBinderBridge(ServiceConfig config, List<String> coverage, List<String> errors) {
        this.config = config; this.coverage = coverage; this.errors = errors;
    }
    /** Hooks the generated AIDL stub, or the service class itself when a vendor build renamed it. */
    public static ConfigBinderBridge install(ServiceConfig config, ClassLoader loader, List<String> coverage, List<String> errors) {
        ConfigBinderBridge bridge = new ConfigBinderBridge(config, coverage, errors);
        for (String name : new String[]{"android.location.ILocationManager$Stub",
                "com.android.server.location.LocationManagerService", "com.android.server.LocationManagerService"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            Method onTransact = type == null ? null : bridge.transact(type);
            if (onTransact == null) continue;
            try {
                XposedBridge.hookMethod(onTransact, bridge);
                coverage.add(type.getName() + ".onTransact(config bridge)");
                return bridge;
            } catch (Throwable failure) { bridge.error("install " + type.getName(), failure); }
        }
        return bridge;
    }
    public long served() { return served.get(); }
    public long deniedCount() { return denied.get(); }
    @Override protected void beforeHookedMethod(MethodHookParam call) {
        if (call.args.length < 4 || !(call.args[0] instanceof Integer) || ((Integer) call.args[0]) != TRANSACTION) return;
        Parcel data = (Parcel) call.args[1], reply = (Parcel) call.args[2];
        if (data == null || reply == null) { call.setResult(false); return; }
        int start = data.dataPosition();
        boolean ours = false;
        try {
            if (data.dataAvail() < 8) { call.setResult(false); return; }
            data.setDataPosition(0);
            ours = data.readInt() == MAGIC;
            if (!ours) return;
            int kind = data.readInt();
            Profile published = config.get();
            Set<String> allowlist = kind == KIND_SENSORS ? published.sensorTargets : published.targets;
            boolean authorized = published.enabled && ownsAny(Binder.getCallingUid(), allowlist);
            // Always consume the transaction and report the verdict; the client falls back to the
            // ContentProvider when it is not listed, which keeps the draft/publish edge case working.
            call.setResult(true);
            reply.setDataPosition(0);
            reply.writeInt(authorized ? 1 : 0);
            if (authorized) reply.writeString(published.json().toString());
            if (authorized) served.incrementAndGet(); else denied.incrementAndGet();
        } catch (Throwable failure) {
            call.setResult(false);
            error("reply", failure);
        } finally {
            // A transaction that is not ours must reach the real stub exactly as it arrived.
            if (!ours && start >= 0) try { data.setDataPosition(start); } catch (Throwable ignored) {}
        }
    }
    private Method transact(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class);
                method.setAccessible(true); return method;
            } catch (NoSuchMethodException absent) { /* Keep walking to the generated AIDL stub. */ }
        }
        return null;
    }
    /** Resolves the caller's packages the way the ContentProvider does, with a short cache. */
    private boolean ownsAny(int uid, Set<String> allowlist) {
        if (allowlist.isEmpty() || !CallerPolicy.application(uid)) return false;
        Entry cached = cache.compute(uid, (key, previous) ->
                previous != null && previous.expires > System.currentTimeMillis() ? previous : new Entry(packages(uid)));
        for (String name : cached.packages)
            if (allowlist.contains(name) || ServicePackages.NAMES.contains(name) || ServicePackages.MODULE_NAMES.contains(name)) return true;
        return false;
    }
    private static String[] packages(int uid) {
        try {
            Object application = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
            if (!(application instanceof Context)) return new String[0];
            String[] names = ((Context) application).getPackageManager().getPackagesForUid(uid);
            return names == null ? new String[0] : names;
        } catch (Throwable unavailable) { return new String[0]; }
    }
    private void error(String stage, Throwable failure) {
        String message = "config bridge " + stage + ": " + failure.getClass().getSimpleName();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: " + message); }
    }
    private static final class Entry {
        final String[] packages; final long expires = System.currentTimeMillis() + CACHE_MS;
        Entry(String[] packages) { this.packages = packages; }
    }
}
