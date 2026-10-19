package io.github.fakegeo.hook;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.SystemClock;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.hook.system.ConfigBinderBridge;
import io.github.fakegeo.hook.system.ConfigBridgeClient;
import de.robv.android.xposed.XposedBridge;

public final class RuntimeConfig {
    public final Context context;
    private volatile Profile profile = Profile.defaults();
    private volatile long nextRead;
    private boolean reportedFailure;
    private final boolean sensorOnly;
    private volatile String channel = "未读取";
    private boolean loggedChannel;
    public RuntimeConfig(Context context) { this(context, false); }
    public RuntimeConfig(Context context, boolean sensorOnly) { this.context = context; this.sensorOnly = sensorOnly; }
    public Profile get() {
        if (SystemClock.elapsedRealtime() >= nextRead) refresh();
        return profile;
    }
    private synchronized void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextRead) return;
        nextRead = now + (sensorOnly ? 500 : 1500);
        if (readBridge()) return;
        readProvider();
    }
    private boolean readBridge() {
        String json = ConfigBridgeClient.read(context, sensorOnly ? ConfigBinderBridge.KIND_SENSORS : ConfigBinderBridge.KIND_PROFILE);
        if (json == null) return false;
        try {
            channel = "binder";
            apply(json);
            return true;
        } catch (Exception invalid) { return false; }
    }
    private void readProvider() {
        try (Cursor cursor = context.getContentResolver().query(sensorOnly ? ConfigStore.SENSOR_URI : ConfigStore.URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Configuration unavailable");
            channel = "provider";
            apply(cursor.getString(0));
            reportedFailure = false;
        } catch (SecurityException revoked) {
            // URI authorization was revoked (e.g. the target was removed). Do not retain enabled state.
            profile = Profile.defaults();
        } catch (Exception e) {
            if (sensorOnly) profile = Profile.defaults();
            // Retain the last valid snapshot during a transient IPC failure. Never write preferences here.
            if (!reportedFailure) XposedBridge.log("FakeGeo: config IPC failed in " + context.getPackageName() + ": " + e);
            reportedFailure = true;
        }
    }
    private void apply(String json) throws Exception {
        Profile next = Profile.parse(json);
        // Shared UIDs can read the profile, but only explicitly listed packages activate hooks.
        boolean listed = sensorOnly ? next.sensorTargets.contains(context.getPackageName())
                : !next.systemMode && next.targets.contains(context.getPackageName());
        profile = listed ? next : Profile.defaults();
        reportedFailure = false;
        if (!loggedChannel) {
            loggedChannel = true;
            XposedBridge.log("FakeGeo: first configuration read in " + context.getPackageName() + " via " + channel);
        }
    }
    public boolean locationAllowed() {
        return permission(Manifest.permission.ACCESS_FINE_LOCATION) || permission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }
    public boolean permission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
