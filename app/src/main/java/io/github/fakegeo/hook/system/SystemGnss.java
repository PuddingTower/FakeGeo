package io.github.fakegeo.hook.system;

import android.location.GnssStatus;
import android.os.Binder;
import io.github.fakegeo.config.Profile;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Publishes virtual GNSS status through the system's existing permissions and listener dispatch. */
final class SystemGnss {
    private final ServiceConfig config;
    private final List<String> coverage, errors;
    private final Set<Class<?>> installed = ConcurrentHashMap.newKeySet();
    private volatile Endpoint endpoint;
    private long nextReport, reportCount;
    private boolean firstFix;
    SystemGnss(ServiceConfig config, List<String> coverage, List<String> errors) { this.config=config; this.coverage=coverage; this.errors=errors; }
    void install(ClassLoader loader) {
        for (String name : new String[]{"com.android.server.location.gnss.GnssStatusProvider", "com.android.server.location.GnssStatusListenerHelper"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null || !installed.add(type)) continue;
            Method report = null, fix = null;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getReturnType() != void.class || Modifier.isAbstract(method.getModifiers())) continue;
                Class<?>[] args = method.getParameterTypes();
                if ((method.getName().equals("onReportFirstFix") || method.getName().equals("onFirstFix"))
                        && args.length == 1 && args[0] == int.class) fix = method;
                if (!method.getName().equals("onReportSvStatus") && !method.getName().equals("onSvStatusChanged")) continue;
                boolean supported = args.length == 1 && args[0] == GnssStatus.class || GnssPayload.legacySignature(args);
                if (!supported) { error("unsupported status signature", new NoSuchMethodException(method.toString())); continue; }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            Profile p = config.get();
                            if (!p.enabled || !p.systemMode || !p.location) return;
                            if (!p.gnss) { call.setResult(null); return; }
                            try { call.args = arguments(method); }
                            catch (Throwable failure) { error("status replacement", failure); call.setResult(null); }
                        }
                    });
                    report = method; coverage.add(type.getName() + "." + method.getName() + " (virtual GNSS)");
                } catch (Throwable failure) { error("status hook", failure); }
            }
            if (report != null) {
                Method statusMethod = report, fixMethod = fix;
                try {
                    XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam call) {
                            if (!call.hasThrowable()) endpoint = new Endpoint(call.thisObject, statusMethod, fixMethod);
                        }
                    });
                } catch (Throwable failure) { error("provider capture", failure); }
            }
        }
    }
    void tick(Profile p, long now) {
        if (!p.enabled || !p.systemMode || !p.location || !p.gnss) { nextReport=0; firstFix=false; return; }
        Endpoint captured = endpoint;
        if (captured == null || now < nextReport) return;
        Object manager = captured.manager.get(); if (manager == null) return;
        nextReport = now + Math.max(500, Math.min(1000, p.interval));
        long identity = Binder.clearCallingIdentity();
        try {
            if (!firstFix && captured.fix != null) {
                XposedBridge.invokeOriginalMethod(captured.fix, manager, new Object[]{100}); firstFix=true;
            }
            XposedBridge.invokeOriginalMethod(captured.report, manager, arguments(captured.report)); reportCount++;
        } catch (Throwable failure) { error("status heartbeat", failure); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    long reports() { return reportCount; }
    boolean captured() { Endpoint value=endpoint; return value != null && value.manager.get() != null; }
    private static Object[] arguments(Method method) throws ReflectiveOperationException {
        return method.getParameterCount() == 1 ? new Object[]{GnssStatusFactory.create()} : new GnssPayload().legacyArguments();
    }
    private void error(String stage, Throwable failure) {
        String message = "GNSS " + stage + ": " + failure.getClass().getSimpleName();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: " + message); }
    }
    private static final class Endpoint {
        final WeakReference<Object> manager; final Method report, fix;
        Endpoint(Object manager, Method report, Method fix) { this.manager=new WeakReference<>(manager); this.report=report; this.fix=fix; }
    }
}
