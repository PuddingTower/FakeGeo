package io.github.fakegeo.root;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocationCacheSelectionTest {
    @Test public void obsoleteCampusCleanupFlagNoLongerSelectsAnApp() throws Exception {
        Profile legacy = new Profile(new JSONObject().put("campusCacheRefresh", true));
        assertEquals("", legacy.locationCacheTarget);
        assertFalse(legacy.json().has("campusCacheRefresh"));
        Profile next = new Profile(legacy.json().put("locationCacheTarget", "org.example.app"));
        assertEquals("org.example.app", Profile.parse(next.json().toString()).locationCacheTarget);
    }
    @Test public void onlyOneValidatedNonProtectedPackageCanBeSelected() throws Exception {
        for (String target : new String[]{"../other", "com.one;com.two", "com.a\ncom.b", "io.github.fakegeo", "com.android.phone", "com.google.android.gms", "com.fake.$(id)"})
            assertThrows(IllegalArgumentException.class, () -> new Profile(new JSONObject().put("locationCacheTarget", target)));
        assertEquals("", Profile.defaults().locationCacheTarget);
    }
}
