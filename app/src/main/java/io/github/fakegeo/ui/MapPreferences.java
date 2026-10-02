package io.github.fakegeo.ui;

import android.content.Context;
import android.content.SharedPreferences;

final class MapPreferences {
    private MapPreferences() {}
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("map_preferences",Context.MODE_PRIVATE); }
    static String key(Context context) { return prefs(context).getString("amap_key",""); }
    static boolean consent(Context context) { return prefs(context).getBoolean("amap_consent",false); }
    static void save(Context context,String key) { prefs(context).edit().putString("amap_key",key.trim()).apply(); }
    static void consent(Context context,boolean accepted) { prefs(context).edit().putBoolean("amap_consent",accepted).apply(); }
    static String label(Context context,double lat,double lon) {
        return fingerprint(lat,lon).equals(prefs(context).getString("point","")) ? prefs(context).getString("label","") : "";
    }
    static void label(Context context,double lat,double lon,String label) {
        prefs(context).edit().putString("point",fingerprint(lat,lon)).putString("label",label).apply();
    }
    static void draft(Context context,long revision,double lat,double lon) {
        prefs(context).edit().putLong("draft_revision",revision).putLong("draft_lat",Double.doubleToLongBits(lat)).putLong("draft_lon",Double.doubleToLongBits(lon)).apply();
    }
    static double[] draft(Context context,long revision) {
        SharedPreferences p=prefs(context);
        if(p.getLong("draft_revision",-1)!=revision) return null;
        return new double[]{Double.longBitsToDouble(p.getLong("draft_lat",0)),Double.longBitsToDouble(p.getLong("draft_lon",0))};
    }
    private static String fingerprint(double lat,double lon) { return Double.toString(lat)+","+Double.toString(lon); }
}
