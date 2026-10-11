package io.github.fakegeo.motion;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GaitModelTest {
    private static final long START=100000,WALL=1800000000000L;
    private Profile moving(int mode,double speed) throws Exception {
        return new Profile(new JSONObject().put("enabled",true).put("sensors",true).put("jitter",false)
                .put("motionMode",mode).put("motionSeed",7).put("motionSpeed",speed).put("motionInitialSpeed",speed)
                .put("motionElapsed",START).put("motionWall",WALL).put("motionLease",6000));
    }
    private GaitModel.Frame sample(GaitModel.State state,Profile p,long t) { return state.sample(p,START+t,WALL+t); }
    @Test public void runningHasGreaterCadenceThanWalkingAndAllSensorsShareOneClock() throws Exception {
        long[] counts=new long[2];
        for(int mode=0;mode<2;mode++) {
            Profile p=moving(mode,mode==0?1.1:3);GaitModel.State state=new GaitModel.State();long steps=0;
            for(long t=0;t<5000;t+=20) {
                GaitModel.Frame f=sample(state,p,t);
                assertSame(f,sample(state,p,t));assertTrue(f.steps>=steps && f.steps<=steps+1);steps=f.steps;
                double gravity=0;for(int i=0;i<3;i++) {
                    assertEquals(f.acceleration[i],f.linear[i]+f.gravity[i],.000001);
                    gravity+=f.gravity[i]*f.gravity[i];assertTrue(Float.isFinite(f.gyroscope[i]));
                }
                assertEquals(9.80665,Math.sqrt(gravity),.00001);
            }
            counts[mode]=steps;
        }
        assertTrue(counts[0]>=6);assertTrue(counts[1]>counts[0]);
    }
    @Test public void releaseLeaseExpiryClockChangeAndCyclingStopSimulatedSteps() throws Exception {
        Profile p=moving(1,3);GaitModel.State state=new GaitModel.State();
        for(long t=0;t<3000;t+=20) sample(state,p,t);
        long before=sample(state,p,3000).steps;
        Profile stopped=new Profile(p.json().put("motionLease",0));
        for(long t=3020;t<4000;t+=20) {
            GaitModel.Frame f=sample(state,stopped,t);assertEquals(before,f.steps);assertEquals(0,f.cadence,0);
            for(int i=0;i<3;i++) { assertEquals(0,f.linear[i],0);assertEquals(0,f.gyroscope[i],0); }
        }
        assertEquals(before,sample(state,p,90000).steps);assertEquals(0,sample(state,p,90020).cadence,0);
        assertEquals(0,new GaitModel.State().sample(p,1000,WALL+1000).cadence,0);
        assertEquals(0,new GaitModel.State().sample(p,START+2000,WALL+86400000).cadence,0);
        assertFalse(GaitModel.enabled(moving(2,8)));assertFalse(GaitModel.enabled(Profile.defaults()));
        assertFalse(GaitModel.enabled(new Profile(p.json().put("sensors",false))));
    }
    @Test public void blockedCallbacksAndSeedChangeNeverReplayOrBurstOldSteps() throws Exception {
        Profile p=moving(1,3);GaitModel.State state=new GaitModel.State();sample(state,p,0);
        assertEquals(0,sample(state,p,2000).steps);
        assertEquals(0,sample(state,new Profile(p.json().put("motionSeed",99)),2020).steps);
    }
    @Test public void cadenceFollowsSpeedAndSensorRateNeverExceedsHardwareOr50Hz() {
        assertEquals(0,GaitModel.cadence(0,0),0);assertEquals(0,GaitModel.cadence(2,10),0);
        assertTrue(GaitModel.cadence(0,1.5)>GaitModel.cadence(0,.5));
        assertEquals(20,SensorPolicy.periodMillis(0,1000));assertEquals(200,SensorPolicy.periodMillis(200000,10000));
        assertEquals(51,SensorPolicy.periodMillis(10000,50100));assertFalse(SensorPolicy.supports(5));
    }
}
