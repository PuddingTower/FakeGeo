package io.github.fakegeo.debug;

import android.content.Context;
import io.github.fakegeo.root.RootBridge;

/** Read-only metadata for the affected package; never reads preference values or database rows. */
final class LocationCacheDiagnostics {
    static String schema(Context context) throws Exception {
        String body = "export CLASSPATH='" + context.getApplicationInfo().sourceDir.replace("'", "'\"'\"'")
                + "'\nexec /system/bin/app_process /system/bin io.github.fakegeo.debug.LocationCacheSchema\n";
        return RootBridge.execute("set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "nsenter -t 1 -m -- /system/bin/sh -c '" + body.replace("'", "'\"'\"'") + "'\n");
    }
    static String run() throws Exception {
        String body = "set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "fg_app='/data/user/0/com.wisedu.cpdaily'\n"
                + "[ -d \"$fg_app\" ] || { echo TARGET_DATA_DIRECTORY_UNAVAILABLE; exit 1; }\n"
                + "echo TARGET_DATA_DIRECTORY_VISIBLE\n"
                + "echo DATABASE_FILE_METADATA\n"
                + "for fg_file in \"$fg_app\"/databases/*; do\n"
                + "  [ -f \"$fg_file\" ] && [ ! -L \"$fg_file\" ] || continue\n"
                + "  case \"${fg_file##*/}\" in hmdb|hmdb-*|alsn20170807.db|alsn20170807.db-*)\n"
                + "    stat -c '%s %Y %n' \"$fg_file\"\n    sha256sum \"$fg_file\";; esac\ndone\n"
                + "echo LOCATION_PREFERENCE_METADATA\n"
                + "for fg_file in \"$fg_app\"/shared_prefs/*; do\n"
                + "  [ -f \"$fg_file\" ] && [ ! -L \"$fg_file\" ] || continue\n"
                + "  case \"${fg_file##*/}\" in *amap*|*AMap*|*location*|*Location*|*lastloc*|*lastfix*|pref.xml|als.xml|open_common.xml)\n"
                + "    stat -c '%s %Y %n' \"$fg_file\";; esac\ndone\n";
        // Android app-data mount isolation can hide other packages even from an inherited UID-0 shell.
        // Enter only init's mount namespace for this read-only child, never change mounts or app data.
        return RootBridge.execute("set -eu\n[ \"$(id -u)\" = 0 ] || exit 1\n"
                + "command -v nsenter >/dev/null\nnsenter -t 1 -m -- /system/bin/sh -c '"
                + body.replace("'", "'\"'\"'") + "'\n");
    }
}
