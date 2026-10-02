package io.github.fakegeo.hook.system;

import android.location.Location;
import android.os.Handler;
import android.os.SystemClock;
import android.system.Os;
import io.github.fakegeo.location.RealGpsRequest;
import io.github.fakegeo.root.RealLocationCommands;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Capture before replacement, only during an explicit root-private request. No app exemptions. */
final class RealGpsStore {
    private final Handler worker;
    private volatile RealGpsRequest request;
    private String completed="";
    RealGpsStore(Handler worker) {
        this.worker=worker;worker.post(new Runnable() {
            @Override public void run() { refresh();worker.postDelayed(this,500); }
        });
    }
    private void refresh() {
        try {
            Path path=new java.io.File(RealLocationCommands.REQUEST).toPath();
            if(Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path)>1024) { request=null;return; }
            RealGpsRequest next=new RealGpsRequest(new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)));
            boolean active=next.active(SystemClock.elapsedRealtime(),System.currentTimeMillis());
            request=active && !next.token.equals(completed)?next:null;
            if(!active) clearResult(next.token);
        } catch(Exception ignored) { request=null; }
    }
    void capture(Object batch) {
        RealGpsRequest current=request;if(current==null) return;
        try {
            Location source=null;
            if(batch instanceof Location) source=(Location)batch;
            else if(batch instanceof Location[]) { Location[] values=(Location[])batch;if(values.length>0) source=values[values.length-1]; }
            else if(batch instanceof java.util.List) { java.util.List<?> values=(java.util.List<?>)batch;if(!values.isEmpty() && values.get(values.size()-1) instanceof Location) source=(Location)values.get(values.size()-1); }
            else if(batch!=null && batch.getClass().getName().equals("android.location.LocationResult")) source=(Location)batch.getClass().getMethod("getLastLocation").invoke(batch);
            if(source==null || !"gps".equals(source.getProvider()) || !source.hasAccuracy()) return;
            if(source.getExtras()!=null && source.getExtras().containsKey("mockLocation")) return;
            long now=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis(),fix=source.getElapsedRealtimeNanos()/1000000;
            boolean mock=android.os.Build.VERSION.SDK_INT>=31?source.isMock():source.isFromMockProvider();
            if(!current.accepts(fix,now,wall,mock,source.getLatitude(),source.getLongitude(),source.getAccuracy())) return;
            JSONObject result=new JSONObject().put("token",current.token).put("latitude",source.getLatitude())
                    .put("longitude",source.getLongitude()).put("accuracy",source.getAccuracy()).put("elapsed",fix);
            request=null;
            worker.post(()->{
                Path target=new java.io.File(RealLocationCommands.RESULT).toPath(),temp=new java.io.File(RealLocationCommands.RESULT+".new").toPath();
                try {
                    refresh();RealGpsRequest pending=request;
                    if(pending==null || !pending.token.equals(current.token) || Files.isSymbolicLink(target) || Files.isSymbolicLink(temp)) return;
                    Files.write(temp,result.toString().getBytes(StandardCharsets.UTF_8));Os.chmod(temp.toString(),0600);
                    Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                    completed=current.token;request=null;
                } catch(Exception ignored) { /* Never affect the system's location report on capture failure. */ }
                finally { try { Files.deleteIfExists(temp); } catch(Exception ignored) {} }
            });
        } catch(Exception ignored) { /* Optional real-map lookup must not interrupt virtual delivery. */ }
    }
    private void clearResult(String token) {
        try {
            Path result=new java.io.File(RealLocationCommands.RESULT).toPath();
            if(Files.isSymbolicLink(result) || !Files.isRegularFile(result) || Files.size(result)>1024) return;
            JSONObject json=new JSONObject(new String(Files.readAllBytes(result),StandardCharsets.UTF_8));
            if(token.equals(json.optString("token"))) Files.deleteIfExists(result);
        } catch(Exception ignored) {}
    }
}
