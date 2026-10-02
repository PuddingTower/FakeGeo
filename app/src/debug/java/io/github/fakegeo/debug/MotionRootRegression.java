package io.github.fakegeo.debug;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;
import android.os.Looper;
import android.os.SystemClock;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.motion.MotionModel;
import io.github.fakegeo.motion.MotionPublisher;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.root.RootProfilePublisher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded movement experiment, explicitly authorized separately; always restores the profile. */
final class MotionRootRegression {
    private static final Pattern GPS=Pattern.compile("Location\\[gps\\s+(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)");
    static String run(Context context) throws Exception {
        return run(context,false,12000,5);
    }
    static String run(Context context,boolean disableAfter) throws Exception {
        return run(context,disableAfter,30000,8);
    }
    private static String run(Context context,boolean disableAfter,long duration,double speed) throws Exception {
        synchronized(ConfigStore.class) {
            if(io.github.fakegeo.motion.JoystickService.isRunning()) throw new IllegalStateException("请先关闭悬浮摇杆，再运行对照。");
            Profile original=ConfigStore.published(context);
            if(!original.systemMode || !original.location || original.motionLease!=0 || ConfigStore.load(context).revision!=original.revision)
                throw new IllegalStateException("Require synchronized stationary configuration");
            Profile owned=original;double cacheMoved=0,expectedMoved=0;int samples=0,matches=0;
            java.util.concurrent.atomic.AtomicReference<Profile> current=new java.util.concurrent.atomic.AtomicReference<>(original);
            java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();
            double[] callbackMax={0,0};
            LocationManager manager=context.getSystemService(LocationManager.class);
            LocationListener listener=new LocationListener() {
                @Override public void onLocationChanged(Location fix) {
                    Profile active=current.get();if(!active.enabled) return;
                    MotionModel.Sample expected=MotionModel.sample(active,SystemClock.elapsedRealtime(),System.currentTimeMillis());
                    if(distance(expected.latitude,expected.longitude,fix.getLatitude(),fix.getLongitude())<15) {
                        synchronized(callbackMax) {
                            callbacks.incrementAndGet();callbackMax[0]=Math.max(callbackMax[0],distance(original.latitude,original.longitude,fix.getLatitude(),fix.getLongitude()));
                            callbackMax[1]=Math.max(callbackMax[1],fix.getSpeed());
                        }
                    }
                }
                @Override public void onStatusChanged(String provider,int status,android.os.Bundle extras) {}
            };
            long start=SystemClock.elapsedRealtime();
            try {
                if(!original.enabled) {
                    RootProfilePublisher.publishUser(context,new Profile(original.json().put("enabled",true)));
                    owned=ConfigStore.published(context);
                }
                MotionPublisher.ready(context);
                long continuationsBefore=new org.json.JSONObject(RootBridge.execute(RootCommands.status())).optLong("motionCacheContinuations");
                if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("移动对照需要已授权的精确定位权限");
                manager.requestLocationUpdates("gps",1000,0,listener,Looper.getMainLooper());
                start=SystemClock.elapsedRealtime();
                while(SystemClock.elapsedRealtime()-start<duration) {
                    owned=MotionPublisher.step(context,owned.revision,speed,90,false);
                    current.set(owned);
                    Thread.sleep(1000);
                    MotionModel.Sample expected=MotionModel.sample(owned,SystemClock.elapsedRealtime(),System.currentTimeMillis());
                    expectedMoved=distance(original.latitude,original.longitude,expected.latitude,expected.longitude);
                    // Bound subprocess output before waiting: full dumpsys can fill its pipe.
                    String dump=RootBridge.execute("dumpsys location | grep -oE 'Location\\[gps[[:space:]]+-?[0-9]+[.][0-9]+,[[:space:]]*-?[0-9]+[.][0-9]+' | head -30\n");Matcher matcher=GPS.matcher(dump);
                    double nearest=Double.MAX_VALUE,lat=0,lon=0;
                    while(matcher.find()) {
                        double testLat=Double.parseDouble(matcher.group(1)),testLon=Double.parseDouble(matcher.group(2));
                        double d=distance(expected.latitude,expected.longitude,testLat,testLon);
                        if(d<nearest) { nearest=d;lat=testLat;lon=testLon; }
                    }
                    samples++;if(nearest<15) { matches++;cacheMoved=Math.max(cacheMoved,distance(original.latitude,original.longitude,lat,lon)); }
                }
                long continuationsAfter=new org.json.JSONObject(RootBridge.execute(RootCommands.status())).optLong("motionCacheContinuations");
                synchronized(callbackMax) {
                    return String.format(java.util.Locale.US,"MOTION_ROOT_TEST: samples=%d cacheMatches=%d expectedMovedMetres=%.2f systemGpsMovedMetres=%.2f matchingGpsCallbacks=%d gpsCallbackMovedMetres=%.2f callbackMaxSpeed=%.2f retainedMotionCaches=%d",samples,matches,expectedMoved,cacheMoved,callbacks.get(),callbackMax[0],callbackMax[1],continuationsAfter-continuationsBefore);
                }
            } finally {
                current.set(original);
                try { if(manager!=null) manager.removeUpdates(listener); } catch(RuntimeException ignored) {}
                RootProfilePublisher.publishUser(context,disableAfter?new Profile(original.json().put("enabled",false)):original);
            }
        }
    }
    private static double distance(double lat,double lon,double nextLat,double nextLon) {
        float[] result={0};Location.distanceBetween(lat,lon,nextLat,nextLon,result);return result[0];
    }
}
