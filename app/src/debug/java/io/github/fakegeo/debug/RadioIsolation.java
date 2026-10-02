package io.github.fakegeo.debug;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.root.RuntimeGate;
import org.json.JSONObject;

/** Fixed, DUMP-protected diagnostic actions; no external profiles or commands accepted. */
final class RadioIsolation {
    private RadioIsolation() {}
    static String run(Context context, boolean restore) throws Exception {
        synchronized (ConfigStore.class) {
            SharedPreferences backup = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("radio_isolation", Context.MODE_PRIVATE);
            Profile current = ConfigStore.published(context);
            if (current.revision != ConfigStore.load(context).revision)
                throw new IllegalStateException("Unpublished draft exists; diagnostic refuses to overwrite it");
            RuntimeGate.requireCurrent(new JSONObject(RootBridge.execute(RootCommands.status())), System.currentTimeMillis());
            JSONObject next = current.json();
            if (restore) {
                String saved = backup.getString("original", null);
                if (saved == null) return "RADIO_TEST_NO_PENDING_RESTORE";
                Profile original = Profile.parse(saved);
                next.put("wifi", original.wifi); next.put("cell", original.cell);
            } else {
                if (backup.contains("original")) throw new IllegalStateException("Restore the pending radio test first");
                if (!current.enabled || !current.systemMode || !current.location)
                    throw new IllegalStateException("Radio isolation requires an already active system location profile");
                if (!backup.edit().putString("original", current.json().toString()).commit())
                    throw new IllegalStateException("Cannot save restoration snapshot");
                next.put("wifi", false); next.put("cell", false);
            }
            next.put("revision", Math.max(System.currentTimeMillis(), current.revision + 1));
            Profile profile = new Profile(next);
            RootBridge.execute(RootCommands.publish(profile.json().toString(), profile.revision));
            ConfigStore.save(context, profile); ConfigStore.markPublished(context, profile);
            if (restore && !backup.edit().remove("original").commit())
                throw new IllegalStateException("Configuration restored but pending snapshot removal failed");
            return (restore ? "RADIO_TEST_RESTORED" : "RADIO_TEST_STARTED") + ": revision=" + profile.revision
                    + " enabled=" + profile.enabled + " location=" + profile.location + " wifi=" + profile.wifi + " cell=" + profile.cell;
        }
    }
}
