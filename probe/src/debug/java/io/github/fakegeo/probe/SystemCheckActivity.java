package io.github.fakegeo.probe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.CancellationSignal;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** ADB-only ten-second foreground check. Uses only system APIs, never module configuration IPC. */
public final class SystemCheckActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private long startedAt;
    private TextView text;
    private boolean checking;
    private final ArrayList<CancellationSignal> cancellations = new ArrayList<>();
    private final JSONObject record = new JSONObject();
    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location value) {
            try {
                if (!record.has("firstLocationMs")) record.put("firstLocationMs", SystemClock.elapsedRealtime()-startedAt);
                record.put("locationCallbacks", record.optInt("locationCallbacks")+1);
                record.put("pointHash", pointHash(value.getLatitude(), value.getLongitude()));
                record.put("provider", value.getProvider());
                record.put("mock", Build.VERSION.SDK_INT >= 31 ? value.isMock() : value.isFromMockProvider());
                record.put("locationSatellites", value.getExtras()==null ? -1 : value.getExtras().getInt("satellites", -1));
                observe("continuous", value);
                save();
            } catch (Exception failure) { fail(failure); }
        }
        @Override public void onProviderEnabled(String name) {}
        @Override public void onProviderDisabled(String name) {}
        @Override public void onStatusChanged(String name, int status, Bundle extras) {}
    };
    private final GnssStatus.Callback status = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus value) {
            try {
                if (!record.has("firstGnssMs")) record.put("firstGnssMs", SystemClock.elapsedRealtime()-startedAt);
                int used=0; for (int i=0; i<value.getSatelliteCount(); i++) if (value.usedInFix(i)) used++;
                record.put("statusCallbacks", record.optInt("statusCallbacks")+1);
                record.put("satelliteCount", value.getSatelliteCount()); record.put("usedInFix", used); save();
            } catch (Exception failure) { fail(failure); }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this); text.setText("系统层验证需要精确定位权限。\n此探针无需加入 LSPosed 作用域。"); setContentView(text);
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 41);
            return;
        }
        startCheck();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code!=41) return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) startCheck();
        else { text.setText("未获得精确定位权限，验证未运行。"); fail(new SecurityException("Fine location permission required")); }
    }
    private void startCheck() {
        if(checking) return;
        if(getIntent().getBooleanExtra("prepare",false)) {
            try { record.put("permissionReady",true); save(); } catch(Exception failure) { fail(failure); }
            text.setText("精确定位权限已就绪，等待系统验证。"); return;
        }
        checking=true; text.setText("系统层验证中，10 秒后结束。\n检查持续、最近和单次位置。");
        try {
            Location own = new Location("gps"); own.setLatitude(1); own.setLongitude(2);
            record.put("constructedUnchanged", own.getLatitude()==1 && own.getLongitude()==2);
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Fine location permission required");
            startedAt = SystemClock.elapsedRealtime();
            manager.requestLocationUpdates("gps", 500, 0, listener, Looper.getMainLooper());
            record.put("statusRegistered", manager.registerGnssStatusCallback(status, handler)); save();
            sample();
            handler.postDelayed(() -> { handler.removeCallbacksAndMessages(null); stop();
                try { record.put("completed",true); save(); } catch(Exception failure) { fail(failure); }
                text.setText("系统验证已结束；结果仅存于探针私有目录。"); }, 10000);
        } catch (Exception failure) { fail(failure); }
    }
    private void sample() {
        if(!checking) return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            fail(new SecurityException("Fine location permission revoked")); return;
        }
        try {
            observe("last",manager.getLastKnownLocation("gps"));
            if(Build.VERSION.SDK_INT>=30) {
                CancellationSignal signal=new CancellationSignal(); cancellations.add(signal);
                manager.getCurrentLocation("gps",signal,getMainExecutor(), value -> {
                    try { observe("current",value); save(); } catch(Exception failure) { fail(failure); }
                });
            }
            save();
        } catch(Exception failure) { fail(failure); }
        handler.postDelayed(this::sample,1000);
    }
    private void observe(String kind,Location value) throws Exception {
        if(value==null) { record.put(kind+"Nulls",record.optInt(kind+"Nulls")+1); return; }
        record.put(kind+"Count",record.optInt(kind+"Count")+1);
        String hash=pointHash(value.getLatitude(),value.getLongitude());
        JSONArray hashes=record.optJSONArray(kind+"Hashes");
        if(hashes==null) { hashes=new JSONArray(); record.put(kind+"Hashes",hashes); }
        boolean found=false; for(int i=0;i<hashes.length();i++) if(hash.equals(hashes.getString(i))) found=true;
        if(!found && hashes.length()<32) hashes.put(hash);
    }
    private static String pointHash(double lat, double lon) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(String.format(Locale.US,"%.8f,%.8f",lat,lon).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder(); for(byte value:bytes) out.append(String.format(Locale.US,"%02x",value & 255)); return out.toString();
    }
    private void save() {
        getSharedPreferences("system_check", MODE_PRIVATE).edit().putString("result", record.toString()).commit();
    }
    private void fail(Exception failure) {
        try { record.put("error", failure.getClass().getSimpleName()); save(); } catch(Exception ignored) {}
    }
    private void stop() {
        checking=false;
        for(CancellationSignal signal:cancellations) signal.cancel(); cancellations.clear();
        if(manager!=null) try { manager.removeUpdates(listener); manager.unregisterGnssStatusCallback(status); }
        catch(SecurityException denied) { fail(denied); }
    }
    @Override public void onStop() { handler.removeCallbacksAndMessages(null); stop(); super.onStop(); }
}
