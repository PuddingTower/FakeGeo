package io.github.fakegeo.hook;

import android.Manifest;
import android.app.AppOpsManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.motion.GaitModel;
import io.github.fakegeo.motion.SensorPolicy;
import io.github.fakegeo.motion.StepCounterModel;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** Framework SensorEventQueue adapter in explicitly scoped application processes, never system_server. */
final class SensorHooks {
    private static final String STATE = "fakegeo.sensor.queue";
    private final RuntimeConfig config;
    private final GaitModel.State gait = new GaitModel.State();
    private Method dispatch;
    private final StepCounterModel counter = new StepCounterModel();

    SensorHooks(RuntimeConfig config) { this.config = config; }

    void install() {
        Class<?> queue = XposedHelpers.findClass("android.hardware.SystemSensorManager$SensorEventQueue", null);
        Class<?> base = XposedHelpers.findClass("android.hardware.SystemSensorManager$BaseEventQueue", null);
        dispatch = XposedHelpers.findMethodExact(queue, "dispatchSensorEvent", int.class, float[].class, int.class, long.class);
        // Resolve the required AOSP contract before installing any hook. Unknown ROMs retain native events.
        XposedHelpers.findField(queue, "mSensorsEvents");
        XposedBridge.hookAllConstructors(queue, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.hasThrowable() || state(call.thisObject) != null) return;
                for (Object arg : call.args) if (arg instanceof Looper) {
                    XposedHelpers.setAdditionalInstanceField(call.thisObject, STATE,
                            new QueueState(call.thisObject, new Handler((Looper)arg)));
                    break;
                }
            }
        });
        Hooks.all(base, "addSensor", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                QueueState state = state(call.thisObject);
                if (state == null || call.hasThrowable() || !Boolean.TRUE.equals(call.getResult())
                        || call.args.length < 2 || !(call.args[0] instanceof Sensor)) return;
                Sensor sensor = (Sensor)call.args[0];
                if (!SensorPolicy.supports(sensor.getType())) return;
                try {
                    int handle = (Integer)XposedHelpers.callMethod(sensor, "getHandle");
                    SparseArray<?> events = (SparseArray<?>)XposedHelpers.getObjectField(call.thisObject, "mSensorsEvents");
                    Object event;
                    synchronized (events) { event = events.get(handle); }
                    if (!(event instanceof SensorEvent)) return;
                    if (sensor.getType()==Sensor.TYPE_STEP_COUNTER && ((SensorEvent)event).timestamp>0)
                        counter.nativeValue(((SensorEvent)event).values[0],true);
                    long steps = gait.sample(config.get(), SystemClock.elapsedRealtime(), System.currentTimeMillis()).steps;
                    state.registrations.put(handle, new Registration(sensor, ((SensorEvent)event).values.length,
                            SensorPolicy.periodMillis((Integer)call.args[1], sensor.getMinDelay()), steps));
                    state.wake();
                } catch (Throwable error) { log("registration", error); }
            }
        });
        Hooks.all(base, "removeSensor", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                QueueState state = state(call.thisObject);
                if (state == null || call.hasThrowable() || !Boolean.TRUE.equals(call.getResult())) return;
                if (call.args.length > 0 && call.args[0] instanceof Sensor) {
                    try { state.registrations.remove((Integer)XposedHelpers.callMethod(call.args[0], "getHandle")); }
                    catch (Throwable error) { log("unregistration", error); }
                }
                if (state.registrations.isEmpty()) state.sleep();
            }
        });
        for (String method : new String[]{"removeAllSensors", "dispose"}) Hooks.all(base, method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                QueueState state = state(call.thisObject);
                if (state != null) { state.registrations.clear(); state.sleep(); }
            }
        });
        XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                QueueState state = state(call.thisObject);
                if (state == null) return;
                Registration r = state.registrations.get((Integer)call.args[0]);
                if (r == null) return;
                boolean simulated=active(config.get(), r.sensor.getType()) && state.scheduled;
                if (r.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                    float[] values=(float[])call.args[1];
                    float logical=counter.nativeValue(values[0],simulated);
                    if(!simulated && logical!=values[0]) {
                        values=values.clone();values[0]=logical;call.args[1]=values;
                    }
                }
                if (simulated) {
                    call.setResult(null);
                } else {
                    long stamp = (Long)call.args[3];
                    if (stamp <= r.lastTimestamp) call.setResult(null);
                    else r.lastTimestamp = stamp;
                }
            }
        });
        XposedBridge.log("FakeGeo: sensor framework adapter installed in " + config.context.getPackageName());
    }

    private boolean active(Profile p, int type) {
        if(!GaitModel.enabled(p)) return false;
        if(!SensorPolicy.step(type)) return true;
        if(!config.permission(Manifest.permission.ACTIVITY_RECOGNITION)) return false;
        AppOpsManager ops=config.context.getSystemService(AppOpsManager.class);
        String operation=AppOpsManager.permissionToOp(Manifest.permission.ACTIVITY_RECOGNITION);
        return ops!=null && operation!=null && ops.unsafeCheckOpNoThrow(operation,
                android.os.Process.myUid(),config.context.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    private QueueState state(Object queue) { return (QueueState)XposedHelpers.getAdditionalInstanceField(queue, STATE); }
    private void log(String operation, Throwable error) {
        XposedBridge.log("FakeGeo: sensor " + operation + " unavailable: " + error.getClass().getSimpleName());
    }

    private static final class Registration {
        final Sensor sensor;
        final int length;
        final long period;
        volatile long nextDue, lastTimestamp;
        long lastSteps;
        boolean counterSent;
        Registration(Sensor sensor, int length, long period, long steps) {
            this.sensor=sensor; this.length=length; this.period=period; lastSteps=steps;
        }
    }

    private final class QueueState implements Runnable {
        final WeakReference<Object> queue;
        final Handler handler;
        final ConcurrentHashMap<Integer, Registration> registrations = new ConcurrentHashMap<>();
        volatile boolean scheduled;
        QueueState(Object queue, Handler handler) { this.queue = new WeakReference<>(queue); this.handler = handler; }
        synchronized void wake() { if (!scheduled && !registrations.isEmpty()) scheduled=handler.post(this); }
        synchronized void sleep() { scheduled=false; handler.removeCallbacks(this); }

        @Override public void run() {
            Object target = queue.get();
            if (!scheduled || target == null || registrations.isEmpty()) { sleep(); return; }
            Profile p=config.get(); long now=SystemClock.elapsedRealtime();
            long timestamp=SystemClock.elapsedRealtimeNanos();
            GaitModel.Frame frame=gait.sample(p, now, System.currentTimeMillis());
            for (Integer handle : new ArrayList<>(registrations.keySet())) {
                Registration r=registrations.get(handle);
                if (r == null) continue;
                if (!active(p, r.sensor.getType())) { r.lastSteps=frame.steps; r.counterSent=false; continue; }
                boolean steps=SensorPolicy.step(r.sensor.getType());
                if (steps ? frame.steps==r.lastSteps && (r.sensor.getType()!=Sensor.TYPE_STEP_COUNTER || r.counterSent)
                        : now<r.nextDue) continue;
                if (timestamp<=r.lastTimestamp) continue;
                float[] values = new float[r.length];
                if (r.sensor.getType()==Sensor.TYPE_STEP_DETECTOR) values[0]=1;
                else if (r.sensor.getType()==Sensor.TYPE_STEP_COUNTER) values[0]=counter.virtualValue(frame.steps);
                else {
                    float[] source;
                    switch (r.sensor.getType()) {
                        case Sensor.TYPE_GRAVITY: source=frame.gravity; break;
                        case Sensor.TYPE_LINEAR_ACCELERATION: source=frame.linear; break;
                        case Sensor.TYPE_GYROSCOPE:
                        case Sensor.TYPE_GYROSCOPE_UNCALIBRATED: source=frame.gyroscope; break;
                        default: source=frame.acceleration;
                    }
                    float limit=r.sensor.getMaximumRange();
                    for (int i=0;i<Math.min(3,values.length);i++) values[i]=Math.max(-limit,Math.min(limit,source[i]));
                }
                r.lastSteps=frame.steps; r.counterSent=true; r.nextDue=now+r.period; r.lastTimestamp=timestamp;
                try {
                    // Calling the original queue body preserves Android's SensorEvent identity and accuracy callbacks.
                    if (registrations.get(handle)==r)
                        XposedBridge.invokeOriginalMethod(dispatch, target,
                                new Object[]{handle,values,SensorManager.SENSOR_STATUS_ACCURACY_HIGH,timestamp});
                } catch (Throwable error) {
                    registrations.remove(handle, r); log("delivery", error);
                }
            }
            synchronized (this) {
                if (scheduled && !registrations.isEmpty()) scheduled=handler.postDelayed(this,GaitModel.enabled(p)?20:200);
                else sleep();
            }
        }
    }
}
