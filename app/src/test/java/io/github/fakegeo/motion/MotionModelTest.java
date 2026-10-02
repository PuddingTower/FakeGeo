package io.github.fakegeo.motion;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MotionModelTest {
    private static final long START=100000,WALL=1800000000000L;
    private JSONObject data() throws Exception {
        return new JSONObject().put("enabled",true).put("jitter",false).put("latitude",31.2).put("longitude",121.5)
                .put("motionElapsed",START).put("motionWall",WALL).put("motionLease",6000)
                .put("motionSpeed",3).put("motionInitialSpeed",0).put("motionBearing",0);
    }
    private MotionModel.Sample sample(Profile p,long ms) { return MotionModel.sample(p,START+ms,WALL+ms); }
    private double metres(MotionModel.Sample a,MotionModel.Sample b) {
        double phi=Math.toRadians((a.latitude+b.latitude)/2);
        return Math.hypot((a.latitude-b.latitude)*111195,(a.longitude-b.longitude)*111195*Math.cos(phi));
    }
    @Test public void acceleratesNorthAndVelocityIsNotConstant() throws Exception {
        Profile p=new Profile(data());MotionModel.Sample early=sample(p,100),later=sample(p,1600);
        assertTrue(early.speed>0 && early.speed<later.speed);assertTrue(later.latitude>p.latitude);
        assertEquals(p.longitude,later.longitude,1e-9);assertEquals(0,later.bearing,0);
        assertNotEquals(sample(p,2000).speed,sample(p,4000).speed,0.001);
        assertTrue(metres(sample(p,0),sample(p,5000))>8);
    }
    @Test public void eastAndWestMoveInTheExpectedDirection() throws Exception {
        Profile east=new Profile(data().put("motionBearing",90)),west=new Profile(data().put("motionBearing",270));
        assertTrue(sample(east,3000).longitude>east.longitude);assertTrue(sample(west,3000).longitude<west.longitude);
        assertEquals(90,sample(east,3000).bearing,0);
    }
    @Test public void expiredLeaseFreezesAtItsEndInsteadOfMovingForever() throws Exception {
        Profile p=new Profile(data());MotionModel.Sample end=sample(p,6000),late=sample(p,600000);
        assertEquals(end.latitude,late.latitude,0);assertEquals(end.longitude,late.longitude,0);assertEquals(0,late.speed,0);
        assertEquals(0,sample(p,6000).speed,0);assertTrue(sample(p,5999).speed>0);
    }
    @Test public void rebootOrClockMismatchNeverReplaysTheOldMotion() throws Exception {
        Profile p=new Profile(data());
        for(MotionModel.Sample s:new MotionModel.Sample[]{MotionModel.sample(p,1000,WALL+1000),MotionModel.sample(p,START+2000,WALL+86400000)}) {
            assertEquals(p.latitude,s.latitude,0);assertEquals(p.longitude,s.longitude,0);assertEquals(0,s.speed,0);
        }
    }
    @Test public void heartbeatsContinueFromTheUndriftedAnchorWithoutAccumulatingNoise() throws Exception {
        Profile p=new Profile(data().put("jitter",true));MotionModel.Sample end=MotionModel.anchor(p,START+2000,WALL+2000);
        JSONObject next=p.json().put("latitude",end.latitude).put("longitude",end.longitude)
                .put("motionElapsed",START+2000).put("motionWall",WALL+2000).put("motionInitialSpeed",end.baseSpeed);
        MotionModel.Sample original=sample(p,2000),continued=sample(new Profile(next),2000);
        assertEquals(original.latitude,continued.latitude,1e-12);assertEquals(original.longitude,continued.longitude,1e-12);
        assertEquals(original.speed,continued.speed,0.00001);
    }
    @Test public void smoothJitterRemainsWithinConfiguredRadiusAcrossTheHourBoundary() throws Exception {
        Profile p=new Profile(new JSONObject().put("enabled",true).put("jitterMeters",2));
        MotionModel.Sample origin=MotionModel.anchor(p,0,0),last=null;
        for(long time=3590000;time<=3620000;time+=100) {
            MotionModel.Sample s=MotionModel.sample(p,time,time);
            assertTrue(metres(origin,s)<=2.01);
            if(last!=null) assertTrue(metres(last,s)<0.12);
            last=s;
        }
    }
    @Test public void disabledProfilesAndDisabledJitterKeepTheExactAnchor() throws Exception {
        for(JSONObject j:new JSONObject[]{new JSONObject(),new JSONObject().put("enabled",true).put("jitter",false),new JSONObject().put("enabled",true).put("location",false)}) {
            Profile p=new Profile(j);MotionModel.Sample s=MotionModel.sample(p,123456,999999);
            assertEquals(p.latitude,s.latitude,0);assertEquals(p.longitude,s.longitude,0);
        }
    }
    @Test public void releaseClearsMotionAndLeavesTheCurrentPosition() throws Exception {
        Profile p=new Profile(data());MotionModel.Sample anchor=MotionModel.anchor(p,START+2100,WALL+2100);
        JSONObject j=p.json().put("latitude",anchor.latitude).put("longitude",anchor.longitude);MotionModel.clear(j);
        MotionModel.Sample stopped=sample(new Profile(j),4000);
        assertEquals(anchor.latitude,stopped.latitude,0);assertEquals(anchor.longitude,stopped.longitude,0);assertEquals(0,stopped.speed,0);
    }
    @Test public void positionsRemainValidAtThePolesAndDateLine() {
        for(double[] point:new double[][]{{90,180},{-90,-180},{0,179.999999}}) {
            double[] moved=MotionModel.offset(point[0],point[1],200,200);
            assertTrue(Double.isFinite(moved[0]) && moved[0]>=-90 && moved[0]<=90);
            assertTrue(Double.isFinite(moved[1]) && moved[1]>=-180 && moved[1]<=180);
        }
    }
    @Test public void malformedMotionAndDriftParametersAreRejectedAndRoundTripPreservesThem() throws Exception {
        for(String key:new String[]{"motionSpeed","motionInitialSpeed","jitterMeters"})
            assertThrows(IllegalArgumentException.class,()->new Profile(data().put(key,"NaN")));
        assertThrows(IllegalArgumentException.class,()->new Profile(data().put("motionLease",6001)));
        assertThrows(IllegalArgumentException.class,()->new Profile(data().put("motionSpeed",-1)));
        Profile p=Profile.parse(new Profile(data()).json().toString());assertEquals(6000,p.motionLease);assertEquals(START,p.motionElapsed);assertEquals(3,p.motionSpeed,0);
    }
}
