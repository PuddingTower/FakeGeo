package io.github.fakegeo.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import io.github.fakegeo.BuildConfig;

public final class ConfigStore {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/profile");
    private ConfigStore() {}
    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("profile", Context.MODE_PRIVATE);
    }
    public static Profile load(Context context) {
        try { return Profile.parse(prefs(context).getString("json", "{}")); }
        catch (Exception e) { return Profile.defaults(); }
    }
    public static Profile published(Context context) {
        try { return Profile.parse(prefs(context).getString("published_system_json", "{}")); }
        catch (Exception e) { return Profile.defaults(); }
    }
    public static void markPublished(Context context, Profile profile) {
        if (!prefs(context).edit().putString("published_system_json", profile.json().toString()).commit())
            throw new IllegalStateException("系统文件已发布，但电话／无线配置同步失败，请重试保存。");
        context.getContentResolver().notifyChange(URI, null);
    }
    public static void restoreGrants(Context context) {
        grantServices(context);
        for (String target : load(context).targets) {
            try { context.grantUriPermission(target, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (RuntimeException absent) { /* Uninstalled targets do not block system services. */ }
        }
    }
    private static void grantServices(Context context) {
        for (String service : ServicePackages.NAMES) {
            try {
                context.getPackageManager().getApplicationInfo(service, 0);
                context.grantUriPermission(service, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
                // Wi-Fi may be hosted in system_server rather than a separate package.
            }
        }
    }
    public static void serviceStatus(Context context, String name, String json) {
        if (!prefs(context).edit().putString("service_status_" + name, json).commit())
            throw new IllegalStateException("状态写入失败");
    }
    public static org.json.JSONObject serviceStatuses(Context context) {
        org.json.JSONObject results = new org.json.JSONObject();
        for (String name : ServicePackages.NAMES) {
            try { results.put(name, new org.json.JSONObject(prefs(context).getString("service_status_" + name, "{}"))); }
            catch (org.json.JSONException invalid) { /* Ignore a corrupt diagnostic record. */ }
        }
        return results;
    }
    public static void save(Context context, Profile profile) {
        Profile previous = load(context);
        grantServices(context);
        // URI grants make the provider visible on Android 11+ without modifying target manifests.
        for (String target : profile.targets)
            context.grantUriPermission(target, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!prefs(context).edit().putString("json", profile.json().toString()).commit())
            throw new IllegalStateException("配置写入失败");
        for (String target : previous.targets) if (!profile.targets.contains(target))
            context.revokeUriPermission(target, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.getContentResolver().notifyChange(URI, null);
    }
}
