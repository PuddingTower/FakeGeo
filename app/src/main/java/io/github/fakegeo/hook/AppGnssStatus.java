package io.github.fakegeo.hook;

import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.hook.system.GnssStatusFactory;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Publishes a virtual constellation inside the target process. A high accuracy fix with zero
 * satellites is a self-contradicting profile, so registration succeeds instead of returning false.
 */
final class AppGnssStatus {
    private final RuntimeConfig config;
    private final Handler timer;
    private final Map<Object, Subscription> listeners = new IdentityHashMap<>();
    private final boolean available;
    AppGnssStatus(RuntimeConfig config) {
        this.config = config;
        HandlerThread worker = new HandlerThread("FakeGeo-gnss"); worker.start(); timer = new Handler(worker.getLooper());
        boolean probe;
        try { GnssStatusFactory.create(); probe = true; }
        catch (Throwable failure) { probe = false; XposedBridge.log("FakeGeo: virtual GNSS unavailable: " + failure); }
        available = probe;
    }
    void install() {
        if (!available) return; // Leave the receiver's real status path untouched rather than claim success.
        Hooks.all(LocationManager.class, "registerGnssStatusCallback", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (!active()) return;
                GnssStatus.Callback legacy = LocationHooks.find(call.args, GnssStatus.Callback.class);
                Object listener = legacy != null ? legacy : callback(call.args);
                if (listener == null) return;
                Executor executor = LocationHooks.find(call.args, Executor.class);
                android.os.Handler handler = LocationHooks.find(call.args, android.os.Handler.class);
                register(listener, executor != null ? executor
                        : handler != null ? task -> handler.post(task)
                        : task -> new Handler(Looper.getMainLooper()).post(task));
                call.setResult(true);
            }
        });
        for (String name : new String[]{"removeGnssStatusCallback", "unregisterGnssStatusCallback"})
            Hooks.all(LocationManager.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (call.args.length > 0 && call.args[0] != null) unregister(call.args[0]);
                    // The original still runs so the receiver drops its own bookkeeping.
                }
            });
        timer.post(pump);
    }
    private boolean active() { Profile p = config.get(); return p.enabled && p.location; }
    private static Object callback(Object[] args) {
        for (Object arg : args)
            if (arg != null && arg.getClass().getName().contains("GnssStatus") && arg.getClass().getName().contains("Callback")) return arg;
        return null;
    }
    private synchronized void register(Object listener, Executor executor) {
        Subscription previous = listeners.remove(listener);
        if (previous != null) previous.closed = true;
        listeners.put(listener, new Subscription(listener, executor));
    }
    private synchronized void unregister(Object listener) {
        Subscription entry = listeners.remove(listener);
        if (entry != null) entry.closed = true;
    }
    private final Runnable pump = new Runnable() {
        @Override public void run() {
            long delay = 3000;
            try {
                if (active()) {
                    GnssStatus status = GnssStatusFactory.create();
                    List<Subscription> snapshot;
                    synchronized (AppGnssStatus.this) { snapshot = new ArrayList<>(listeners.values()); }
                    for (Subscription entry : snapshot) entry.deliver(status);
                    synchronized (AppGnssStatus.this) { if (!listeners.isEmpty()) delay = 1000; }
                }
            } catch (Throwable failure) { XposedBridge.log("FakeGeo: virtual GNSS delivery ended: " + failure); }
            timer.postDelayed(this, delay);
        }
    };
    private static final class Subscription {
        private final Object listener; private final Executor executor; private boolean closed;
        Subscription(Object listener, Executor executor) { this.listener = listener; this.executor = executor; }
        void deliver(GnssStatus status) {
            if (closed) return;
            try { executor.execute(() -> {
                if (closed) return;
                try { de.robv.android.xposed.XposedHelpers.callMethod(listener, "onSatelliteStatusChanged", status); }
                catch (Throwable failure) { XposedBridge.log("FakeGeo: GNSS callback rejected: " + failure); }
            }); }
            catch (RuntimeException rejected) { /* A shut-down executor simply loses this sample. */ }
        }
    }
}
