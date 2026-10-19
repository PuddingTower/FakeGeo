package io.github.fakegeo.hook.system;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Captures the class of every service the system starts or publishes, so vendor location services
 * shipped in oplus-services.jar or a Mainline APEX are found without hard-coding their names.
 */
public final class OplusClassLoaderResolver {
    public interface Sink { void inspect(String identity, ClassLoader loader, Object service); }
    private final Sink sink;
    private final List<String> coverage, errors;
    private final Set<Class<?>> inspected = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger services = new AtomicInteger();

    private OplusClassLoaderResolver(Sink sink, List<String> coverage, List<String> errors) {
        this.sink = sink; this.coverage = coverage; this.errors = errors;
    }
    public static OplusClassLoaderResolver install(ClassLoader loader, List<String> coverage, List<String> errors, Sink sink) {
        OplusClassLoaderResolver resolver = new OplusClassLoaderResolver(sink, coverage, errors);
        for (String[] pair : new String[][]{
                {"com.android.server.SystemServiceManager", "startService"},
                {"android.server.SystemServiceManager", "startService"},
                {"com.android.server.SystemService", "publishBinderService"},
                {"android.os.ServiceManager", "addService"},
                {"com.android.server.LocalServices", "addService"}}) {
            Class<?> type = XposedHelpers.findClassIfExists(pair[0], loader);
            if (type == null) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers())) continue;
                boolean candidate = method.getName().equals(pair[1])
                        || pair[1].equals("startService") && method.getName().startsWith("startService");
                if (candidate) resolver.capture(type, method);
            }
        }
        return resolver;
    }
    private void capture(Class<?> type, Method method) {
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (call.hasThrowable()) return;
                    offer(call.getResult());
                    for (Object argument : call.args) offer(argument);
                }
            });
            coverage.add(type.getName() + "." + method.getName() + " (service capture)");
        } catch (Throwable failure) { error(type.getName() + "." + method.getName(), failure); }
    }
    private void offer(Object service) {
        if (service == null || service instanceof Class || service instanceof String || service instanceof Integer) return;
        Class<?> type = service.getClass();
        if (!inspected.add(type)) return;
        services.incrementAndGet();
        try { sink.inspect(type.getName(), type.getClassLoader(), service); }
        catch (Throwable failure) { error(type.getName(), failure); }
    }
    public int services() { return services.get(); }
    private void error(String stage, Throwable failure) {
        String message = "vendor class loader " + stage + ": " + failure.getClass().getSimpleName();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: " + message); }
    }
}
