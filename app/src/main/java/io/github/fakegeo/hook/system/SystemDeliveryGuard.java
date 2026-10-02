package io.github.fakegeo.hook.system;

import android.location.Location;
import io.github.fakegeo.config.Profile;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** System cache and delivery boundaries; retains the system's permission and completion paths. */
final class SystemDeliveryGuard {
    private static final String BINDING = "io.github.fakegeo.deliveryPermission";
    private final ServiceConfig config;
    private final List<String> coverage, errors;
    private final Set<Method> installed = new HashSet<>();
    private final AtomicLong cacheReads = new AtomicLong(), deliveries = new AtomicLong(), drops = new AtomicLong();
    SystemDeliveryGuard(ServiceConfig config, List<String> coverage, List<String> errors) {
        this.config=config; this.coverage=coverage; this.errors=errors;
    }
    private Profile active() {
        Profile p=config.get(); return p.enabled && p.systemMode && p.location ? p : null;
    }
    void installProvider(Class<?> type) {
        Method permitted = null;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] args=method.getParameterTypes();
            if (method.getName().equals("getPermittedLocation") && args.length==2
                    && args[0]==Location.class && args[1]==int.class && method.getReturnType()==Location.class) permitted=method;
            if ((method.getName().equals("getPermittedLocation") || method.getName().equals("getPermittedLocationResult"))
                    && args.length==2 && args[1]==int.class && locationType(args[0]) && method.getReturnType()==args[0]) {
                hook(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        Profile p=active(); if(p==null || call.args[0]==null) return;
                        try { call.args[0]=replacement(p, args[0], call.args[0], ProviderAccess.name(call.thisObject)); }
                        catch(Throwable failure) {
                            // Continuous registrations require a non-null permission result. Keep
                            // their contract here; the cache/transport guards suppress failed exits.
                            error("permission input", failure);
                        }
                    }
                });
            }
            if (method.getName().equals("getLastLocationUnsafe") && method.getReturnType()==Location.class
                    && java.util.Arrays.equals(args,new Class<?>[]{int.class,int.class,boolean.class,long.class})) {
                hook(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        Profile p=active(); if(p==null || call.hasThrowable() || call.getResult()==null) return;
                        try { call.setResult(replacement(p, Location.class, call.getResult(), ProviderAccess.name(call.thisObject))); cacheReads.incrementAndGet(); }
                        catch(Throwable failure) { error("cache read", failure); call.setResult(null); drops.incrementAndGet(); }
                    }
                });
            }
        }
        if(permitted!=null) installTransports(type,permitted);
    }
    private void installTransports(Class<?> managerType, Method permitted) {
        ClassLoader loader=managerType.getClassLoader();
        Class<?> registration=XposedHelpers.findClassIfExists(managerType.getName()+"$Registration",loader);
        Class<?> transport=XposedHelpers.findClassIfExists(managerType.getName()+"$LocationTransport",loader);
        if(registration==null || transport==null) return;
        for(Constructor<?> ctor:registration.getDeclaredConstructors()) {
            Class<?>[] args=ctor.getParameterTypes();
            int transportIndex=-1;
            for(int i=0;i<args.length;i++) if(args[i]==transport) transportIndex=i;
            if(transportIndex<0 || args[args.length-1]!=int.class) continue;
            int index=transportIndex;
            try {
                XposedBridge.hookMethod(ctor,new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        if(call.hasThrowable() || call.args[index]==null) return;
                        try {
                            Object manager=ProviderAccess.field(call.thisObject,"this$0");
                            int permission=((Number)call.args[call.args.length-1]).intValue();
                            XposedHelpers.setAdditionalInstanceField(call.args[index],BINDING,new Binding(manager,permission,permitted));
                        } catch(Throwable failure) { error("transport permission capture",failure); }
                    }
                });
                coverage.add(registration.getName()+".<init> (delivery permission)");
            } catch(Throwable failure) { error("registration hook",failure); }
        }
        for(String suffix:new String[]{"$LocationListenerTransport","$LocationPendingIntentTransport","$GetCurrentLocationTransport"}) {
            Class<?> type=XposedHelpers.findClassIfExists(managerType.getName()+suffix,loader);
            if(type==null) continue;
            for(Method method:type.getDeclaredMethods()) {
                Class<?>[] args=method.getParameterTypes();
                if(!method.getName().equals("deliverOnLocationChanged") || method.getReturnType()!=void.class
                        || args.length!=2 || !locationType(args[0]) || !args[1].getName().equals("android.os.IRemoteCallback")) continue;
                hook(method,new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        Profile p=active(); if(p==null || call.args[0]==null) return;
                        try {
                            Binding binding=(Binding)XposedHelpers.getAdditionalInstanceField(call.thisObject,BINDING);
                            if(binding==null) throw new IllegalStateException("Delivery permission not captured");
                            Object manager=binding.manager.get();
                            if(manager==null) throw new IllegalStateException("Provider manager unavailable");
                            Location fine=SystemLocations.create(p,SystemLocations.provider(call.args[0],ProviderAccess.name(manager)));
                            Object location=XposedBridge.invokeOriginalMethod(binding.permitted,manager,new Object[]{fine,binding.permission});
                            if(!(location instanceof Location)) throw new IllegalStateException("Permission conversion returned no location");
                            call.args[0]=SystemLocations.batch(args[0],(Location)location); deliveries.incrementAndGet();
                        } catch(Throwable failure) {
                            error("delivery replacement",failure); drops.incrementAndGet();
                            if(suffix.equals("$GetCurrentLocationTransport")) call.args[0]=null;
                            else {
                                // No stale/real result escapes, and the existing wakelock is released.
                                if(call.args[1]!=null) try { XposedHelpers.callMethod(call.args[1],"sendResult",new Object[]{null}); }
                                catch(Throwable completionFailure) { error("delivery completion",completionFailure); }
                                call.setResult(null);
                            }
                        }
                    }
                });
            }
        }
    }
    void installLegacy(ClassLoader loader) {
        Class<?> service=XposedHelpers.findClassIfExists("com.android.server.LocationManagerService",loader);
        if(service==null) return;
        // AOSP Android 10. Modern getLastLocation signatures remain with their provider manager.
        for(Method method:service.getDeclaredMethods()) {
            Class<?>[] args=method.getParameterTypes();
            if(!method.getName().equals("getLastLocation") || method.getReturnType()!=Location.class
                    || args.length!=2 || !args[0].getName().equals("android.location.LocationRequest") || args[1]!=String.class) continue;
            hook(method,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Profile p=active(); if(p==null || call.hasThrowable() || call.getResult()==null) return;
                    try {
                        int permission=((Number)XposedHelpers.callMethod(call.thisObject,"getCallerAllowedResolutionLevel")).intValue();
                        call.setResult(legacyLocation(p,(Location)call.getResult(),call.thisObject,permission)); cacheReads.incrementAndGet();
                    } catch(Throwable failure) { error("legacy cache read",failure); call.setResult(null); drops.incrementAndGet(); }
                }
            });
        }
        Class<?> receiver=XposedHelpers.findClassIfExists(service.getName()+"$Receiver",loader);
        if(receiver==null) return;
        for(Method method:receiver.getDeclaredMethods()) {
            if(!method.getName().equals("callLocationChangedLocked") || method.getReturnType()!=boolean.class
                    || !java.util.Arrays.equals(method.getParameterTypes(),new Class<?>[]{Location.class})) continue;
            hook(method,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    Profile p=active(); if(p==null || call.args[0]==null) return;
                    try {
                        int permission=((Number)ProviderAccess.field(call.thisObject,"mAllowedResolutionLevel")).intValue();
                        Object owner=ProviderAccess.field(call.thisObject,"this$0");
                        call.args[0]=legacyLocation(p,(Location)call.args[0],owner,permission); deliveries.incrementAndGet();
                    } catch(Throwable failure) {
                        error("legacy delivery",failure); drops.incrementAndGet(); call.setResult(true);
                    }
                }
            });
        }
    }
    private static Location legacyLocation(Profile p, Location source, Object service,int permission) throws Throwable {
        Location fine=SystemLocations.create(p,SystemLocations.provider(source,"fused"));
        if(permission==2) return fine;
        if(permission!=1) throw new SecurityException("Unsupported location permission");
        Object fudger=ProviderAccess.field(service,"mLocationFudger");
        return (Location)XposedHelpers.callMethod(fudger,"getOrCreate",fine);
    }
    private static Object replacement(Profile p,Class<?> type,Object source,String fallback) throws ReflectiveOperationException {
        return SystemLocations.batch(type,SystemLocations.create(p,SystemLocations.provider(source,fallback)));
    }
    private static boolean locationType(Class<?> type) { return type==Location.class || type.getName().equals("android.location.LocationResult"); }
    private void hook(Method method,XC_MethodHook callback) {
        if(Modifier.isAbstract(method.getModifiers()) || !installed.add(method)) return;
        try { XposedBridge.hookMethod(method,callback); coverage.add(method.getDeclaringClass().getName()+"."+method.getName()+" (delivery guard)"); }
        catch(Throwable failure) { error("hook "+method.getName(),failure); }
    }
    private void error(String stage,Throwable failure) {
        String message="location boundary "+stage+": "+failure.getClass().getSimpleName();
        if(errors.size()<12 && !errors.contains(message)) { errors.add(message); XposedBridge.log("FakeGeo: "+message); }
    }
    long cacheReads() { return cacheReads.get(); }
    long deliveries() { return deliveries.get(); }
    long drops() { return drops.get(); }
    private static final class Binding {
        final WeakReference<Object> manager; final int permission; final Method permitted;
        Binding(Object manager,int permission,Method permitted) { this.manager=new WeakReference<>(manager); this.permission=permission; this.permitted=permitted; }
    }
}
