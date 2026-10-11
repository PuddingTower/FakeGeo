package io.github.fakegeo.motion;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NaturalPaceTest {
    @Test public void differentSessionsAndModesHaveDistinctSmoothBoundedPaces() {
        for(int mode=0;mode<3;mode++) {
            double last=NaturalPace.factor(mode,17,0),min=2,max=0;
            for(int i=1;i<6000;i++) {
                double v=NaturalPace.factor(mode,17,i*.01);min=Math.min(min,v);max=Math.max(max,v);
                assertTrue(v>=.79 && v<=1.21);assertTrue(Math.abs(v-last)<.004);last=v;
                assertEquals(v,NaturalPace.factor(mode,17,i*.01),0);
            }
            assertTrue(max-min>(mode==2?.01:.05));
            assertNotEquals(NaturalPace.factor(mode,17,5),NaturalPace.factor(mode,91,5),1e-6);
        }
        assertNotEquals(NaturalPace.factor(0,17,8),NaturalPace.factor(1,17,8),1e-6);
    }
    @Test public void reportedSpeedMatchesActualDisplacementAcrossRenewedSegments() throws Exception {
        // Regression: changing the displayed speed alone must never leave GPS moving at a constant rate.
        long start=100000,wall=1800000000000L;
        Profile p=new Profile(new JSONObject().put("enabled",true).put("jitter",false).put("motionMode",1)
                .put("motionSeed",17).put("motionSpeed",3).put("motionInitialSpeed",3)
                .put("motionElapsed",start).put("motionWall",wall).put("motionLease",6000).put("motionBearing",90));
        for(long t=100;t<5000;t+=100) {
            MotionModel.Sample a=MotionModel.anchor(p,start+t-5,wall+t-5);
            MotionModel.Sample b=MotionModel.anchor(p,start+t+5,wall+t+5);
            double h=Math.pow(Math.sin(Math.toRadians(b.latitude-a.latitude)/2),2)
                    +Math.cos(Math.toRadians(a.latitude))*Math.cos(Math.toRadians(b.latitude))*Math.pow(Math.sin(Math.toRadians(b.longitude-a.longitude)/2),2);
            double metres=12742017.6*Math.asin(Math.sqrt(h));
            assertEquals(MotionModel.sample(p,start+t,wall+t).speed,metres/.01,.015);
            if(t%1000==0) {
                MotionModel.Sample anchor=MotionModel.anchor(p,start+t,wall+t);
                Profile next=new Profile(p.json().put("latitude",anchor.latitude).put("longitude",anchor.longitude)
                        .put("motionInitialSpeed",anchor.baseSpeed).put("motionElapsed",start+t).put("motionWall",wall+t));
                assertEquals(anchor.speed,MotionModel.anchor(next,start+t,wall+t).speed,1e-5);p=next;
            }
        }
    }
}
