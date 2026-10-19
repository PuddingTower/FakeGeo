package io.github.fakegeo.hook;

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.fakegeo.hook.system.ServerHooks;
import io.github.fakegeo.hook.system.SystemServiceEntry;

public final class ModuleEntry implements IXposedHookLoadPackage {
    private static boolean installed;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param.packageName.equals("android") && (param.processName.equals("android") || param.processName.equals("system_server"))) {
            synchronized (ModuleEntry.class) { if (installed) return; installed = true; }
            install("system_server", () -> new ServerHooks(param.classLoader).install());
            return;
        }
        if (param.packageName.equals(io.github.fakegeo.BuildConfig.APPLICATION_ID)) {
            XposedHelpers.findAndHookMethod("io.github.fakegeo.ui.MainActivity", param.classLoader,
                    "moduleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        boolean service = SystemServiceEntry.isService(param.packageName);
        if (!service && (param.packageName.equals("android") || param.packageName.startsWith("com.android.")
                || param.packageName.startsWith("com.google.android.gms") || !param.isFirstApplication)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                synchronized (ModuleEntry.class) {
                    if (installed) return;
                    installed = true;
                }
                if (service) {
                    install(param.packageName, () -> SystemServiceEntry.install(param.packageName, (Context) call.args[0], param.classLoader));
                    return;
                }
                RuntimeConfig config = new RuntimeConfig((Context) call.args[0]);
                install("sensors", () -> new SensorHooks(new RuntimeConfig((Context) call.args[0], true)).install());
                if (config.get().systemMode) return;
                install("location", () -> new LocationHooks(config).install());
                install("concealment", () -> new AntiDetectionHooks(config).install());
                install("amap", () -> new LarkLocationHooks(config, param.classLoader).install());
                install("wifi", () -> new WifiHooks(config).install());
                install("bluetooth", () -> new BluetoothHooks(config).install());
                install("cell", () -> new CellHooks(config).install());
                install("GMS", () -> new GmsHooks(config, param.classLoader).install());
                XposedBridge.log("FakeGeo: hooks installed in " + param.packageName + " / " + param.processName);
            }
        });
    }
    private static void install(String name, Runnable action) {
        try { action.run(); }
        catch (Throwable error) { XposedBridge.log("FakeGeo: " + name + " hook install failed: " + error); }
    }
}
