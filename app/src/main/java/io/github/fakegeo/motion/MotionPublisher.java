package io.github.fakegeo.motion;

import android.content.Context;
import android.os.SystemClock;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.root.RootProfilePublisher;
import io.github.fakegeo.root.RuntimeGate;
import org.json.JSONObject;

/** Publishes short-lived segments. Configuration changes outside the joystick end its ownership. */
public final class MotionPublisher {
    public static Profile ready(Context context) throws Exception {
        synchronized (ConfigStore.class) {
            Profile p=ConfigStore.published(context); requireEnabled(p);
            JSONObject report=new JSONObject(RootBridge.execute(RootCommands.status()));
            RuntimeGate.requireCurrent(report,System.currentTimeMillis());
            RuntimeGate.requireMotionContinuity(report);
            if(p.wifi) RuntimeGate.requireWifiConnectivity(report);
            return p;
        }
    }
    public static Profile step(Context context,long expected,double speed,double bearing,boolean stop) throws Exception {
        Profile p=ConfigStore.published(context);
        return step(context,expected,speed,bearing,stop,p.motionMode,p.sensors);
    }
    public static Profile step(Context context,long expected,double speed,double bearing,boolean stop,int mode,boolean sensors) throws Exception {
        synchronized (ConfigStore.class) {
            Profile p=ConfigStore.published(context); requireEnabled(p);
            if(p.revision!=expected) throw new IllegalStateException("定位配置已由其他页面改变，悬浮移动已停止，请重新打开摇杆。");
            long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
            MotionModel.Sample anchor=MotionModel.anchor(p,elapsed,wall);
            JSONObject json=p.json().put("latitude",anchor.latitude).put("longitude",anchor.longitude)
                    .put("revision",Math.max(wall,p.revision+1)).put("motionMode",mode).put("sensors",sensors);
            if(!stop && p.motionLease==0) json.put("motionSeed",Math.max(1,elapsed));
            if(stop) MotionModel.clear(json);
            else json.put("motionSpeed",speed).put("motionInitialSpeed",anchor.baseSpeed)
                    .put("motionBearing",bearing).put("motionElapsed",elapsed).put("motionWall",wall)
                    .put("motionLease",MotionModel.LEASE_MS);
            Profile next=new Profile(json); RootProfilePublisher.publish(context,next); return next;
        }
    }
    private static void requireEnabled(Profile p) {
        if(!p.enabled || !p.location || !p.systemMode)
            throw new IllegalStateException("请先在系统模式开启虚拟定位，再打开悬浮摇杆。");
    }
    private MotionPublisher() {}
}
