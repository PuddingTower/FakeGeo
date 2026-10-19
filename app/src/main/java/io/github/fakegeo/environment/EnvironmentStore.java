package io.github.fakegeo.environment;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Device-protected, app-local fingerprint library. The published profile carries one snapshot. */
public final class EnvironmentStore {
    private static final String FILE = "environment_library";
    private static final String ITEMS = "items";
    private static final int MAX_ENVIRONMENTS = 16;
    private static final int MAX_IMPORT_CHARS = 512 * 1024;

    private EnvironmentStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static List<EnvironmentSnapshot> all(Context context) {
        List<EnvironmentSnapshot> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                EnvironmentSnapshot snapshot = EnvironmentSnapshot.parse(array.optJSONObject(i));
                if (snapshot != null) result.add(snapshot);
            }
        } catch (Exception damaged) { /* A corrupt library is empty, not fatal. */ }
        result.sort(Comparator.comparingLong((EnvironmentSnapshot item) -> item.capturedAt).reversed());
        return result;
    }

    public static EnvironmentSnapshot find(Context context, String id) {
        if (id == null) return null;
        for (EnvironmentSnapshot snapshot : all(context)) if (id.equals(snapshot.id)) return snapshot;
        return null;
    }

    public static void save(Context context, EnvironmentSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (EnvironmentStore.class) {
            List<EnvironmentSnapshot> items = all(context);
            boolean replaced = false;
            for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(snapshot.id)) {
                items.set(i, snapshot);
                replaced = true;
                break;
            }
            if (!replaced) items.add(snapshot);
            items.sort(Comparator.comparingLong((EnvironmentSnapshot item) -> item.capturedAt).reversed());
            while (items.size() > MAX_ENVIRONMENTS) items.remove(items.size() - 1);
            write(context, items);
        }
    }

    public static void delete(Context context, String id) {
        synchronized (EnvironmentStore.class) {
            List<EnvironmentSnapshot> items = all(context);
            items.removeIf(item -> item.id.equals(id));
            write(context, items);
        }
    }

    public static String exportAll(Context context) {
        List<EnvironmentSnapshot> items = all(context);
        JSONArray array = new JSONArray();
        for (EnvironmentSnapshot item : items) array.put(item.json());
        return array.toString();
    }

    public static int importText(Context context, String text) throws Exception {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("剪贴板为空");
        if (value.length() > MAX_IMPORT_CHARS) throw new IllegalArgumentException("导入内容过大");
        List<EnvironmentSnapshot> parsed = new ArrayList<>();
        if (value.startsWith("[")) {
            JSONArray array = new JSONArray(value);
            for (int i = 0; i < array.length(); i++) {
                EnvironmentSnapshot snapshot = EnvironmentSnapshot.parse(unwrap(array.optJSONObject(i)));
                if (snapshot != null) parsed.add(snapshot);
            }
        } else {
            EnvironmentSnapshot snapshot = EnvironmentSnapshot.parse(unwrap(new JSONObject(value)));
            if (snapshot != null) parsed.add(snapshot);
        }
        for (EnvironmentSnapshot snapshot : parsed) save(context, snapshot);
        return parsed.size();
    }

    /** Also accept a full profile export; its environment object is the useful part. */
    private static JSONObject unwrap(JSONObject json) {
        if (json == null) return null;
        JSONObject nested = json.optJSONObject("environment");
        return nested != null ? nested : json;
    }

    private static void write(Context context, List<EnvironmentSnapshot> items) {
        JSONArray array = new JSONArray();
        for (EnvironmentSnapshot item : items) array.put(item.json());
        if (!prefs(context).edit().putString(ITEMS, array.toString()).commit())
            throw new IllegalStateException("环境库写入失败");
    }
}
