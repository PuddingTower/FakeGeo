package io.github.fakegeo.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import org.json.JSONObject;

/** Debug builds only. ADB/system DUMP permission is required; callers cannot supply shell commands. */
public final class DiagnosticsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!"io.github.fakegeo.DEBUG_DIAGNOSTICS".equals(action) && !"io.github.fakegeo.DEBUG_REVISION_TEST".equals(action)
                && !"io.github.fakegeo.DEBUG_PUBLISH_DISABLED".equals(action) && !"io.github.fakegeo.DEBUG_RETURN_CONTRACT".equals(action)
                && !"io.github.fakegeo.DEBUG_RUNTIME_GATE".equals(action) && !"io.github.fakegeo.DEBUG_RADIOS_OFF".equals(action)
                && !"io.github.fakegeo.DEBUG_RADIOS_RESTORE".equals(action)
                && !"io.github.fakegeo.DEBUG_PREPARE_SYSTEM_UPGRADE".equals(action) && !"io.github.fakegeo.DEBUG_ENABLE_SYSTEM_TEST".equals(action)
                && !"io.github.fakegeo.DEBUG_SYSTEM_LOCATION_CONTRACT".equals(action)
                && !"io.github.fakegeo.DEBUG_WIFI_IDENTITY_CONTRACT".equals(action)
                && !"io.github.fakegeo.DEBUG_NETWORK_ISOLATION_START".equals(action)
                && !"io.github.fakegeo.DEBUG_NETWORK_ISOLATION_STOP".equals(action)
                && !"io.github.fakegeo.DEBUG_NETWORK_ISOLATION_STATUS".equals(action)
                && !"io.github.fakegeo.DEBUG_LOCATION_CACHE_METADATA".equals(action)
                && !"io.github.fakegeo.DEBUG_LOCATION_CACHE_SCHEMA".equals(action)
                && !"io.github.fakegeo.DEBUG_LOCATION_CACHE_BACKUP".equals(action)
                && !"io.github.fakegeo.DEBUG_LOCATION_CACHE_RESTORE".equals(action)
                && !"io.github.fakegeo.DEBUG_SYSTEM_STATUS".equals(action)) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                String result;
                try {
                    if("io.github.fakegeo.DEBUG_SYSTEM_STATUS".equals(action)) {
                        result=RootBridge.execute(RootCommands.status());
                    } else if ("io.github.fakegeo.DEBUG_LOCATION_CACHE_BACKUP".equals(action) || "io.github.fakegeo.DEBUG_LOCATION_CACHE_RESTORE".equals(action)) {
                        result = LocationCacheIsolation.run(context, "io.github.fakegeo.DEBUG_LOCATION_CACHE_RESTORE".equals(action));
                    } else if ("io.github.fakegeo.DEBUG_LOCATION_CACHE_SCHEMA".equals(action)) {
                        result = LocationCacheDiagnostics.schema(context);
                    } else if ("io.github.fakegeo.DEBUG_LOCATION_CACHE_METADATA".equals(action)) {
                        result = LocationCacheDiagnostics.run();
                    } else if ("io.github.fakegeo.DEBUG_NETWORK_ISOLATION_START".equals(action)) {
                        result = NetworkIsolation.start(context);
                    } else if ("io.github.fakegeo.DEBUG_NETWORK_ISOLATION_STOP".equals(action)) {
                        result = NetworkIsolation.stop();
                    } else if ("io.github.fakegeo.DEBUG_NETWORK_ISOLATION_STATUS".equals(action)) {
                        result = NetworkIsolation.status();
                    } else if ("io.github.fakegeo.DEBUG_PREPARE_SYSTEM_UPGRADE".equals(action) || "io.github.fakegeo.DEBUG_ENABLE_SYSTEM_TEST".equals(action)) {
                        result = SystemSetup.run(context, "io.github.fakegeo.DEBUG_ENABLE_SYSTEM_TEST".equals(action));
                    } else if ("io.github.fakegeo.DEBUG_WIFI_IDENTITY_CONTRACT".equals(action)) {
                        result = WifiIdentityRegression.run();
                    } else if ("io.github.fakegeo.DEBUG_SYSTEM_LOCATION_CONTRACT".equals(action)) {
                        result = io.github.fakegeo.hook.system.SystemLocationRegression.run();
                    } else if ("io.github.fakegeo.DEBUG_RADIOS_OFF".equals(action) || "io.github.fakegeo.DEBUG_RADIOS_RESTORE".equals(action)) {
                        result = RadioIsolation.run(context, "io.github.fakegeo.DEBUG_RADIOS_RESTORE".equals(action));
                    } else if ("io.github.fakegeo.DEBUG_RETURN_CONTRACT".equals(action)) {
                        result = ReturnContractRegression.run(context);
                    } else if ("io.github.fakegeo.DEBUG_RUNTIME_GATE".equals(action)) {
                        JSONObject status = new JSONObject(RootBridge.execute(RootCommands.status()));
                        try {
                            io.github.fakegeo.root.RuntimeGate.requireCurrent(status, System.currentTimeMillis());
                            result = "RUNTIME_GATE_CURRENT";
                        } catch (IllegalStateException blocked) { result = "RUNTIME_GATE_BLOCKED: " + blocked.getMessage(); }
                    } else if ("io.github.fakegeo.DEBUG_REVISION_TEST".equals(action)) {
                        result = RootBridge.execute(RootShellRegression.command());
                    } else if ("io.github.fakegeo.DEBUG_PUBLISH_DISABLED".equals(action)) {
                        Profile p = ConfigStore.load(context);
                        if (p.enabled) throw new IllegalStateException("This regression check only publishes the saved disabled profile");
                        synchronized (ConfigStore.class) {
                            ConfigStore.save(context, p);
                            result = RootBridge.execute(RootCommands.publish(p.json().toString(), p.revision));
                            ConfigStore.markPublished(context, p);
                        }
                    } else result = RootBridge.execute("id\n"
                            + "printf '\\nCONFIG_FILES\\n'\nls -ldZ /data/system/fakegeo\nls -lZ /data/system/fakegeo\n"
                            + "printf '\\nPUBLISHED_REVISION\\n'\nsed -n 's/.*\"revision\":\\([0-9][0-9]*\\).*/\\1/p' /data/system/fakegeo/profile.json\n"
                            + "printf '\\nSYSTEM_STATUS\\n'\nif [ -f /data/system/fakegeo/status.json ]; then cat /data/system/fakegeo/status.json; fi\n"
                            + "printf '\\nLSPOSED_LOGS\\n'\nfor f in /data/adb/lspd/log/modules*.log /data/adb/lspd/log/verbose*.log; do [ -f \"$f\" ] || continue; grep -E 'FakeGeo|io.github.fakegeo' \"$f\" | tail -45; done\n"
                            + "printf '\\nLOGCAT\\n'\nlogcat -d -t 15000 | grep -E 'FakeGeo:|FakeGeo.*denied|avc: denied.*fakegeo' | tail -45\n");
                } catch (Exception failure) { result = failure.toString(); }
                JSONObject record = new JSONObject(); record.put("result", result); record.put("action", action); record.put("finishedAt", System.currentTimeMillis());
                context.getSharedPreferences("debug_diagnostics", Context.MODE_PRIVATE).edit().putString("record", record.toString()).commit();
            } catch (Exception failure) { android.util.Log.e("FakeGeo", "Debug diagnostics failed", failure); }
            finally { pending.finish(); }
        }, "FakeGeo-debug-reader").start();
    }
}
