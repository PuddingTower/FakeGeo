package io.github.fakegeo.hook.system;

import android.net.NetworkCapabilities;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Runs after Android's permission filtering for the actual recipient, including Handler callbacks. */
public final class ConnectivityHooks {
    private final ServiceConfig config;
    private final List<String> coverage, errors;
    private final Set<Method> installed = Collections.synchronizedSet(new HashSet<>());
    private final Set<Class<?>> inspected = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong replacements = new AtomicLong(), fallbacks = new AtomicLong();
    private volatile boolean protectedRecipients;
    public ConnectivityHooks(ServiceConfig config, List<String> coverage, List<String> errors) {
        this.config = config; this.coverage = coverage; this.errors = errors;
    }
    public void install(ClassLoader loader) {
        // Android 16 Mainline relocates the implementation under android.net.connectivity.
        for (String name : new String[]{"com.android.server.ConnectivityService",
                "android.net.connectivity.com.android.server.ConnectivityService"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null || !inspected.add(type)) continue;
            for (Method method : type.getDeclaredMethods()) {
                int uidIndex = ConnectivitySignatures.recipientUidIndex(method);
                if (uidIndex < 0 || Modifier.isAbstract(method.getModifiers()) || !installed.add(method)) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam call) {
                            if (call.hasThrowable() || !(call.getResult() instanceof NetworkCapabilities)) return;
                            io.github.fakegeo.config.Profile p = config.get();
                            if (!config.active() || !p.wifi || !CallerPolicy.application((Integer) call.args[uidIndex])) return;
                            NetworkCapabilities source = (NetworkCapabilities) call.getResult();
                            boolean attached = source.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                    || source.getTransportInfo() instanceof android.net.wifi.WifiInfo;
                            // Attendance Wi-Fi checks also run while the radio is on mobile data.
                            if (!attached && !(p.wifiConnected && source.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) return;
                            try {
                                call.setResult(attached ? io.github.fakegeo.hook.system.WifiIdentity.copy(source, p)
                                        : io.github.fakegeo.hook.system.WifiIdentity.connected(source, p));
                                replacements.incrementAndGet();
                            } catch (Throwable failure) {
                                error(method.getName(), failure);
                                try { call.setResult(WifiIdentity.withoutIdentity(source)); fallbacks.incrementAndGet(); }
                                catch (Throwable fallbackFailure) {
                                    error("identity fallback", fallbackFailure);
                                    // Never return null: system callback bundling dereferences this result.
                                    call.setResult(XposedHelpers.newInstance(NetworkCapabilities.class)); fallbacks.incrementAndGet();
                                }
                            }
                        }
                    });
                    if (method.getName().equals("networkCapabilitiesRestrictedForCallerPermissions")) protectedRecipients = true;
                    coverage.add(type.getName() + "." + method.getName() + "(recipient UID)");
                } catch (Throwable failure) { error("install " + name, failure); }
            }
        }
    }
    private void error(String stage, Throwable failure) {
        String message = "connectivity " + stage + ": " + failure.getClass().getSimpleName();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: " + message); }
    }
    public boolean protectedRecipients() { return protectedRecipients; }
    public long replacements() { return replacements.get(); }
    public long fallbacks() { return fallbacks.get(); }
}
