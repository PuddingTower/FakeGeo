package io.github.fakegeo.debug;

import android.content.Context;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.root.RuntimeGate;
import org.json.JSONObject;

/** Fixed system upgrade/test actions. */
final class SystemSetup {
    static String run(Context context, boolean enable) throws Exception {
        synchronized (ConfigStore.class) {
            Profile current = ConfigStore.published(context);
            if (current.revision != ConfigStore.load(context).revision) throw new IllegalStateException("Unpublished draft exists");
            if (enable) {
                JSONObject status = new JSONObject(RootBridge.execute(RootCommands.status()));
                RuntimeGate.requireCurrent(status, System.currentTimeMillis());
                if (current.wifi) RuntimeGate.requireWifiConnectivity(status);
                if (!status.optBoolean("gnssCaptured")) throw new IllegalStateException("System GNSS status provider was not captured");
            }
            JSONObject json = current.json().put("enabled", enable).put("gnss", true)
                    .put("revision", Math.max(System.currentTimeMillis(), current.revision + 1));
            Profile next = new Profile(json);
            io.github.fakegeo.root.RootProfilePublisher.publish(context, next);
            return (enable ? "SYSTEM_TEST_ENABLED" : "SYSTEM_UPGRADE_PREPARED_DISABLED") + ": revision=" + next.revision
                    + " gnss=" + next.gnss + " wifi=" + next.wifi + " cell=" + next.cell;
        }
    }
}
