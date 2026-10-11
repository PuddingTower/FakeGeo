package io.github.fakegeo.config;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileAccessTest {
    private Profile configured() throws Exception {
        return new Profile(new JSONObject().put("enabled",true).put("sensors",true).put("sensorTargets","com.example.runner").put("revision",42));
    }
    @Test public void sensorAllowlistWorksInSystemModeWithoutGrantingLocationCompatibility() throws Exception {
        Profile p=configured();String[] caller={"com.example.runner"};
        assertEquals(42,ProfileAccess.snapshot(true,false,caller,p,p).revision);
        assertFalse(ProfileAccess.snapshot(false,false,caller,p,p).enabled);
        assertFalse(ProfileAccess.snapshot(true,false,new String[]{"com.example.other"},p,p).enabled);
        assertFalse(ProfileAccess.snapshot(true,false,new String[]{"com.android.phone"},p,p).enabled);
    }
    @Test public void unpublishedSelectionAndRevocationDoNotExposeSensorConfiguration() throws Exception {
        Profile p=configured();String[] caller={"com.example.runner"};
        assertFalse(ProfileAccess.snapshot(true,false,caller,p,Profile.defaults()).enabled);
        assertFalse(ProfileAccess.snapshot(true,false,caller,Profile.defaults(),p).enabled);
        assertFalse(ProfileAccess.snapshot(true,false,null,p,p).enabled);
        // UID grants are shared; RuntimeConfig additionally checks the actual process package name.
        assertTrue(ProfileAccess.snapshot(true,false,new String[]{"com.example.other","com.example.runner"},p,p).enabled);
    }
    @Test public void profileRoundTripDefaultsAndValidationCoverSensorAndMotionFields() throws Exception {
        assertFalse(Profile.defaults().sensors);assertTrue(Profile.defaults().sensorTargets.isEmpty());
        Profile p=new Profile(configured().json().put("motionMode",1).put("motionSeed",197));
        Profile copy=Profile.parse(p.json().toString());assertTrue(copy.sensors);assertEquals(p.sensorTargets,copy.sensorTargets);
        assertEquals(1,copy.motionMode);assertEquals(197,copy.motionSeed);
        assertThrows(UnsupportedOperationException.class,()->copy.sensorTargets.clear());
        for(double mode:new double[]{-1,.5,3}) assertThrows(IllegalArgumentException.class,()->new Profile(p.json().put("motionMode",mode)));
        assertThrows(IllegalArgumentException.class,()->new Profile(p.json().put("sensorTargets","android")));
    }
}
