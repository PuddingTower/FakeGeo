package io.github.fakegeo.ui;

import android.content.Context;
import android.content.SharedPreferences;

final class SetupPreferences {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("setup", Context.MODE_PRIVATE); }
    static boolean accepted(Context c) { return prefs(c).getInt("notice_version", 0) >= 1; }
    static boolean complete(Context c) { return accepted(c) && prefs(c).getBoolean("complete", false); }
    static void accept(Context c) { if (!prefs(c).edit().putInt("notice_version", 1).commit()) throw new IllegalStateException("无法保存同意状态"); }
    static void complete(Context c, boolean value) { prefs(c).edit().putBoolean("complete", value).apply(); }
    static boolean rootSeen(Context c) { return prefs(c).getBoolean("root_seen", false); }
    static void rootSeen(Context c, boolean value) { prefs(c).edit().putBoolean("root_seen", value).apply(); }
    private SetupPreferences() {}
}
