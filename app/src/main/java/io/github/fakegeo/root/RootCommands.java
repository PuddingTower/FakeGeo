package io.github.fakegeo.root;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Commands use constant paths and an encoded payload, never interpolate editable shell text. */
public final class RootCommands {
    public static final String DIRECTORY = "/data/system/fakegeo";
    public static final String PROFILE = DIRECTORY + "/profile.json";
    public static final String STATUS = DIRECTORY + "/status.json";
    private RootCommands() {}
    /** Android mksh's numeric test operators can truncate epoch milliseconds to 32 bits. */
    public static String revisionOrderFunction() {
        return "fg_revision_le() {\n"
                + "fg_cmp_left=$1\nfg_cmp_right=$2\n"
                + "case \"$fg_cmp_left\" in ''|*[!0-9]*) return 2;; esac\n"
                + "case \"$fg_cmp_right\" in ''|*[!0-9]*) return 2;; esac\n"
                + "while [ \"${fg_cmp_left#0}\" != \"$fg_cmp_left\" ]; do fg_cmp_left=${fg_cmp_left#0}; done\n"
                + "while [ \"${fg_cmp_right#0}\" != \"$fg_cmp_right\" ]; do fg_cmp_right=${fg_cmp_right#0}; done\n"
                + "[ -n \"$fg_cmp_left\" ] || fg_cmp_left=0\n[ -n \"$fg_cmp_right\" ] || fg_cmp_right=0\n"
                + "if [ \"${#fg_cmp_left}\" -lt \"${#fg_cmp_right}\" ]; then return 0; fi\n"
                + "if [ \"${#fg_cmp_left}\" -gt \"${#fg_cmp_right}\" ]; then return 1; fi\n"
                + "[ \"$fg_cmp_left\" = \"$fg_cmp_right\" ] || [ \"$fg_cmp_left\" \\< \"$fg_cmp_right\" ]\n}\n";
    }
    static String prepareDirectory() {
        return "set -eu\nexport LC_ALL=C\n"
                + "[ \"$(id -u)\" = 0 ] || { echo 'Root UID 0 required'; exit 1; }\n"
                + "d='" + DIRECTORY + "'\n"
                + "[ ! -L \"$d\" ] && [ ! -L \"$d/profile.json\" ] && [ ! -L \"$d/status.json\" ] || { echo 'Unsafe symlink'; exit 1; }\n"
                + "umask 077\nmkdir -p \"$d\"\nchown 1000:1000 \"$d\"\nchmod 700 \"$d\"\n"
                + "chcon u:object_r:system_data_file:s0 \"$d\"\n";
    }
    public static String publish(String json) { return publish(json, 0); }
    public static String publish(String json, long revision) {
        if (revision < 0) throw new IllegalArgumentException("Invalid revision");
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return prepareDirectory() + revisionOrderFunction()
                + "old=0\nif [ -f \"$d/profile.json\" ]; then old=$(sed -n 's/.*\"revision\":\\([0-9][0-9]*\\).*/\\1/p' \"$d/profile.json\"); fi\n"
                + "[ -n \"$old\" ] || old=0\nfg_revision_le \"$old\" '" + revision + "' || { echo \"STALE_REVISION published=$old attempted=" + revision + "\"; exit 1; }\n"
                + "tmp=\"$d/profile.new.$$\"\n"
                + "printf '%s' '" + encoded + "' | base64 -d > \"$tmp\"\n"
                + "chown 1000:1000 \"$tmp\"\nchmod 600 \"$tmp\"\n"
                + "chcon u:object_r:system_data_file:s0 \"$tmp\"\n"
                + "mv -f \"$tmp\" \"$d/profile.json\"\n"
                + "echo 'ROOT_CONFIG_PUBLISHED'\n";
    }
    public static String status() {
        return prepareDirectory()
                + "if [ -f '" + STATUS + "' ]; then cat '" + STATUS + "'; "
                + "elif [ -f '" + PROFILE + "' ]; then echo 'SYSTEM_STATUS_MISSING_WITH_PROFILE'; "
                + "else echo 'SYSTEM_STATUS_MISSING_NO_PROFILE'; fi\n";
    }
    public static String authorization() { return "id -u\n"; }
}
