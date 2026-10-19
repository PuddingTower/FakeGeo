package io.github.fakegeo.hook.system;

import android.os.Binder;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootCommands;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Replaces provider reports in system_server; leaves app registration and permission filtering intact. */
public final class ServerHooks {
    private static final String QUEUED_REVISION = "io.github.fakegeo.queuedRevision";
    private final ClassLoader loader;
    private final ServiceConfig config;
    private final Map<String, Endpoint> providers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> coverage = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();
    private long nextLocation, delivered, nextStatus, observedRevision;
    private boolean previouslyActive;
    private Profile observedProfile;
    private SystemGnss gnss;
    private SystemDeliveryGuard delivery;
    private RadioServiceHooks radio;
    private RealGpsStore realGps;
    private ConfigBinderBridge bridge;
    private OplusLocationHooks vendor;
    private OplusKeepAliveHooks keepAlive;
    private OplusClassLoaderResolver resolver;
    public ServerHooks(ClassLoader loader) { this.loader = loader; config = new ServiceConfig(null, true); }
    public void install() {
        realGps=new RealGpsStore(config.worker);
        delivery = new SystemDeliveryGuard(config, coverage, errors);
        bridge = ConfigBinderBridge.install(config, loader, coverage, errors);
        vendor = new OplusLocationHooks(config, coverage, errors);
        resolver = OplusClassLoaderResolver.install(loader, coverage, errors, vendor::inspect);
        keepAlive = new OplusKeepAliveHooks(config, coverage, errors); keepAlive.install(loader);
        for (String name : new String[]{
                "com.android.server.location.provider.LocationProviderManager",
                "com.android.server.location.LocationProviderManager",
                "com.android.server.LocationManagerService$LocationProviderManager",
                "com.android.server.location.LocationManagerService$LocationProviderManager",
                "com.android.server.LocationManagerService$LocationProvider",
                "com.android.server.location.LocationManagerService$LocationProvider"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type != null) { installProvider(type); delivery.installProvider(type); }
        }
        delivery.installLegacy(loader);
        installLegacyQueueGuard();
        radio = new RadioServiceHooks(config, loader, coverage, errors); radio.installFramework();
        gnss = new SystemGnss(config, coverage, errors); gnss.install(loader);
        config.worker.post(new Runnable() {
            @Override public void run() {
                try { tick(); } catch (Throwable failure) { failure("tick", failure); }
                config.worker.postDelayed(this, 250);
            }
        });
        XposedBridge.log("FakeGeo: system_server entry loaded, API " + Build.VERSION.SDK_INT);
    }
    private void installProvider(Class<?> type) {
        Method preferred = null;
        for (Method method : type.getDeclaredMethods()) {
            // Android 16 adds batch overloads (onReportLocations) and internal report handlers,
            // so the Location parameter is located instead of assumed to be first.
            int slot = reportSlot(method);
            if (slot < 0) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        try { if(slot == 0 && "gps".equals(providerName(call.thisObject))) realGps.capture(call.args[0]); }
                        catch(Throwable ignored) { /* Explicit real-position lookup never blocks virtual delivery. */ }
                        Profile p = config.get();
                        if (!p.enabled || !p.systemMode || !p.location) return;
                        String name = providerName(call.thisObject);
                        if (!supportedProvider(name)) return;
                        try { call.args[slot] = SystemLocations.batch(method.getParameterTypes()[slot], report(p,
                                name.equals("passive") ? SystemLocations.provider(call.args[slot], name) : name,
                                method.getParameterTypes()[slot] == android.location.Location.class)); }
                        catch (Throwable failure) { failure("report " + name, failure); call.setResult(null); }
                    }
                });
                // Only a single-parameter report can be driven directly by the heartbeat loop.
                if (method.getParameterCount() == 1 && (preferred == null || rank(method) < rank(preferred))) preferred = method;
                coverage.add(type.getName() + "." + method.getName() + "(" + method.getParameterTypes()[slot].getSimpleName() + ")");
            } catch (Throwable failure) { failure(type.getName(), failure); }
        }
        if (preferred != null) {
            Method heartbeat = preferred;
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    String name = providerName(call.thisObject);
                    if (supportedProvider(name)) providers.put(name, new Endpoint(call.thisObject, heartbeat, name));
                }
            });
        }
    }
    private static int rank(Method method) {
        return ProviderAccess.reportPriority(method);
    }
    /** Index of the Location-shaped parameter of a report method, or -1 when this is not a report. */
    private static int reportSlot(Method method) {
        if (java.lang.reflect.Modifier.isAbstract(method.getModifiers()) || method.getReturnType() != void.class) return -1;
        String name = method.getName();
        if (!name.startsWith("onReport") && !name.startsWith("handleReportLocation")) return -1;
        for (int index = 0; index < method.getParameterCount(); index++)
            if (locationType(method.getParameterTypes()[index])) return index;
        return -1;
    }
    private void tick() throws Exception {
        long now = SystemClock.elapsedRealtime(); Profile p = config.get();
        boolean active = p.enabled && p.systemMode && p.location;
        // Deliver GNSS quality before GPS reports so SDKs receive a coherent fix.
        gnss.tick(p, now);
        if (p.revision != observedRevision || active != previouslyActive) {
            nextLocation = now;
            // Coarse caches otherwise keep the previous real/virtual point for several minutes.
            if (ProviderCachePolicy.clear(observedProfile,p)) clearVirtualCaches();
        }
        observedRevision = p.revision; previouslyActive = active;
        observedProfile=p;
        if (active && now >= nextLocation) {
            nextLocation = now + p.interval;
            for (Endpoint endpoint : providers.values()) {
                if (endpoint.name.equals("passive")) continue;
                Object manager = endpoint.manager.get();
                if (manager == null) { providers.remove(endpoint.name, endpoint); continue; }
                try {
                    // A real provider and the normal location switch must be available.
                    Object lock = lock(manager);
                    Object value = SystemLocations.batch(endpoint.method.getParameterTypes()[0], report(p, endpoint.name,
                            endpoint.method.getParameterTypes()[0] == android.location.Location.class));
                    long identity = Binder.clearCallingIdentity();
                    try {
                        synchronized (lock) {
                            if (!enabled(manager)) continue;
                            XposedBridge.invokeOriginalMethod(endpoint.method, manager, new Object[]{value});
                        }
                    } finally { Binder.restoreCallingIdentity(identity); }
                    delivered++;
                } catch (Throwable failure) { failure("heartbeat " + endpoint.name, failure); }
            }
        }
        if (now >= nextStatus) { nextStatus = now + 2000; status(p); }
    }
    private static android.location.Location report(Profile p, String name, boolean queueGuard) {
        android.location.Location location = SystemLocations.create(p, name);
        if (Build.VERSION.SDK_INT == 29 && queueGuard) {
            android.os.Bundle extras = location.getExtras();
            extras.putLong(QUEUED_REVISION, p.revision); location.setExtras(extras);
        }
        return location;
    }
    private void installLegacyQueueGuard() {
        if (Build.VERSION.SDK_INT != 29) return;
        Class<?> service = XposedHelpers.findClassIfExists("com.android.server.LocationManagerService", loader);
        if (service == null) return;
        for (Method method : service.getDeclaredMethods()) {
            if (!method.getName().equals("handleLocationChangedLocked") || method.getParameterCount() != 2
                    || method.getParameterTypes()[0] != android.location.Location.class) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    android.location.Location location = (android.location.Location) call.args[0];
                    android.os.Bundle extras = location.getExtras();
                    if (extras == null || !extras.containsKey(QUEUED_REVISION)) return;
                    Profile p = config.get();
                    if (!config.active() || !p.location || extras.getLong(QUEUED_REVISION) != p.revision) {
                        call.setResult(null); return;
                    }
                    // Keep the marker private, including locations forwarded to passive clients.
                    extras.remove(QUEUED_REVISION); location.setExtras(extras);
                }
            });
            coverage.add(service.getName() + ".handleLocationChangedLocked(queue guard)");
        }
    }
    private void clearVirtualCaches() {
        for (Endpoint endpoint : providers.values()) {
            Object manager = endpoint.manager.get(); if (manager == null) continue;
            try {
                synchronized (lock(manager)) {
                    boolean ownCache = false;
                    for (String field : new String[]{"mLastLocations", "mLastLocation", "mLastCoarseLocation"}) {
                        try { XposedHelpers.callMethod(XposedHelpers.getObjectField(manager, field), "clear"); ownCache = true; }
                        catch (Throwable absent) {}
                    }
                    if (!ownCache) {
                        Object outer = XposedHelpers.getObjectField(manager, "this$0");
                        for (String field : new String[]{"mLastLocation", "mLastLocationCoarseInterval"}) {
                            try { XposedHelpers.callMethod(XposedHelpers.getObjectField(outer, field), "remove", endpoint.name); }
                            catch (Throwable absent) {}
                        }
                    }
                }
            } catch (Throwable failure) { failure("clear cache " + endpoint.name, failure); }
        }
    }
    private void status(Profile p) throws Exception {
        if (!new File(RootCommands.DIRECTORY).isDirectory()) return;
        JSONObject status = new JSONObject();
        status.put("moduleVersion", io.github.fakegeo.config.ModuleVersion.CURRENT);
        status.put("realGpsCaptureSupported",true);
        status.put("api", Build.VERSION.SDK_INT); status.put("pid", android.os.Process.myPid());
        status.put("reportedAt", System.currentTimeMillis()); status.put("revision", p.revision);
        status.put("enabled", config.active()); status.put("configError", config.error());
        status.put("providers", new JSONArray(providers.keySet())); status.put("hooks", new JSONArray(coverage));
        status.put("reportCount", delivered); status.put("errors", new JSONArray(errors));
        status.put("gnss", p.gnss); status.put("gnssReportCount", gnss.reports());
        status.put("gnssCaptured", gnss.captured());
        status.put("environment", p.environment == null ? "" : p.environment.id);
        status.put("wifiAnchors", p.wifiPoints.size());
        status.put("bleAnchors", p.blePoints.size());
        status.put("cellAnchors", p.cellPoints.size());
        status.put("cacheReadReplacements", delivery.cacheReads());
        status.put("deliveryReplacements", delivery.deliveries());
        status.put("deliveryDrops", delivery.drops());
        status.put("wifiConnectivityProtected", radio.connectivityProtected());
        status.put("wifiConnectivityReplacements", radio.connectivityReplacements());
        status.put("wifiConnectivityFallbacks", radio.connectivityFallbacks());
        status.put("configBridgeServed", bridge.served()); status.put("configBridgeDenied", bridge.deniedCount());
        status.put("vendorLocationReplacements", vendor.replacements());
        status.put("vendorServicesCaptured", resolver.services());
        status.put("keepAliveFreezesSkipped", keepAlive.skipped());
        File temp = new File(RootCommands.DIRECTORY, "status.new");
        Files.write(temp.toPath(), status.toString().getBytes(StandardCharsets.UTF_8)); Os.chmod(temp.getPath(), 0600);
        Files.move(temp.toPath(), new File(RootCommands.STATUS).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private static boolean locationType(Class<?> type) {
        return type == android.location.Location.class || type == android.location.Location[].class
                || type.getName().equals("android.location.LocationResult") || java.util.List.class.isAssignableFrom(type);
    }
    private static String providerName(Object manager) {
        try { return ProviderAccess.name(manager); }
        catch (ReflectiveOperationException unavailable) { return ""; }
    }
    private static boolean supportedProvider(String name) {
        return name.equals("gps") || name.equals("network") || name.equals("fused") || name.equals("passive");
    }
    private static boolean enabled(Object manager) throws ReflectiveOperationException {
        return ProviderAccess.enabled(manager);
    }
    private static Object lock(Object manager) throws ReflectiveOperationException {
        return ProviderAccess.lock(manager);
    }
    private void failure(String stage, Throwable cause) {
        String message = stage + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (errors.size() < 12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: " + message); }
    }
    private static final class Endpoint {
        final WeakReference<Object> manager; final Method method; final String name;
        Endpoint(Object manager, Method method, String name) { this.manager = new WeakReference<>(manager); this.method = method; this.name = name; }
    }
}
