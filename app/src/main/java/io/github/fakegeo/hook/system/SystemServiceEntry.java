package io.github.fakegeo.hook.system;

import android.content.Context;
import io.github.fakegeo.config.ServicePackages;
import java.util.concurrent.CopyOnWriteArrayList;
import de.robv.android.xposed.XposedBridge;

public final class SystemServiceEntry {
    private SystemServiceEntry() {}
    public static boolean isService(String name) { return ServicePackages.NAMES.contains(name); }
    public static void install(String name, Context context, ClassLoader loader) {
        ServiceConfig config = new ServiceConfig(context, false);
        CopyOnWriteArrayList<String> coverage = new CopyOnWriteArrayList<>(), errors = new CopyOnWriteArrayList<>();
        RadioServiceHooks hooks = new RadioServiceHooks(config, loader, coverage, errors);
        if (name.equals("com.android.phone")) hooks.installPhone();
        if (name.equals("com.android.bluetooth")) hooks.installBluetooth();
        if (name.endsWith(".wifi")) hooks.installWifi(loader);
        XposedBridge.log("FakeGeo: service hooks " + name + ": " + coverage);
        config.worker.post(new Runnable() {
            @Override public void run() {
                try {
                    org.json.JSONObject status = new org.json.JSONObject();
                    status.put("moduleVersion", io.github.fakegeo.config.ModuleVersion.CURRENT);
                    status.put("pid", android.os.Process.myPid()); status.put("api", android.os.Build.VERSION.SDK_INT);
                    status.put("revision", config.get().revision); status.put("enabled", config.active());
                    status.put("configError", config.error()); status.put("hooks", new org.json.JSONArray(coverage));
                    status.put("errors", new org.json.JSONArray(errors));
                    android.os.Bundle data = new android.os.Bundle(); data.putString("json", status.toString());
                    context.getContentResolver().call(io.github.fakegeo.config.ConfigStore.URI, "report_service", name, data);
                } catch (Exception unavailable) { /* The module may be locked/stopped or not granted yet. */ }
                config.worker.postDelayed(this, 5000);
            }
        });
    }
}
