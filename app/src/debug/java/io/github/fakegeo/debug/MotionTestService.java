package io.github.fakegeo.debug;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import org.json.JSONObject;

/** DUMP-only, fixed short movement experiment; retains Keep's foreground activity. */
public final class MotionTestService extends Service {
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("fakegeo_motion_test","临时移动诊断",NotificationManager.IMPORTANCE_LOW));
        Notification notification=new Notification.Builder(this,"fakegeo_motion_test")
                .setSmallIcon(io.github.fakegeo.R.drawable.ic_launcher).setContentTitle("FakeGeo 移动对照")
                .setContentText("约 30 秒，结束恢复原选点并关闭虚拟定位").setOngoing(true).build();
        if(android.os.Build.VERSION.SDK_INT>=34) startForeground(36,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(36,notification);
        new Thread(()->{
            try {
                String result;
                try { result=MotionRootRegression.run(this,true); }
                catch(Exception failure) { result=failure.toString(); }
                JSONObject record=new JSONObject().put("action","io.github.fakegeo.DEBUG_MOTION_PUBLISH_TEST")
                        .put("finishedAt",System.currentTimeMillis()).put("result",result);
                getSharedPreferences("debug_diagnostics",MODE_PRIVATE).edit().putString("record",record.toString()).commit();
            } catch(Exception failure) { android.util.Log.e("FakeGeo","Motion test failed",failure); }
            finally { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(); }
        },"FakeGeo-motion-test").start();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
}
