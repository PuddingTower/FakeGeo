package io.github.fakegeo.environment;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Device-protected, app-local fingerprint library. The published profile carries one snapshot. */
public final class EnvironmentStore {
    private static final String FILE = "environment_library";
    private static final String ITEMS = "items";
    public static final int MAX_ENVIRONMENTS = 16;

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

    public static final class ImportResult {
        public final int added, replaced, rejected, evicted;
        ImportResult(int added, int replaced, int rejected, int evicted) {
            this.added = added; this.replaced = replaced; this.rejected = rejected; this.evicted = evicted;
        }
        public boolean changedAnything() { return added > 0 || replaced > 0; }
    }

    public static ImportResult importText(Context context, String text) throws Exception {
        EnvironmentImport.Result parsed = EnvironmentImport.parse(text);
        synchronized (EnvironmentStore.class) {
            List<EnvironmentSnapshot> items = all(context);
            int added = 0, replaced = 0;
            for (EnvironmentSnapshot snapshot : parsed.snapshots) {
                int existing = indexOf(items, snapshot.id);
                if (existing >= 0) { items.set(existing, snapshot); replaced++; }
                else { items.add(snapshot); added++; }
            }
            items.sort(Comparator.comparingLong((EnvironmentSnapshot item) -> item.capturedAt).reversed());
            int evicted = Math.max(0, items.size() - MAX_ENVIRONMENTS);
            while (items.size() > MAX_ENVIRONMENTS) items.remove(items.size() - 1);
            write(context, items);
            return new ImportResult(added, replaced, parsed.rejected, evicted);
        }
    }

    private static int indexOf(List<EnvironmentSnapshot> items, String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
        return -1;
    }

    private static void write(Context context, List<EnvironmentSnapshot> items) {
        JSONArray array = new JSONArray();
        for (EnvironmentSnapshot item : items) array.put(item.json());
        if (!prefs(context).edit().putString(ITEMS, array.toString()).commit())
            throw new IllegalStateException("环境库写入失败");
    }
}
