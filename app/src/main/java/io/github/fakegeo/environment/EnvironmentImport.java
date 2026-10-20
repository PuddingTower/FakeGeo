package io.github.fakegeo.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Context-free parser for clipboard and file imports; no Android dependency so it stays testable. */
public final class EnvironmentImport {
    public static final int MAX_TEXT_CHARS = 512 * 1024;
    private EnvironmentImport() {}

    public static final class Result {
        public final List<EnvironmentSnapshot> snapshots;
        public final int rejected;
        Result(List<EnvironmentSnapshot> snapshots, int rejected) {
            this.snapshots = Collections.unmodifiableList(snapshots);
            this.rejected = rejected;
        }
    }

    public static Result parse(String text) throws Exception {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("\uFEFF")) value = value.substring(1).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("导入内容为空");
        if (value.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("导入内容过大");
        List<EnvironmentSnapshot> snapshots = new ArrayList<>();
        int rejected = 0;
        if (value.startsWith("[")) {
            JSONArray array = new JSONArray(value);
            for (int i = 0; i < array.length(); i++) {
                EnvironmentSnapshot snapshot = usable(EnvironmentSnapshot.parse(unwrap(array.optJSONObject(i))));
                if (snapshot == null) rejected++; else snapshots.add(snapshot);
            }
        } else {
            EnvironmentSnapshot snapshot = usable(EnvironmentSnapshot.parse(unwrap(new JSONObject(value))));
            if (snapshot == null) rejected++; else snapshots.add(snapshot);
        }
        if (snapshots.isEmpty())
            throw new IllegalArgumentException(rejected > 0 ? "没有识别到有效环境" : "没有识别到环境数据");
        return new Result(snapshots, rejected);
    }

    /** A profile export wraps the environment; a library item does not. */
    private static JSONObject unwrap(JSONObject json) {
        if (json == null) return null;
        JSONObject nested = json.optJSONObject("environment");
        return nested != null ? nested : json;
    }

    private static EnvironmentSnapshot usable(EnvironmentSnapshot snapshot) {
        return snapshot == null || snapshot.isEmpty() ? null : snapshot;
    }
}
