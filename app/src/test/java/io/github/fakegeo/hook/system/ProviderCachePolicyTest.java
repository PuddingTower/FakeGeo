package io.github.fakegeo.hook.system;

import io.github.fakegeo.config.Profile;
import io.github.fakegeo.motion.MotionModel;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderCachePolicyTest {
    private Profile moving() throws Exception {
        return new Profile(new JSONObject().put("enabled",true).put("revision",1).put("latitude",31.2).put("longitude",121.5)
                .put("motionSpeed",3).put("motionInitialSpeed",3).put("motionBearing",90)
                .put("motionElapsed",100000).put("motionWall",1800000000000L).put("motionLease",6000));
    }
    private JSONObject renewal(Profile p,long elapsed,long wall) throws Exception {
        MotionModel.Sample next=MotionModel.anchor(p,elapsed,wall);
        return p.json().put("revision",p.revision+1).put("latitude",next.latitude).put("longitude",next.longitude)
                .put("motionElapsed",elapsed).put("motionWall",wall).put("motionInitialSpeed",next.baseSpeed);
    }
    @Test public void consecutiveMotionAndDirectionChangesKeepProviderHistory() throws Exception {
        Profile a=moving();Profile b=new Profile(renewal(a,102000,1800000002000L).put("motionBearing",270));
        assertFalse(ProviderCachePolicy.clear(a,b));
        assertFalse(ProviderCachePolicy.clear(b,new Profile(renewal(b,104000,1800000004000L))));
    }
    @Test public void startingStoppingAndChangingPointInvalidateHistory() throws Exception {
        Profile a=moving();JSONObject stop=a.json().put("revision",2);MotionModel.clear(stop);
        assertTrue(ProviderCachePolicy.clear(a,new Profile(stop)));
        assertTrue(ProviderCachePolicy.clear(new Profile(stop),new Profile(renewal(a,102000,1800000002000L).put("revision",3))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(renewal(a,102000,1800000002000L).put("latitude",32))));
    }
    @Test public void permissionSettingsAndClockChangesAreNeverMistakenForContinuations() throws Exception {
        Profile a=moving();
        for(String key:new String[]{"wifi","cell","bluetooth","gnss","jitter","enabled","location"})
            assertTrue(key,ProviderCachePolicy.clear(a,new Profile(renewal(a,102000,1800000002000L).put(key,!a.json().getBoolean(key)))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(renewal(a,102000,1800000002000L).put("systemMode",false).put("targets","com.example.test"))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(renewal(a,102000,1800000002000L).put("accuracy",100))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(renewal(a,102000,1800000002000L).put("ssid","changed"))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(renewal(a,102000,1800000030000L))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(renewal(a,1000,1800000002000L))));
    }
    @Test public void firstPublicationAndReenableClearWhileDisabledUpdatesDoNot() throws Exception {
        Profile a=moving();Profile off=new Profile(a.json().put("enabled",false).put("revision",2));
        assertTrue(ProviderCachePolicy.clear(null,a));assertTrue(ProviderCachePolicy.clear(a,off));
        assertTrue(ProviderCachePolicy.clear(off,new Profile(a.json().put("revision",3))));
        assertFalse(ProviderCachePolicy.clear(off,new Profile(off.json().put("revision",3))));
        assertFalse(ProviderCachePolicy.clear(a,a));
    }
}
