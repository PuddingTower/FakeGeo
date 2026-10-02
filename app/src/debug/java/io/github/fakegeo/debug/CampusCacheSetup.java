package io.github.fakegeo.debug;

import android.content.Context;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootProfilePublisher;
import org.json.JSONObject;

/** Fixed, temporary opt-in using the same production path as both UI screens. */
final class CampusCacheSetup {
    static String run(Context context, boolean restore) throws Exception {
        synchronized (ConfigStore.class) {
            Profile current = ConfigStore.published(context);
            if (current.enabled || current.revision != ConfigStore.load(context).revision)
                throw new IllegalStateException("Require a synchronized disabled profile");
            String result = "";
            if (restore) result = io.github.fakegeo.root.LocationCacheManager.restoreLatest(context);
            else result = io.github.fakegeo.root.LocationCacheManager.clear(context, "com.wisedu.cpdaily");
            JSONObject json = current.json().put("enabled", !restore).put("locationCacheTarget", "com.wisedu.cpdaily")
                    .put("systemMode", true).put("location", true).put("wifi", true)
                    .put("revision", Math.max(System.currentTimeMillis(), current.revision + 1));
            Profile next = new Profile(json);
            RootProfilePublisher.publish(context, next);
            return result + "\n" + (restore ? "CAMPUS_CACHE_TEST_RESTORED_DISABLED" : "CAMPUS_CACHE_TEST_ENABLED")
                    + " revision=" + next.revision;
        }
    }
}
