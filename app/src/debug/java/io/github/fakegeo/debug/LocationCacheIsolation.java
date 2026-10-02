package io.github.fakegeo.debug;

import android.content.Context;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Fixed files; reversible quarantine. Requires a disabled environment before a fresh backup. */
final class LocationCacheIsolation {
    static String run(Context context, boolean restore) throws Exception {
        Profile profile = ConfigStore.published(context);
        if (!restore && profile.enabled) throw new IllegalStateException("Disable the environment before backing up location caches");
        String script;
        try (java.io.InputStream stream = context.getAssets().open("location-cache-isolation.sh")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while ((count = stream.read(buffer)) > 0) bytes.write(buffer, 0, count);
            script = bytes.toString(StandardCharsets.UTF_8.name());
        }
        String path = RootCommands.DIRECTORY + "/location-cache-isolation.sh";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String body = "set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "[ ! -L '" + path + "' ] || exit 1\numask 077\n"
                + "printf '%s' '" + encoded + "' | base64 -d > '" + path + "'\n"
                + "chmod 600 '" + path + "'\n/system/bin/sh '" + path + "' " + (restore ? "restore" : "backup") + "\n";
        return RootBridge.execute("nsenter -t 1 -m -- /system/bin/sh -c '" + body.replace("'", "'\"'\"'") + "'\n");
    }
}
