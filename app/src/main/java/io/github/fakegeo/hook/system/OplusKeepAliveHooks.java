package io.github.fakegeo.hook.system;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.fakegeo.config.ServicePackages;

/**
 * ColorOS freezes background processes through Hans, which can stop the overlay controller.
 * Only freeze *actions* whose argument names this module are suppressed; predicates keep their
 * original polarity and no other application is affected.
 */
public final class OplusKeepAliveHooks {
    private final ServiceConfig config;
    private final List<String> coverage, errors;
    private final Set<Class<?>> installed = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger skipped = new AtomicInteger();
    private volatile Set<Integer> moduleIds;
    public OplusKeepAliveHooks(ServiceConfig config, List<String> coverage, List<String> errors) {
        this.config = config; this.coverage = coverage; this.errors = errors;
    }
    public void install(ClassLoader loader) {
        for (String name : new String[]{"com.android.server.am.OplusHansManager", "com.android.server.am.OplusAppStartupManager",
                "com.android.server.am.OplusHansManagerImpl"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null || !installed.add(type)) continue;
            int hooked = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class) continue;
                String lower = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (!lower.contains("freez") || lower.contains("unfreez") || lower.contains("thaw")) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            if (!config.get().keepAlive || !namesModule(call.args)) return;
                            call.setResult(null);
                            skipped.incrementAndGet();
                        }
                    });
                    hooked++;
                } catch (Throwable failure) { error(type.getName() + "." + method.getName(), failure); }
            }
            if (hooked > 0) coverage.add(type.getName() + " (" + hooked + " freeze actions guarded)");
        }
    }
    private boolean namesModule(Object[] args) {
        for (Object arg : args) if (arg != null && matches(arg)) return true;
        return false;
    }
    private boolean matches(Object arg) {
        if (arg instanceof String) return module((String) arg);
        if (arg instanceof Integer) return ids().contains(((Integer) arg) % 100000);
        if (arg instanceof Long) return ids().contains((int) (((Long) arg) % 100000));
        for (String field : new String[]{"processName", "pkg", "packageName", "mProcessName", "mPackageName"}) {
            try {
                Object value = XposedHelpers.getObjectField(arg, field);
                if (value instanceof String && module((String) value)) return true;
            } catch (Throwable absent) { /* Field names differ across ColorOS releases. */ }
        }
        for (String accessor : new String[]{"getPackageName", "getCachedName"}) {
            try {
                Object value = XposedHelpers.callMethod(arg, accessor);
                if (value instanceof String && module((String) value)) return true;
            } catch (Throwable absent) { /* Same reason. */ }
        }
        return false;
    }
    private static boolean module(String value) {
        for (String name : ServicePackages.MODULE_NAMES) if (name.equals(value) || value.startsWith(name + "/")) return true;
        return false;
    }
    /** App ids are resolved once per process start; a missing package simply yields no ids. */
    private Set<Integer> ids() {
        Set<Integer> current = moduleIds;
        if (current != null) return current;
        Set<Integer> resolved = new HashSet<>();
        try {
            android.content.Context context = (android.content.Context)
                    XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
            for (String name : ServicePackages.MODULE_NAMES) {
                try { resolved.add(context.getPackageManager().getApplicationInfo(name, 0).uid % 100000); }
                catch (android.content.pm.PackageManager.NameNotFoundException absent) { /* Only one build is installed. */ }
            }
        } catch (Throwable unavailable) { return Collections.emptySet(); }
        moduleIds = resolved;
        return resolved;
    }
    public int skipped() { return skipped.get(); }
    private void error(String stage, Throwable failure) {
        String message = "keep-alive " + stage + ": " + failure.getClass().getSimpleName();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: " + message); }
    }
}
