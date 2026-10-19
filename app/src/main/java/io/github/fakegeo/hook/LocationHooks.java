package io.github.fakegeo.hook;

import android.app.PendingIntent;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.hook.system.GnssPayload;
import io.github.fakegeo.hook.system.SystemLocations;
import io.github.fakegeo.location.DriftModel;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class LocationHooks {
    private final RuntimeConfig config;
    private final Subscriptions subscriptions;
    private final AppGnssStatus gnss;
    private final ThreadLocal<Boolean> original = ThreadLocal.withInitial(() -> false);
    LocationHooks(RuntimeConfig config) {
        this.config = config; subscriptions = new Subscriptions(config); gnss = new AppGnssStatus(config);
    }
    private boolean active() { Profile p = config.get(); return p.enabled && p.location; }
    void install() {
        scalar("getLatitude", p -> sample(p).latitude); scalar("getLongitude", p -> sample(p).longitude);
        scalar("getAltitude", p -> sample(p).altitude); scalar("getAccuracy", p -> sample(p).accuracy);
        scalar("getSpeed", p -> 0f); scalar("getBearing", p -> 0f);
        scalar("getVerticalAccuracyMeters", p -> sample(p).accuracy);
        scalar("getSpeedAccuracyMetersPerSecond", p -> 0.2f); scalar("getBearingAccuracyDegrees", p -> 2f);
        scalar("hasAltitude", p -> true); scalar("hasAccuracy", p -> true);
        scalar("hasSpeed", p -> true); scalar("hasBearing", p -> true);
        scalar("hasVerticalAccuracy", p -> true); scalar("hasSpeedAccuracy", p -> true);
        scalar("hasBearingAccuracy", p -> true);
        scalar("hasMslAltitude", p -> false); scalar("hasMslAltitudeAccuracy", p -> false);
        scalar("hasElapsedRealtimeUncertaintyNanos", p -> true);
        scalar("getElapsedRealtimeUncertaintyNanos", p -> GnssPayload.current().uncertaintyNanos());
        // Attendance SDKs read these two flags; a real fix and a virtual one must look identical.
        scalar("isMock", p -> false); scalar("isFromMockProvider", p -> false);
        Hooks.all(Location.class, "getExtras", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (original.get() || !active()) return;
                String provider = ((Location) call.thisObject).getProvider();
                call.setResult(SystemLocations.metadata(config.get(), known(provider) ? provider : "gps"));
            }
        });
        // Serialization and string representations must use the same fields as getters.
        for (String name : new String[]{"writeToParcel", "toString"}) Hooks.all(Location.class, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) throws Throwable {
                if (original.get() || !active()) return;
                original.set(true);
                try {
                    Location clean = LocationFactory.copy((Location) call.thisObject, config.get());
                    call.setResult(XposedBridge.invokeOriginalMethod(call.method, clean, call.args));
                } finally { original.set(false); }
            }
        });
        Hooks.all(LocationManager.class, "getLastKnownLocation", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (active() && config.locationAllowed()) {
                    String provider = (String) call.args[0];
                    if (known(provider)) call.setResult(LocationFactory.create(config.get(), provider));
                }
            }
        });
        Hooks.all(LocationManager.class, "getCurrentLocation", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (!active() || !config.locationAllowed() || !known(provider(call.args))) return;
                Executor executor = find(call.args, Executor.class);
                Consumer<?> consumer = find(call.args, Consumer.class);
                CancellationSignal cancellation = find(call.args, CancellationSignal.class);
                if (executor == null || consumer == null) return;
                @SuppressWarnings("unchecked") Consumer<Location> callback = (Consumer<Location>) consumer;
                executor.execute(() -> {
                    if (cancellation != null && cancellation.isCanceled()) return;
                    if (active() && config.locationAllowed()) callback.accept(LocationFactory.create(config.get(), provider(call.args)));
                });
                call.setResult(null);
            }
        });
        for (String name : new String[]{"requestLocationUpdates", "requestSingleUpdate"})
            Hooks.all(LocationManager.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active() || !config.locationAllowed()) return;
                    String provider = provider(call.args);
                    if (!known(provider)) return;
                    LocationListener listener = find(call.args, LocationListener.class);
                    PendingIntent intent = find(call.args, PendingIntent.class);
                    if (listener == null && intent == null) return;
                    Executor executor = find(call.args, Executor.class);
                    Looper looper = find(call.args, Looper.class);
                    if (executor == null) {
                        Looper target = looper != null ? looper : Looper.myLooper();
                        if (target == null && intent == null) return; // Let Android raise its documented error.
                        Handler handler = new Handler(target != null ? target : Looper.getMainLooper());
                        executor = task -> handler.post(task);
                    }
                    long interval = config.get().interval, duration = Long.MAX_VALUE;
                    float distance = 0; int maxUpdates = name.equals("requestSingleUpdate") ? 1 : Integer.MAX_VALUE;
                    for (Object arg : call.args) {
                        if (arg instanceof Long) interval = Math.max(interval, (Long) arg);
                        if (arg instanceof Float) distance = (Float) arg;
                        if (arg != null && arg.getClass().getName().equals("android.location.LocationRequest")) {
                            interval = Math.max(interval, readLong(arg, "getIntervalMillis", interval));
                            duration = readLong(arg, "getDurationMillis", duration);
                            maxUpdates = (int) readLong(arg, "getMaxUpdates", maxUpdates);
                            try { distance = ((Number) XposedHelpers.callMethod(arg, "getMinUpdateDistanceMeters")).floatValue(); }
                            catch (Throwable ignored) {}
                        }
                    }
                    Object token = listener != null ? listener : intent;
                    subscriptions.register(token, provider, interval, distance, duration, maxUpdates, executor, location -> {
                        if (listener != null) listener.onLocationChanged(location);
                        else intent.send(config.context, 0, new Intent().putExtra(LocationManager.KEY_LOCATION_CHANGED, location));
                    });
                    call.setResult(null);
                }
            });
        Hooks.all(LocationManager.class, "removeUpdates", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (call.args.length > 0) subscriptions.remove(call.args[0]);
                // Also execute the original to remove any request predating module activation.
            }
        });
        // Advertise standard providers within the scoped process, without changing device settings.
        for (String name : new String[]{"isProviderEnabled", "isLocationEnabled", "getProviders", "getAllProviders", "getBestProvider"})
            Hooks.all(LocationManager.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active()) return;
                    switch (name) {
                        case "isLocationEnabled": call.setResult(true); break;
                        case "isProviderEnabled": if (call.args.length > 0 && known((String) call.args[0])) call.setResult(true); break;
                        case "getBestProvider": call.setResult("gps"); break;
                        default: call.setResult(new java.util.ArrayList<>(Arrays.asList("gps", "network", "passive", "fused")));
                    }
                }
            });
        // Raw pseudorange and NMEA stay unavailable; the virtual fix has no physical measurement behind it.
        for (String name : new String[]{"registerGnssMeasurementsCallback", "registerGnssNavigationMessageCallback",
                "addNmeaListener", "addGpsStatusListener"})
            Hooks.all(LocationManager.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (active()) call.setResult(false);
                }
            });
        // Satellite status is published instead of forced to zero, so accuracy and constellation agree.
        gnss.install();
    }
    interface Value { Object get(Profile p); }
    private static DriftModel.Sample sample(Profile p) {
        return DriftModel.sample(p, android.os.SystemClock.elapsedRealtime());
    }
    private void scalar(String name, Value value) {
        Hooks.all(Location.class, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (!original.get() && active()) call.setResult(value.get(config.get()));
            }
        });
    }
    static <T> T find(Object[] args, Class<T> type) {
        for (Object arg : args) if (type.isInstance(arg)) return type.cast(arg);
        return null;
    }
    static String provider(Object[] args) { return args.length > 0 && args[0] instanceof String ? (String) args[0] : "fused"; }
    static boolean known(String p) { return p != null && Arrays.asList("gps", "network", "passive", "fused").contains(p); }
    static long readLong(Object arg, String method, long fallback) {
        try { return ((Number) XposedHelpers.callMethod(arg, method)).longValue(); }
        catch (Throwable ignored) { return fallback; }
    }
}
