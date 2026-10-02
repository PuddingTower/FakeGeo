package io.github.fakegeo.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.Bundle;

/** Exported read-only IPC. Authorizes the caller by Binder UID, never a supplied package name. */
public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (!ConfigStore.URI.equals(uri)) throw new IllegalArgumentException("Unknown URI");
        Profile profile = ConfigStore.load(getContext());
        Profile published = ConfigStore.published(getContext());
        boolean authorized = Binder.getCallingUid() == Process.myUid();
        boolean service = false;
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String name : packages) {
            service |= ServicePackages.NAMES.contains(name);
            authorized |= profile.targets.contains(name) || ServicePackages.NAMES.contains(name);
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"json"}, 1);
        // Removal from the allowlist immediately returns disabled state without exposing the profile.
        cursor.addRow(new Object[]{authorized ? (service ? published : profile).json().toString() : "{}"});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.fakegeo.profile"; }
    @Override public Bundle call(String method, String name, Bundle extras) {
        // ContentProvider.call has no automatic read/write permission check.
        if (!"report_service".equals(method) || name == null || extras == null)
            throw new SecurityException("Unknown diagnostic caller");
        boolean listed = ServicePackages.NAMES.contains(name);
        if (!listed)
            throw new SecurityException("Unknown diagnostic caller");
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        boolean authorized = false;
        if (packages != null) for (String actual : packages) authorized |= actual.equals(name);
        if (!authorized) throw new SecurityException("Caller UID does not own service package");
        String json = extras.getString("json", "");
        if (json.length() > 65536) throw new IllegalArgumentException("Diagnostic payload too large");
        try {
            org.json.JSONObject status = new org.json.JSONObject(json);
            status.put("reportedAt", System.currentTimeMillis());
            status.put("uid", Binder.getCallingUid());
            ConfigStore.serviceStatus(getContext(), name, status.toString());
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("Invalid diagnostic payload", invalid); }
        return Bundle.EMPTY;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read-only"); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new SecurityException("Read-only"); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new SecurityException("Read-only"); }
}
