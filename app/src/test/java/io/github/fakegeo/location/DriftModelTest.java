package io.github.fakegeo.location;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DriftModelTest {
    private static final long START=3590000;
    private static double metres(DriftModel.Sample a,DriftModel.Sample b) {
        double phi=Math.toRadians((a.latitude+b.latitude)/2);
        return Math.hypot((a.latitude-b.latitude)*111195,(a.longitude-b.longitude)*111195*Math.cos(phi));
    }
    private static double metres(DriftModel.Sample a,double latitude,double longitude) {
        double phi=Math.toRadians((a.latitude+latitude)/2);
        return Math.hypot((a.latitude-latitude)*111195,(a.longitude-longitude)*111195*Math.cos(phi));
    }
    @Test public void smoothDriftRemainsWithinConfiguredRadiusAcrossTheHourBoundary() throws Exception {
        Profile p=new Profile(new JSONObject().put("enabled",true).put("jitterMeters",2));
        DriftModel.Sample last=null;
        for(long time=START;time<=START+30000;time+=100) {
            DriftModel.Sample s=DriftModel.sample(p,time);
            assertTrue(metres(s,p.latitude,p.longitude)<=2.01);
            if(last!=null) assertTrue(metres(last,s)<0.12);
            last=s;
        }
    }
    @Test public void disabledProfilesAndDisabledJitterKeepTheExactAnchor() throws Exception {
        for(JSONObject j:new JSONObject[]{new JSONObject(),new JSONObject().put("enabled",true).put("jitter",false),new JSONObject().put("enabled",true).put("location",false)}) {
            Profile p=new Profile(j);DriftModel.Sample s=DriftModel.sample(p,123456);
            assertEquals(p.latitude,s.latitude,0);assertEquals(p.longitude,s.longitude,0);
            assertEquals(p.accuracy,s.accuracy,0);
        }
    }
    @Test public void driftStaysValidAtThePolesAndDateLine() throws Exception {
        for(double[] point:new double[][]{{90,180},{-90,-180},{0,179.999999}}) {
            Profile p=new Profile(new JSONObject().put("enabled",true).put("jitterMeters",30)
                    .put("latitude",point[0]).put("longitude",point[1]));
            DriftModel.Sample s=DriftModel.sample(p,1234567);
            assertTrue(Double.isFinite(s.latitude) && s.latitude>=-90 && s.latitude<=90);
            assertTrue(Double.isFinite(s.longitude) && s.longitude>=-180 && s.longitude<=180);
        }
    }
    @Test public void malformedDriftParametersAreRejectedAndRoundTripPreservesThem() throws Exception {
        JSONObject data=new JSONObject().put("enabled",true);
        assertThrows(IllegalArgumentException.class,()->new Profile(data.put("jitterMeters","NaN")));
        assertThrows(IllegalArgumentException.class,()->new Profile(data.put("jitterMeters",31)));
        Profile p=Profile.parse(new Profile(new JSONObject().put("enabled",true).put("jitterMeters",10)).json().toString());
        assertEquals(10,p.jitterMeters,0);
    }
}
