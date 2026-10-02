package io.github.fakegeo.hook;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.SystemClock;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import de.robv.android.xposed.XposedBridge;

public final class RuntimeConfig {
    public final Context context;
    private volatile Profile profile = Profile.defaults();
    private volatile long nextRead;
    private boolean reportedFailure;
    public RuntimeConfig(Context context) { this.context = context; }
    public Profile get() {
        if (SystemClock.elapsedRealtime() >= nextRead) refresh();
        return profile;
    }
    private synchronized void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextRead) return;
        nextRead = now + 1500;
        try (Cursor cursor = context.getContentResolver().query(ConfigStore.URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Configuration unavailable");
            Profile next = Profile.parse(cursor.getString(0));
            // Shared UIDs can read the profile, but only explicitly listed packages activate hooks.
            boolean listed = !next.systemMode && next.targets.contains(context.getPackageName());
            profile = listed ? next : Profile.defaults();
            reportedFailure = false;
        } catch (SecurityException revoked) {
            // URI authorization was revoked (e.g. the target was removed). Do not retain enabled state.
            profile = Profile.defaults();
        } catch (Exception e) {
            // Retain the last valid snapshot during a transient IPC failure. Never write preferences here.
            if (!reportedFailure) XposedBridge.log("FakeGeo: config IPC failed in " + context.getPackageName() + ": " + e);
            reportedFailure = true;
        }
    }
    public boolean locationAllowed() {
        return permission(Manifest.permission.ACCESS_FINE_LOCATION) || permission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }
    public boolean permission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
