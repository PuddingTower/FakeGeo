package io.github.fakegeo.hook.system;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderCachePolicyTest {
    private Profile active() throws Exception {
        return new Profile(new JSONObject().put("enabled",true).put("revision",1).put("latitude",31.2).put("longitude",121.5));
    }
    @Test public void sameRevisionHeartbeatsKeepProviderHistory() throws Exception {
        Profile a=active();
        assertFalse(ProviderCachePolicy.clear(a,a));
        assertFalse(ProviderCachePolicy.clear(a,new Profile(a.json())));
    }
    @Test public void configurationChangesWhileActiveInvalidateHistory() throws Exception {
        Profile a=active();
        assertTrue(ProviderCachePolicy.clear(a,new Profile(a.json().put("revision",2).put("wifi",false))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(a.json().put("revision",2).put("jitterMeters",10))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(a.json().put("revision",2).put("latitude",32))));
        assertTrue(ProviderCachePolicy.clear(a,new Profile(a.json().put("revision",2).put("systemMode",false).put("targets","com.example.test"))));
    }
    @Test public void startingStoppingAndReenablingInvalidateHistory() throws Exception {
        Profile a=active();Profile off=new Profile(a.json().put("enabled",false).put("revision",2));
        assertTrue(ProviderCachePolicy.clear(null,a));
        assertTrue(ProviderCachePolicy.clear(a,off));
        assertTrue(ProviderCachePolicy.clear(off,new Profile(a.json().put("revision",3))));
        assertFalse(ProviderCachePolicy.clear(off,new Profile(off.json().put("revision",4))));
    }
}
