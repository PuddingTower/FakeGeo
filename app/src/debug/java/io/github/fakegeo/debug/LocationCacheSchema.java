package io.github.fakegeo.debug;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Root app_process entry: two fixed candidate location databases; schema only, no payload rows. */
public final class LocationCacheSchema {
    public static void main(String[] args) throws Exception {
        JSONArray result = new JSONArray();
        for (String name : new String[]{"hmdb", "alsn20170807.db"}) {
            JSONObject record = new JSONObject().put("file", name);
            File file = new File("/data/user/0/com.wisedu.cpdaily/databases", name);
            if (!file.isFile()) { result.put(record.put("missing", true)); continue; }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                 Cursor tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
                JSONArray schema = new JSONArray();
                while (tables.moveToNext()) {
                    String table = tables.getString(0); JSONArray columns = new JSONArray();
                    try (Cursor info = db.rawQuery("PRAGMA table_info(" + DatabaseUtils.sqlEscapeString(table) + ")", null)) {
                        while (info.moveToNext()) columns.put(new JSONObject().put("name", info.getString(1)).put("type", info.getString(2)));
                    }
                    schema.put(new JSONObject().put("table", table).put("columns", columns));
                }
                record.put("schema", schema);
            } catch (Exception failure) { record.put("error", failure.getClass().getSimpleName()); }
            result.put(record);
        }
        System.out.println("LOCATION_CACHE_SCHEMA=" + result);
    }
}
