package io.github.fakegeo.hook.system;

import android.location.Location;
import android.os.Binder;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.fakegeo.config.Profile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Covers the vendor location services ColorOS and Qualcomm add beside AOSP. They report and serve
 * positions through their own Binder interfaces, so an AOSP-only hook leaves a real network fix
 * reachable. Discovery is by behaviour: any vendor method that accepts or returns a Location.
 */
public final class OplusLocationHooks implements OplusClassLoaderResolver.Sink {
    private final ServiceConfig config;
    private final List<String> coverage, errors;
    private final Set<Class<?>> installed = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger replacements = new AtomicInteger();
    public OplusLocationHooks(ServiceConfig config, List<String> coverage, List<String> errors) {
        this.config = config; this.coverage = coverage; this.errors = errors;
    }
    @Override public void inspect(String identity, ClassLoader loader, Object service) {
        Class<?> type = service.getClass();
        if (!isVendor(type)) return;
        if (!installed.add(type)) return;
        int hooked = 0;
        for (Method method : type.getMethods()) {
            if (method.isSynthetic() || Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
            if (!declaredByVendor(method)) continue;
            if (locationType(method.getReturnType()) && method.getReturnType() != void.class) { hookResult(method); hooked++; continue; }
            if (method.getReturnType() == void.class && method.getParameterCount() == 1 && locationType(method.getParameterTypes()[0])) {
                hookArgument(method); hooked++;
            }
        }
        if (hooked > 0) coverage.add(type.getName() + " (" + hooked + " vendor location entries)");
    }
    /** Only the concrete vendor implementation, never inherited framework plumbing. */
    private static boolean declaredByVendor(Method method) {
        String owner = method.getDeclaringClass().getName();
        return !owner.equals("java.lang.Object") && !owner.startsWith("java.") && !owner.startsWith("android.")
                && !owner.startsWith("com.android.server.location.");
    }
    private static boolean isVendor(Class<?> type) {
        String name = type.getName();
        return name.startsWith("com.oplus.") || name.startsWith("oplus.") || name.startsWith("vendor.")
                || name.startsWith("com.qualcomm.") || name.startsWith("com.android.server.location.oplus")
                || name.toLowerCase().contains("opluslocation");
    }
    private static boolean locationType(Class<?> type) {
        return Location.class.isAssignableFrom(type) || type.getName().equals("android.location.LocationResult")
                || java.util.List.class.isAssignableFrom(type);
    }
    private void hookResult(Method method) {
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (call.hasThrowable() || !isLocationBatch(call.getResult())) return;
                    Profile p = reportable();
                    if (p == null) return;
                    try { call.setResult(virtual(p, call.getResult())); replacements.incrementAndGet(); }
                    catch (Throwable failure) { error(method.getName(), failure); }
                }
            });
        } catch (Throwable failure) { error(method.getName(), failure); }
    }
    private void hookArgument(Method method) {
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!isLocationBatch(call.args[0])) return;
                    Profile p = reportable();
                    if (p == null) return;
                    try { call.args[0] = virtual(p, call.args[0]); replacements.incrementAndGet(); }
                    catch (Throwable failure) { error(method.getName(), failure); }
                }
            });
        } catch (Throwable failure) { error(method.getName(), failure); }
    }
    /** An application caller is rewritten; system-internal reads keep their real data. */
    private Profile reportable() {
        Profile p = config.get();
        if (!p.enabled || !p.systemMode || !p.location || callIsInternalSystem()) return null;
        return p;
    }
    private static boolean callIsInternalSystem() {
        try { return !CallerPolicy.application(Binder.getCallingUid()); } catch (Throwable unavailable) { return false; }
    }
    private static Object virtual(Profile p, Object value) throws ReflectiveOperationException {
        if (value instanceof Location) return SystemLocations.create(p, SystemLocations.provider(value, "fused"));
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            if (source.isEmpty() || !(source.get(0) instanceof Location)) return value;
            ArrayList<Location> replaced = new ArrayList<>();
            for (Object item : source) if (item instanceof Location) replaced.add(SystemLocations.create(p, SystemLocations.provider(item, "fused")));
            return replaced;
        }
        if (value == null) return null;
        return SystemLocations.batch(value.getClass(), SystemLocations.create(p, "fused"));
    }
    private static boolean isLocationBatch(Object value) {
        if (value instanceof List) { List<?> source = (List<?>) value; return !source.isEmpty() && source.get(0) instanceof Location; }
        return value != null && (value instanceof Location || value.getClass().getName().equals("android.location.LocationResult"));
    }
    public int replacements() { return replacements.get(); }
    private void error(String stage, Throwable failure) {
        String message = "vendor location " + stage + ": " + failure.getClass().getSimpleName();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: " + message); }
    }
}
