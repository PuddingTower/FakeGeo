package io.github.fakegeo.motion;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.location.Location;
import android.os.Parcel;
import io.github.fakegeo.config.Profile;
import org.json.JSONObject;

/** Dependency-free device checks for gesture cancellation and real Android Location/parcel fields. */
public final class MotionInstrumentation extends Instrumentation {
    private volatile Throwable error;
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        runOnMainSync(()->{ try { gestures();lockedGestures();panel();androidLocations(); } catch(Throwable failure) { error=failure; } });
        if(error==null) { try { serviceExit(); } catch(Throwable failure) { error=failure; } }
        if(error!=null) { result.putString("stream","MOTION_CHECK_FAILED: "+error);finish(Activity.RESULT_CANCELED,result); }
        else { result.putString("stream","MOTION_CHECKS_PASSED="+checks);finish(Activity.RESULT_OK,result); }
    }
    private void gestures() {
        Context c=getTargetContext();double[] command={-1,-1};
        JoystickView view=new JoystickView(c,(amount,bearing)->{ command[0]=amount;command[1]=bearing; });
        FrameLayout parent=new FrameLayout(c);parent.addView(view,new FrameLayout.LayoutParams(200,200));view.layout(0,0,200,200);
        touch(view,MotionEvent.ACTION_DOWN,100,0);require(command[0]>0.9 && Math.abs(command[1])<0.001,"north");
        touch(view,MotionEvent.ACTION_MOVE,200,100);require(command[0]>0.9 && Math.abs(command[1]-90)<0.001,"east");
        touch(view,MotionEvent.ACTION_MOVE,100,200);require(Math.abs(command[1]-180)<0.001,"south");
        touch(view,MotionEvent.ACTION_MOVE,0,100);require(Math.abs(command[1]-270)<0.001,"west");
        touch(view,MotionEvent.ACTION_UP,0,100);require(command[0]==0,"release stops");
        touch(view,MotionEvent.ACTION_DOWN,100,100);require(command[0]==0,"centre dead zone");
        touch(view,MotionEvent.ACTION_MOVE,100,0);touch(view,MotionEvent.ACTION_CANCEL,100,0);require(command[0]==0,"cancel stops");
        touch(view,MotionEvent.ACTION_MOVE,100,0);require(command[0]==0,"cancelled gesture cannot resume");
        touch(view,MotionEvent.ACTION_DOWN,100,0);view.release();touch(view,MotionEvent.ACTION_MOVE,100,0);require(command[0]==0,"pause remains stopped until new down");
        view.setEnabled(false);touch(view,MotionEvent.ACTION_DOWN,100,0);require(command[0]==0,"disabled controls ignored");
    }
    private void androidLocations() throws Exception {
        long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        Profile p=new Profile(new JSONObject().put("enabled",true).put("jitter",false).put("latitude",31.2).put("longitude",121.5)
                .put("motionElapsed",elapsed-2000).put("motionWall",wall-2000).put("motionLease",6000)
                .put("motionSpeed",3).put("motionInitialSpeed",3).put("motionBearing",90));
        for(String className:new String[]{"io.github.fakegeo.hook.system.SystemLocations","io.github.fakegeo.hook.LocationFactory"}) {
            Class<?> type=Class.forName(className);java.lang.reflect.Method create=type.getDeclaredMethod("create",Profile.class,String.class);create.setAccessible(true);
            Location fix=(Location)create.invoke(null,p,"gps");
            require(fix.getLongitude()>p.longitude,"Android location moves east");
            require(fix.hasSpeed() && fix.getSpeed()>2 && fix.getSpeed()<4,"Android speed field");
            require(fix.hasBearing() && fix.getBearing()==90,"Android bearing field");
            require(fix.getTime()>0 && fix.getElapsedRealtimeNanos()>0,"Android fresh timestamps");
            Parcel parcel=Parcel.obtain();try {
                fix.writeToParcel(parcel,0);parcel.setDataPosition(0);Location copy=Location.CREATOR.createFromParcel(parcel);
                require(copy.getLatitude()==fix.getLatitude() && copy.getLongitude()==fix.getLongitude() && copy.getSpeed()==fix.getSpeed() && copy.getBearing()==fix.getBearing(),"parcel preserves motion");
            } finally { parcel.recycle(); }
        }
        Profile jitter=new Profile(new JSONObject().put("enabled",true).put("jitterMeters",2));
        java.lang.reflect.Method create=Class.forName("io.github.fakegeo.hook.system.SystemLocations").getDeclaredMethod("create",Profile.class,String.class);create.setAccessible(true);
        Location fix=(Location)create.invoke(null,jitter,"gps");float[] distance={0};Location.distanceBetween(jitter.latitude,jitter.longitude,fix.getLatitude(),fix.getLongitude(),distance);
        require(distance[0]<=2.02,"Android drift bounded");
    }
    private void lockedGestures() {
        double[] command={0,0};Context c=getTargetContext();
        JoystickView view=new JoystickView(c,(amount,bearing)->{ command[0]=amount;command[1]=bearing; });
        FrameLayout parent=new FrameLayout(c);parent.addView(view,new FrameLayout.LayoutParams(200,200));view.layout(0,0,200,200);
        view.setDirectionLocked(true);require(command[0]==0,"locking alone never starts motion");
        touch(view,MotionEvent.ACTION_DOWN,100,0);double magnitude=command[0],bearing=command[1];
        touch(view,MotionEvent.ACTION_UP,100,0);require(command[0]==magnitude && command[1]==bearing && command[0]>0,"locked release retains vector");
        touch(view,MotionEvent.ACTION_DOWN,200,100);touch(view,MotionEvent.ACTION_UP,200,100);
        require(command[0]>0 && command[1]==90,"locked steering changes direction");
        view.release();touch(view,MotionEvent.ACTION_MOVE,200,100);require(command[0]==0,"forced pause cannot resume from stale move");
        touch(view,MotionEvent.ACTION_DOWN,100,0);touch(view,MotionEvent.ACTION_CANCEL,100,0);require(command[0]==0,"cancel stops even while locked");
        touch(view,MotionEvent.ACTION_UP,100,0);require(command[0]==0,"cancelled release cannot restart locked motion");
        touch(view,MotionEvent.ACTION_DOWN,100,0);touch(view,MotionEvent.ACTION_POINTER_DOWN,200,100);
        require(command[0]==0,"multitouch stops even while locked");
        touch(view,MotionEvent.ACTION_DOWN,100,0);touch(view,MotionEvent.ACTION_UP,100,0);
        view.setDirectionLocked(false);require(command[0]==0 && !view.isDirectionLocked(),"unlock stops immediately");
        view.setDirectionLocked(true);touch(view,MotionEvent.ACTION_DOWN,100,0);touch(view,MotionEvent.ACTION_UP,100,0);
        view.setEnabled(false);require(command[0]==0,"disabling stops a held locked vector");view.setEnabled(true);require(command[0]==0,"reenabling never resumes movement");
        touch(view,MotionEvent.ACTION_DOWN,100,100);touch(view,MotionEvent.ACTION_UP,100,100);require(command[0]==0,"locked dead zone remains stopped");
    }
    private void panel() throws Exception {
        double[] command={0,0,0};boolean[] closed={false},sensors={false},setup={false};int[] selectedMode={-1};Context c=getTargetContext();
        JoystickPanel panel=new JoystickPanel(c,new JoystickPanel.Listener() {
            @Override public void motion(double amount,double bearing,double kmh) { command[0]=amount;command[1]=bearing;command[2]=kmh; }
            @Override public void close() { closed[0]=true; }
            @Override public void mode(int mode) { selectedMode[0]=mode; }
            @Override public void sensors(boolean enabled) { sensors[0]=enabled; }
            @Override public void sensorSetup() { setup[0]=true; }
        });
        int width=Math.round(JoystickPanel.WIDTH_DP*c.getResources().getDisplayMetrics().density);
        panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
        panel.layout(0,0,width,panel.getMeasuredHeight());panel.joystick.setEnabled(true);
        panel.sensors(false,true);int compactHeight=panel.getMeasuredHeight();
        require(compactHeight<=dp(c,230),"compact overlay height");
        require(panel.speed.getVisibility()==android.view.View.VISIBLE && panel.speed.getWidth()==0,"secondary controls collapsed by default");
        panel.setMode(1);panel.speed.setProgress(50);panel.setDirectionLocked(true);
        touch(panel.joystick,MotionEvent.ACTION_DOWN,panel.joystick.getWidth()/2f,0);
        touch(panel.joystick,MotionEvent.ACTION_UP,panel.joystick.getWidth()/2f,0);
        double oldSpeed=command[2];panel.settingsButton.performClick();require(command[0]>0,"expanding settings preserves locked movement");
        panel.speed.setProgress(100);require(command[0]>0 && command[2]>oldSpeed,"locked speed adjustment republishes held vector");
        panel.pauseButton.performClick();require(command[0]==0,"pause button stops locked movement");
        panel.lockButton.performClick();require(!panel.joystick.isDirectionLocked(),"lock button can unlock");
        panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
        panel.layout(0,0,width,panel.getMeasuredHeight());require(panel.getMeasuredHeight()>compactHeight,"settings expand overlay only on request");
        for(int mode=0;mode<3;mode++) {
            panel.setMode(mode);panel.speed.setProgress(100);
            require(selectedMode[0]==mode,"mode command reaches publisher");
            touch(panel.joystick,MotionEvent.ACTION_DOWN,panel.joystick.getWidth()/2f,0);
            require(command[0]>0.9 && command[2]==new double[]{7,18,45}[mode],"mode speed maximum");
            panel.setMode((mode+1)%3);require(command[0]==0,"mode change pauses");
        }
        panel.setMode(0);panel.speed.setProgress(0);touch(panel.joystick,MotionEvent.ACTION_DOWN,panel.joystick.getWidth()/2f,0);
        require(command[2]==1,"minimum walking speed");panel.release();require(command[0]==0,"panel pause");
        panel.sensors(false,false);panel.sensorButton.performClick();require(setup[0],"unconfigured sensors open setup");
        panel.sensors(false,true);touch(panel.joystick,MotionEvent.ACTION_DOWN,panel.joystick.getWidth()/2f,0);
        panel.sensorButton.performClick();require(sensors[0] && command[0]==0,"sensor toggle stops movement and enables");
        panel.sensorButton.performClick();require(!sensors[0],"sensor toggle disables");
        panel.setMode(2);require(panel.sensorButton.getText().toString().contains("骑车不模拟"),"cycling sensor status");
        panel.closeButton.performClick();require(closed[0] && command[0]==0,"close button also stops movement");
        panel.setMode(1);panel.sensors(true,true);panel.speed.setProgress(50);panel.showMotion(false,0);
        savePanel(c,panel,"joystick-expanded.png");
        require(panel.joystick.getHeight()>0 && panel.speed.getWidth()>0,"panel controls laid out");
        panel.settingsButton.performClick();
        panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
        panel.layout(0,0,width,panel.getMeasuredHeight());require(panel.getHeight()==compactHeight,"collapsing restores compact height");
        panel.setDirectionLocked(true);touch(panel.joystick,MotionEvent.ACTION_DOWN,panel.joystick.getWidth()/2f+20,0);
        touch(panel.joystick,MotionEvent.ACTION_UP,panel.joystick.getWidth()/2f+20,0);panel.showMotion(true,11.5);
        savePanel(c,panel,"joystick-panel.png");
        panel.release();require(command[0]==0,"lockscreen and window-drag stop path clears held vector");
        JoystickPanel fresh=new JoystickPanel(c,new JoystickPanel.Listener() {
            @Override public void motion(double amount,double bearing,double kmh) {}
            @Override public void close() {}
        });require(!fresh.joystick.isDirectionLocked(),"direction lock never persists across window reopen");
    }
    private int dp(Context c,int value) { return io.github.fakegeo.ui.UiKit.dp(c,value); }
    private void savePanel(Context c,JoystickPanel panel,String name) throws Exception {
        int width=panel.getWidth();
        panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
        panel.layout(0,0,width,panel.getMeasuredHeight());
        require(panel.status.getWidth()>0 && panel.status.getHeight()>0 && panel.status.getLayout()!=null,"compact speed status has a text layout");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(panel.getWidth(),panel.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(c.getCacheDir(),name))) {
            panel.draw(new android.graphics.Canvas(bitmap));bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
        } finally { bitmap.recycle(); }
    }
    private void serviceExit() throws Exception {
        Context c=getTargetContext();
        require(!io.github.fakegeo.config.ConfigStore.published(c).enabled,"service test uses disabled emulator profile");
        startActivitySync(new android.content.Intent(c,io.github.fakegeo.ui.MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        runOnMainSync(()->c.startForegroundService(new android.content.Intent(c,JoystickService.class)));
        SystemClock.sleep(1500);waitForIdleSync();
        require(!JoystickService.isRunning(),"service exits when virtual positioning is disabled");
        require(!io.github.fakegeo.config.ConfigStore.published(c).enabled,"service test leaves configuration disabled");
    }
    private void touch(JoystickView view,int action,float x,float y) {
        long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
        try { view.onTouchEvent(event); } finally { event.recycle(); }
    }
    private void require(boolean condition,String name) { if(!condition) throw new AssertionError(name);checks++; }
}
