package io.github.fakegeo.motion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.ui.MainActivity;
import io.github.fakegeo.ui.UiKit;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-started foreground overlay; no restart-on-boot, no background movement without a live lease. */
public final class JoystickService extends Service {
    private static final String CLOSE="io.github.fakegeo.motion.CLOSE",CHANNEL="fakegeo_joystick";
    private static volatile boolean running;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private JoystickPanel panel;
    private volatile boolean closed,busy,ready;
    private volatile double magnitude,bearing,targetKmh;
    private volatile Profile owned;
    private boolean screenReceiverRegistered;
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { panel.release();queue(true); } }
    };
    private final Runnable pulse=new Runnable() {
        @Override public void run() {
            if(closed) return;
            if(ready && owned!=null) {
                Profile current=ConfigStore.published(JoystickService.this);
                if(!current.enabled || !current.location || current.revision!=owned.revision) {
                    // Ignore our short commit window while worker publishes its new revision.
                    if(!busy) { fail("定位配置已改变，悬浮摇杆已关闭。");return; }
                }
                if(!Settings.canDrawOverlays(JoystickService.this)) { fail("悬浮窗权限已关闭。");return; }
                if(magnitude>0 || owned.motionLease>0) queue(magnitude==0);
                MotionModel.Sample sample=MotionModel.sample(owned,SystemClock.elapsedRealtime(),System.currentTimeMillis());
                panel.status.setText(String.format(Locale.CHINA,"%s · %.1f km/h",magnitude>0?"移动中":"松手停止",sample.speed*3.6));
            }
            ui.postDelayed(this,1000);
        }
    };
    public static boolean isRunning() { return running; }
    @Override public void onCreate() {
        super.onCreate();
        MotionDiagnostics.record(this,"opened",0,0,0,null);
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"悬浮移动控制",NotificationManager.IMPORTANCE_LOW));
            Intent open=new Intent(this,MainActivity.class);
            PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent close=PendingIntent.getService(this,1,new Intent(this,JoystickService.class).setAction(CLOSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(io.github.fakegeo.R.drawable.ic_launcher)
                    .setContentTitle("FakeGeo 悬浮摇杆").setContentText("摇杆松手即停；关闭控制窗停止移动").setOngoing(true)
                    .setContentIntent(content).addAction(new Notification.Action.Builder(null,"关闭摇杆",close).build()).build();
            if(android.os.Build.VERSION.SDK_INT>=34) startForeground(35,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(35,n);
            if(!Settings.canDrawOverlays(this)) throw new IllegalStateException("请先允许 FakeGeo 显示悬浮窗。");
            buildOverlay();running=true;
            IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver,filter);
            screenReceiverRegistered=true;
            worker.execute(()->{
                try { owned=MotionPublisher.ready(this);MotionDiagnostics.record(this,"ready",0,0,owned.revision,null);ui.post(()->{ if(!closed) { ready=true;panel.joystick.setEnabled(true);panel.status.setText("就绪 · 松手停止");ui.post(pulse); } }); }
                catch(Exception error) { ui.post(()->fail(error.getMessage())); }
            });
        } catch(Exception error) { fail(error.getMessage()); }
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null && CLOSE.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }
    private void buildOverlay() {
        windows=getSystemService(WindowManager.class);
        panel=new JoystickPanel(this,new JoystickPanel.Listener() {
            @Override public void motion(double amount,double direction,double kmh) {
                if((magnitude>0)!=(amount>0)) MotionDiagnostics.record(JoystickService.this,amount>0?"gesture":"released",amount,kmh,owned==null?0:owned.revision,"ready="+ready);
                magnitude=amount;targetKmh=kmh;if(amount>0) bearing=direction;
                if(ready && (amount==0 || owned==null || owned.motionLease==0)) queue(amount==0);
            }
            @Override public void close() { stopSelf(); }
        });
        panel.handle.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downX,downY;int startX,startY;
            @Override public boolean onTouch(android.view.View view,MotionEvent e) {
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN) { panel.release();downX=e.getRawX();downY=e.getRawY();startX=params.x;startY=params.y;return true; }
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE) { params.x=startX+Math.round(e.getRawX()-downX);params.y=startY+Math.round(e.getRawY()-downY);clampWindow();windows.updateViewLayout(panel,params);return true; }
                if(e.getActionMasked()==MotionEvent.ACTION_UP) { view.performClick();return true; }return true;
            }
        });
        params=new WindowManager.LayoutParams(dp(246),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.x=dp(16);params.y=dp(140);clampWindow();windows.addView(panel,params);
    }
    private void queue(boolean stop) {
        if(closed || !ready || busy || owned==null || (stop && owned.motionLease==0)) return;
        busy=true;
        worker.execute(()->{
            try {
                if(!closed) {
                    double amount=magnitude;
                    owned=MotionPublisher.step(this,owned.revision,targetKmh/3.6*amount,bearing,amount==0);
                    MotionDiagnostics.record(this,"published",amount,targetKmh,owned.revision,"lease="+owned.motionLease);
                }
            } catch(Exception error) { ui.post(()->fail(error.getMessage())); }
            finally { busy=false;ui.post(()->{ if(!closed && magnitude==0 && owned!=null && owned.motionLease>0) queue(true); }); }
        });
    }
    private void clampWindow() {
        android.util.DisplayMetrics m=getResources().getDisplayMetrics();
        params.x=Math.max(0,Math.min(params.x,Math.max(0,m.widthPixels-dp(246))));
        int height=panel.getHeight();
        if(height==0) { panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(dp(246),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));height=panel.getMeasuredHeight(); }
        params.y=Math.max(dp(24),Math.min(params.y,Math.max(dp(24),m.heightPixels-height-dp(24))));
    }
    private void fail(String message) { if(closed) return;MotionDiagnostics.record(this,"failed",magnitude,targetKmh,owned==null?0:owned.revision,message);Toast.makeText(this,message==null?"悬浮移动无法启动":message,Toast.LENGTH_LONG).show();stopSelf(); }
    @Override public void onDestroy() {
        MotionDiagnostics.record(this,"closed",magnitude,targetKmh,owned==null?0:owned.revision,null);
        closed=true;running=false;ready=false;magnitude=0;ui.removeCallbacksAndMessages(null);
        if(screenReceiverRegistered) unregisterReceiver(screenReceiver);
        if(windows!=null && panel!=null && panel.isAttachedToWindow()) windows.removeView(panel);
        worker.execute(()->{ try { Profile p=owned;if(p!=null && p.motionLease>0) MotionPublisher.step(this,p.revision,0,0,true); } catch(Exception ignored) { /* A lease expires within six seconds; never overwrite a newer configuration. */ } });
        worker.shutdown();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public void onTaskRemoved(Intent rootIntent) { stopSelf(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int value) { return UiKit.dp(this,value); }
}
