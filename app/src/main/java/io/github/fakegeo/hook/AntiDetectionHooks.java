package io.github.fakegeo.hook;

import android.app.AppOpsManager;
import android.provider.Settings;
import io.github.fakegeo.config.Profile;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Conceals the module itself: the developer-option switch, the mock-location app op and
 * stack frames that name Xposed, LSPosed or FakeGeo.
 */
final class AntiDetectionHooks {
    /** AppOpsManager.OPSTR_MOCK_LOCATION; kept as a literal because its constant is newer than minSdk 29. */
    private static final String MOCK_OP = "android:mock_location";
    private final RuntimeConfig config;
    AntiDetectionHooks(RuntimeConfig config) { this.config = config; }
    private boolean active() { Profile p = config.get(); return p.enabled && p.antiDetect; }
    void install() {
        for (String name : new String[]{"getString", "getIntForUser"})
            Hooks.all(Settings.Secure.class, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (!active() || call.hasThrowable() || call.args.length < 2) return;
                    if (Settings.Secure.ALLOW_MOCK_LOCATION.equals(call.args[1])) call.setResult(name.equals("getString") ? "0" : 0);
                }
            });
        // getInt() resolves through getString(), but the cached-value overload does not.
        Hooks.all(Settings.Secure.class, "getInt", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable() || call.args.length < 2) return;
                if (Settings.Secure.ALLOW_MOCK_LOCATION.equals(call.args[1])) call.setResult(0);
            }
        });
        for (String name : new String[]{"checkOp", "checkOpNoThrow", "unsafeCheckOp", "unsafeCheckOpNoThrow",
                "checkOpRawNoThrow", "unsafeCheckOpRaw", "startWatchingMode", "noteOperation"})
            Hooks.all(AppOpsManager.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active() || call.args.length == 0 || !(call.args[0] instanceof String)) return;
                    if (MOCK_OP.equals(call.args[0]) && ((java.lang.reflect.Method) call.method).getReturnType() == int.class)
                        call.setResult(AppOpsManager.MODE_IGNORED);
                }
            });
        // Any frame naming the framework or this module is removed before the caller inspects it.
        Hooks.all(Throwable.class, "getStackTrace", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable()) return;
                call.setResult(sanitize((StackTraceElement[]) call.getResult()));
            }
        });
        Hooks.all(Thread.class, "getStackTrace", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable()) return;
                call.setResult(sanitize((StackTraceElement[]) call.getResult()));
            }
        });
    }
    static StackTraceElement[] sanitize(StackTraceElement[] frames) {
        if (frames == null) return null;
        List<StackTraceElement> kept = new ArrayList<>(frames.length);
        for (StackTraceElement frame : frames) if (frame != null && !isModuleFrame(frame)) kept.add(frame);
        return kept.size() == frames.length ? frames : kept.toArray(new StackTraceElement[0]);
    }
    private static boolean isModuleFrame(StackTraceElement frame) {
        String owner = frame.getClassName() + frame.getMethodName() + frame.getFileName();
        return owner.contains("de.robv.android.xposed") || owner.contains("org.lsposed")
                || owner.contains("LSPHooker") || owner.contains("io.github.fakegeo");
    }
}
