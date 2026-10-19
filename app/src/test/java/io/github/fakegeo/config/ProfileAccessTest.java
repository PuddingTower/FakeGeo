package io.github.fakegeo.config;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileAccessTest {
    private Profile configured() throws Exception {
        return new Profile(new JSONObject().put("enabled",true).put("systemMode",false)
                .put("targets","com.example.target").put("revision",42));
    }
    @Test public void listedTargetsReadTheDraftWhileOtherAppsStayDisabled() throws Exception {
        Profile p=configured();String[] caller={"com.example.target"};
        assertEquals(42,ProfileAccess.snapshot(false,caller,p,p).revision);
        assertFalse(ProfileAccess.snapshot(false,new String[]{"com.example.other"},p,p).enabled);
        assertFalse(ProfileAccess.snapshot(false,null,p,p).enabled);
        assertEquals(42,ProfileAccess.snapshot(true,new String[]{"io.github.fakegeo"},p,p).revision);
    }
    @Test public void systemServicesReceiveOnlyThePublishedSnapshot() throws Exception {
        Profile draft=configured();
        Profile published=new Profile(draft.json().put("latitude",31.2).put("revision",43));
        Profile result=ProfileAccess.snapshot(false,new String[]{"com.android.phone"},draft,published);
        assertEquals(43,result.revision);
        assertEquals(31.2,result.latitude,0);
        assertFalse(ProfileAccess.snapshot(false,new String[]{"com.android.phone"},draft,Profile.defaults()).enabled);
    }
    @Test public void profileRoundTripKeepsTheTargetAllowlistImmutable() throws Exception {
        Profile p=configured();
        Profile copy=Profile.parse(p.json().toString());
        assertEquals(p.targets,copy.targets);
        assertThrows(UnsupportedOperationException.class,()->copy.targets.clear());
        assertThrows(IllegalArgumentException.class,()->new Profile(p.json().put("targets","android")));
        assertThrows(IllegalArgumentException.class,()->new Profile(new JSONObject().put("enabled",true).put("systemMode",false)));
    }
}
