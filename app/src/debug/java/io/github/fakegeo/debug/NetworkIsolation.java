package io.github.fakegeo.debug;

import android.content.Context;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.root.RuntimeGate;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

/** Fixed package, fixed duration, debug-only. Does not inject code into the target application. */
final class NetworkIsolation {
    private static final String SCRIPT = RootCommands.DIRECTORY + "/network-isolation.sh";
    private static final String LOG = RootCommands.DIRECTORY + "/network-isolation.log";
    static String start(Context context) throws Exception {
        Profile profile = ConfigStore.published(context);
        if (!profile.enabled || !profile.systemMode)
            throw new IllegalStateException("Requires enabled system-only test configuration");
        JSONObject status = new JSONObject(RootBridge.execute(RootCommands.status()));
        RuntimeGate.requireCurrent(status, System.currentTimeMillis());
        if (!status.optBoolean("enabled") || status.optLong("revision") != profile.revision)
            throw new IllegalStateException("System test configuration is not acknowledged");
        String script;
        try (java.io.InputStream stream = context.getAssets().open("network-isolation.sh")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while ((count = stream.read(buffer)) > 0) bytes.write(buffer, 0, count);
            script = bytes.toString(StandardCharsets.UTF_8.name());
        }
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        return RootBridge.execute("set -eu\n"
                + "[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "[ ! -e '" + RootCommands.DIRECTORY + "/network-isolation.lock' ] || { echo NETWORK_ISOLATION_BUSY; exit 1; }\n"
                + "[ ! -L '" + SCRIPT + "' ] && [ ! -L '" + LOG + "' ] || exit 1\n"
                + "command -v nohup >/dev/null\n"
                + "umask 077\nprintf '%s' '" + encoded + "' | base64 -d > '" + SCRIPT + "'\n"
                + "chmod 600 '" + SCRIPT + "'\n: > '" + LOG + "'\n"
                + "nohup /system/bin/sh '" + SCRIPT + "' >> '" + LOG + "' 2>&1 < /dev/null &\n"
                + "for fg_attempt in 1 2 3; do\n"
                + "  if grep -q NETWORK_ISOLATION_ACTIVE '" + LOG + "'; then cat '" + LOG + "'; exit 0; fi\n"
                + "  sleep 1\ndone\ncat '" + LOG + "'\nexit 1\n");
    }
    static String stop() throws Exception {
        return RootBridge.execute("set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "[ ! -L '" + SCRIPT + "' ] || exit 1\n"
                + "if [ -f '" + SCRIPT + "' ]; then /system/bin/sh '" + SCRIPT + "' stop; "
                + "else echo NETWORK_ISOLATION_NOT_RUNNING; fi\n");
    }
    static String status() throws Exception {
        return RootBridge.execute("set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "if [ -f '" + LOG + "' ]; then tail -30 '" + LOG + "'; else echo NETWORK_ISOLATION_NOT_RUN; fi\n"
                + "fg_rules4=$(iptables -w 1 -S) || { echo IPV4_STATUS_FAILED; exit 1; }\n"
                + "fg_rules6=$(ip6tables -w 1 -S) || { echo IPV6_STATUS_FAILED; exit 1; }\n"
                + "if printf '%s\\n' \"$fg_rules4\" | grep -q FGEO_DIAG4; then echo IPV4_DIAGNOSTIC_RULES_PRESENT; else echo IPV4_DIAGNOSTIC_RULES_ABSENT; fi\n"
                + "if printf '%s\\n' \"$fg_rules6\" | grep -q FGEO_DIAG6; then echo IPV6_DIAGNOSTIC_RULES_PRESENT; else echo IPV6_DIAGNOSTIC_RULES_ABSENT; fi\n"
                + "if [ -d '" + RootCommands.DIRECTORY + "/network-isolation.lock' ]; then echo LEASE_PRESENT; else echo LEASE_ABSENT; fi\n");
    }
}
