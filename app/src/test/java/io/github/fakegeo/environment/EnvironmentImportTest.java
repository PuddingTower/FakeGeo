package io.github.fakegeo.environment;

import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnvironmentImportTest {
    @Test public void acceptsASingleEnvironmentOrAFullProfileWrapper() throws Exception {
        EnvironmentSnapshot source = snapshot("lab");
        assertEquals(1, EnvironmentImport.parse(source.json().toString()).snapshots.size());
        JSONObject profile = new JSONObject().put("enabled", true).put("environment", source.json());
        EnvironmentImport.Result wrapped = EnvironmentImport.parse(profile.toString());
        assertEquals(1, wrapped.snapshots.size());
        assertEquals("lab", wrapped.snapshots.get(0).id);
    }

    @Test public void arrayImportSkipsEmptyAndMalformedEntriesButKeepsTheRest() throws Exception {
        JSONArray array = new JSONArray()
                .put(snapshot("ok").json())
                .put(new JSONObject())
                .put(new JSONObject().put("id", "bad").put("wifi",
                        new JSONArray().put(new JSONObject().put("bssid", "not-a-mac"))));
        EnvironmentImport.Result result = EnvironmentImport.parse(array.toString());
        assertEquals(1, result.snapshots.size());
        assertEquals("ok", result.snapshots.get(0).id);
        assertEquals(2, result.rejected);
    }

    @Test public void emptyObjectAndEmptyArrayAreNotImportable() {
        IllegalArgumentException emptyObject = assertThrows(IllegalArgumentException.class,
                () -> EnvironmentImport.parse("{}"));
        assertTrue(emptyObject.getMessage().contains("有效环境"));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentImport.parse("[]"));
    }

    @Test public void parserToleratesABomAndRejectsOversizedText() throws Exception {
        String text = snapshot("bom").json().toString();
        assertEquals(1, EnvironmentImport.parse("\uFEFF" + text).snapshots.size());
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentImport.parse("x".repeat(EnvironmentImport.MAX_TEXT_CHARS + 1)));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentImport.parse("   "));
    }

    @Test public void locationOnlyEnvironmentIsUsefulButAnEmptyOneIsNot() throws Exception {
        EnvironmentSnapshot locationOnly = new EnvironmentSnapshot("loc", "", 1000, true,
                31.2, 121.4, 8, 5, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertEquals(1, EnvironmentImport.parse(locationOnly.json().toString()).snapshots.size());
        EnvironmentSnapshot empty = new EnvironmentSnapshot("empty", "", 1000, false,
                0, 0, 0, 10, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> EnvironmentImport.parse(empty.json().toString()));
    }

    @Test public void importedNamesCannotInjectLineBreaksOrControlCharacters() {
        EnvironmentSnapshot named = new EnvironmentSnapshot("id", " office\nnorth\twing\u0000 ", 1000, true,
                31.2, 121.4, 8, 5, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertEquals("office north wing", named.name);
    }

    private static EnvironmentSnapshot snapshot(String id) {
        return new EnvironmentSnapshot(id, "实验室", 1000, true, 31.2, 121.4, 12, 5,
                Collections.singletonList(new WifiFingerprint("lab", "02:11:22:33:44:55", -55, 2412, "[ESS]", true)),
                Collections.emptyList(), Collections.emptyList());
    }
}
