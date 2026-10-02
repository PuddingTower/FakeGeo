package io.github.fakegeo.hook;

import android.app.PendingIntent;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import io.github.fakegeo.config.Profile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** No GMS dependency in the module APK; resolve the target app's own SDK at runtime. */
final class GmsHooks {
    private final RuntimeConfig config;
    private final ClassLoader loader;
    private Subscriptions subscriptions;
    private final Set<Class<?>> hooked = new HashSet<>();
    GmsHooks(RuntimeConfig config, ClassLoader loader) { this.config = config; this.loader = loader; }
    private boolean active() { Profile p = config.get(); return p.enabled && p.location && config.locationAllowed(); }
    void install() {
        Class<?> services = XposedHelpers.findClassIfExists("com.google.android.gms.location.LocationServices", loader);
        if (services == null) return;
        subscriptions = new Subscriptions(config);
        Class<?> client = XposedHelpers.findClassIfExists("com.google.android.gms.location.FusedLocationProviderClient", loader);
        if (client != null) installClient(client);
        // Newer GMS exposes an interface; hook the returned implementation instead of abstract methods.
        Hooks.all(services, "getFusedLocationProviderClient", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!call.hasThrowable() && call.getResult() != null) installClient(call.getResult().getClass());
            }
        });
        Class<?> availability = XposedHelpers.findClassIfExists("com.google.android.gms.location.LocationAvailability", loader);
        if (availability != null) Hooks.all(availability, "isLocationAvailable", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) { if (active() && !call.hasThrowable()) call.setResult(true); }
        });
    }
    private synchronized void installClient(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (!hooked.add(current)) continue;
            for (String name : new String[]{"getLastLocation", "getCurrentLocation"}) Hooks.all(current, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active()) return;
                    for (Object arg : call.args) if (arg != null && isA(arg, "com.google.android.gms.tasks.CancellationToken")) {
                        if (Boolean.TRUE.equals(XposedHelpers.callMethod(arg, "isCancellationRequested"))) {
                            call.setResult(XposedHelpers.callStaticMethod(tasks(), "forCanceled")); return;
                        }
                    }
                    call.setResult(task(LocationFactory.create(config.get(), "fused")));
                }
            });
            Hooks.all(current, "requestLocationUpdates", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active()) return;
                    Object callback = null, request = null;
                    for (Object arg : call.args) {
                        if (arg instanceof PendingIntent || isA(arg, "com.google.android.gms.location.LocationCallback")
                                || isA(arg, "com.google.android.gms.location.LocationListener")) callback = arg;
                        if (isA(arg, "com.google.android.gms.location.LocationRequest")) request = arg;
                    }
                    if (callback == null || request == null) return;
                    Executor executor = LocationHooks.find(call.args, Executor.class);
                    if (executor == null) {
                        Looper looper = LocationHooks.find(call.args, Looper.class);
                        if (looper == null) looper = Looper.myLooper();
                        if (looper == null && !(callback instanceof PendingIntent)) return;
                        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
                        executor = runnable -> handler.post(runnable);
                    }
                    Object success = task(null), token = callback;
                    long interval = LocationHooks.readLong(request, "getIntervalMillis",
                            LocationHooks.readLong(request, "getInterval", config.get().interval));
                    long duration = LocationHooks.readLong(request, "getDurationMillis", Long.MAX_VALUE);
                    int maxUpdates = (int) LocationHooks.readLong(request, "getMaxUpdates", Integer.MAX_VALUE);
                    float distance = 0;
                    try { distance = ((Number) XposedHelpers.callMethod(request, "getMinUpdateDistanceMeters")).floatValue(); }
                    catch (Throwable ignored) {}
                    subscriptions.register(token, "fused", interval, distance, duration, maxUpdates, executor,
                            location -> deliver(token, location));
                    call.setResult(success);
                }
            });
            Hooks.all(current, "removeLocationUpdates", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (call.args.length > 0 && subscriptions.remove(call.args[0])) call.setResult(task(null));
                }
            });
            Hooks.all(current, "flushLocations", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) { if (active()) call.setResult(task(null)); }
            });
        }
        XposedBridge.log("FakeGeo: GMS client inspected: " + type.getName());
    }
    private Class<?> tasks() { return XposedHelpers.findClass("com.google.android.gms.tasks.Tasks", loader); }
    private Object task(Object result) { return XposedHelpers.callStaticMethod(tasks(), "forResult", result); }
    private void deliver(Object callback, Location location) throws Exception {
        if (isA(callback, "com.google.android.gms.location.LocationListener")) {
            XposedHelpers.callMethod(callback, "onLocationChanged", location); return;
        }
        Class<?> resultClass = XposedHelpers.findClass("com.google.android.gms.location.LocationResult", loader);
        Object result = XposedHelpers.callStaticMethod(resultClass, "create", Collections.singletonList(location));
        if (callback instanceof PendingIntent) ((PendingIntent) callback).send(config.context, 0,
                new Intent().putExtra("com.google.android.gms.location.EXTRA_LOCATION_RESULT", (Parcelable) result));
        else XposedHelpers.callMethod(callback, "onLocationResult", result);
    }
    private boolean isA(Object value, String name) {
        if (value == null) return false;
        Class<?> type = XposedHelpers.findClassIfExists(name, loader);
        return type != null && type.isInstance(value);
    }
}
