package io.github.fakegeo.location;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RealLocationCommands;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.root.RuntimeGate;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground, user-requested lookup; never disables virtual positioning for other apps. */
public final class RealPositionFinder {
    public interface Callback { void found(double latitude,double longitude,float accuracy);void failed(String message); }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LocationManager manager;
    private final Callback callback;
    private final String token=UUID.randomUUID().toString().replace("-","");
    private volatile boolean finished,protectedLookup;
    private long started;
    private final LocationListener listener=new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            if(finished || protectedLookup || location==null) return;
            Profile p=ConfigStore.published(context);
            if(p.enabled && p.systemMode && p.location) { fail("定位环境已改变，请重新获取真实位置。");return; }
            long fix=location.getElapsedRealtimeNanos()/1000000,now=SystemClock.elapsedRealtime();
            boolean mock=android.os.Build.VERSION.SDK_INT>=31?location.isMock():location.isFromMockProvider();
            if(!mock && fix>=started-2000 && fix<=now && now-fix<=5000 && location.hasAccuracy())
                complete(location.getLatitude(),location.getLongitude(),location.getAccuracy());
        }
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) { if(LocationManager.GPS_PROVIDER.equals(provider)) fail("请开启手机系统定位，再重试。"); }
        @Override public void onStatusChanged(String provider,int status,Bundle extras) {}
    };
    public RealPositionFinder(Context context,Callback callback) {
        this.context=context.getApplicationContext();this.callback=callback;manager=context.getSystemService(LocationManager.class);
    }
    public void start() {
        try {
            if(manager==null || !manager.isLocationEnabled() || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                throw new IllegalStateException("请开启手机系统定位，再重试。");
            Profile p=ConfigStore.published(context);protectedLookup=p.enabled && p.systemMode && p.location;
            started=SystemClock.elapsedRealtime();main.postDelayed(()->fail("未获取到新鲜的真实 GPS 位置，请到开阔位置重试。"),35000);
            if(protectedLookup) worker.execute(()->protectedStart());
            else requestGps();
        } catch(Exception error) { fail(error.getMessage()); }
    }
    private void protectedStart() {
        try {
            JSONObject report=new JSONObject(RootBridge.execute(RootCommands.status()));
            RuntimeGate.requireCurrent(report,System.currentTimeMillis());
            RuntimeGate.requireRealGps(report);
            if(finished) return;
            RootBridge.execute(RealLocationCommands.begin(token,SystemClock.elapsedRealtime(),System.currentTimeMillis()));
            if(finished) return;
            main.post(()->{ if(!finished) { try { requestGps(); } catch(Exception error) { fail(error.getMessage()); } } });
            long deadline=SystemClock.elapsedRealtime()+30000;
            while(!finished && SystemClock.elapsedRealtime()<deadline) {
                String text=RootBridge.execute(RealLocationCommands.read(token));
                if(text.startsWith("{")) {
                    JSONObject result=new JSONObject(text);long fix=result.optLong("elapsed"),now=SystemClock.elapsedRealtime();
                    double lat=result.getDouble("latitude"),lon=result.getDouble("longitude"),accuracy=result.getDouble("accuracy");
                    if(token.equals(result.optString("token")) && fix>=started-2000 && fix<=now && now-fix<=5000
                            && Double.isFinite(lat) && lat>=-90 && lat<=90 && Double.isFinite(lon) && lon>=-180 && lon<=180
                            && Double.isFinite(accuracy) && accuracy>=0) { main.post(()->complete(lat,lon,(float)accuracy));return; }
                }
                Thread.sleep(500);
            }
            if(!finished) main.post(()->fail("真实 GPS 暂未返回，请到开阔位置重试。"));
        } catch(Exception error) { main.post(()->fail(error.getMessage())); }
        finally { if(finished) cleanupRoot(); }
    }
    private void requestGps() {
        if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("请允许 FakeGeo 使用精确定位权限。");
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,listener,Looper.getMainLooper());
    }
    private void complete(double lat,double lon,float accuracy) {
        if(finished) return;cancel();callback.found(lat,lon,accuracy);
    }
    private void fail(String message) {
        if(finished) return;cancel();callback.failed(message==null?"无法获取真实位置":message);
    }
    public void cancel() {
        if(finished) return;finished=true;main.removeCallbacksAndMessages(null);
        if(manager!=null) try { manager.removeUpdates(listener); } catch(RuntimeException ignored) {}
        if(protectedLookup) worker.execute(this::cleanupRoot);
        worker.shutdown();
    }
    private void cleanupRoot() { try { RootBridge.execute(RealLocationCommands.cancel(token)); } catch(Exception ignored) { /* System capture expires automatically; a token cannot activate a later request. */ } }
}
