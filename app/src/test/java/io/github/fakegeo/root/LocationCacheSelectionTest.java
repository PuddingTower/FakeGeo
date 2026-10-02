package io.github.fakegeo.root;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocationCacheSelectionTest {
    @Test public void oldSelectionMigratesWithoutKeepingAutomaticCleanup() throws Exception {
        Profile old = new Profile(new JSONObject().put("campusCacheRefresh", true));
        assertEquals("com.wisedu.cpdaily", old.locationCacheTarget);
        assertFalse(old.json().has("locationCacheRefresh"));
        assertFalse(old.json().has("campusCacheRefresh"));
        Profile next = new Profile(old.json().put("locationCacheTarget", "org.example.app"));
        assertEquals("org.example.app", Profile.parse(next.json().toString()).locationCacheTarget);
    }
    @Test public void onlyOneValidatedNonProtectedPackageCanBeSelected() throws Exception {
        for (String target : new String[]{"../other", "com.one;com.two", "com.a\ncom.b", "io.github.fakegeo", "com.android.phone", "com.google.android.gms", "com.fake.$(id)"})
            assertThrows(IllegalArgumentException.class, () -> new Profile(new JSONObject().put("locationCacheTarget", target)));
        assertEquals("", Profile.defaults().locationCacheTarget);
    }
}
