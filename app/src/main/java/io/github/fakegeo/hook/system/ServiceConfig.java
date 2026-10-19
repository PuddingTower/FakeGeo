package io.github.fakegeo.hook.system;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootCommands;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import de.robv.android.xposed.XposedBridge;

/** Never perform disk or cross-process IPC while a system service's caller/lock is held. */
public final class ServiceConfig {
    private final PublishedProfile profile = new PublishedProfile();
    private volatile String error = "尚未读取配置";
    private final Context context;
    private final boolean systemServer;
    public final Handler worker;
    private boolean loggedFailure;
    private String lastJson;
    private long lastModified = -1, lastLength = -1;
    public ServiceConfig(Context context, boolean systemServer) {
        this.context = context; this.systemServer = systemServer;
        HandlerThread thread = new HandlerThread("FakeGeo-system"); thread.start(); worker = new Handler(thread.getLooper());
        worker.post(new Runnable() {
            @Override public void run() { refresh(); worker.postDelayed(this, 1000); }
        });
    }
    public Profile get() { return profile.get(); }
    public boolean active() { Profile p = get(); return p.enabled && p.systemMode; }
    public String error() { return error; }
    private void refresh() {
        try {
            String json;
            if (systemServer) {
                File file = new File(RootCommands.PROFILE);
                if (!file.isFile() || file.length() > 65536) throw new java.io.IOException("系统配置未发布或不可读取");
                long modified = file.lastModified(), length = file.length();
                if (modified == lastModified && length == lastLength) return;
                json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                lastModified = modified; lastLength = length;
            } else {
                // The bridge avoids resolving the module provider from a service process, which fails
                // under package-visibility isolation; the provider remains the fallback.
                String bridged = ConfigBridgeClient.read(context);
                if (bridged != null) {
                    if (!bridged.equals(lastJson)) { profile.update(bridged); lastJson = bridged; }
                    error = ""; loggedFailure = false; return;
                }
                try (Cursor cursor = context.getContentResolver().query(ConfigStore.URI, null, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new java.io.IOException("配置 Provider 不可读取");
                    json = cursor.getString(0);
                }
            }
            if (!json.equals(lastJson)) { profile.update(json); lastJson = json; }
            error = ""; loggedFailure = false;
        } catch (Exception failure) {
            // Keep the last successfully published state until a valid disable is received.
            // Before the first valid publication the snapshot remains disabled.
            error = failure.getMessage();
            if (!loggedFailure) XposedBridge.log("FakeGeo: system config unavailable: " + failure);
            loggedFailure = true;
        }
    }
}
