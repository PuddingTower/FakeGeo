package io.github.fakegeo.config;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class ValidationTest {
    @Test public void acceptsCoordinateBoundaries() {
        assertEquals(-90, Validation.number("-90", -90, 90, "lat"), 0);
        assertEquals(180, Validation.number("180", -180, 180, "lon"), 0);
    }
    @Test public void rejectsNonFiniteAndOutOfRangeCoordinates() {
        for (String value : new String[]{"NaN", "Infinity", "-Infinity", "90.01", "-90.01", "", "x"})
            assertThrows(IllegalArgumentException.class, () -> Validation.number(value, -90, 90, "lat"));
    }
    @Test public void normalizesUnicastMacAndRejectsMalformedOrMulticast() {
        assertEquals("02:AB:CD:00:00:01", Validation.mac(" 02:ab:cd:00:00:01 "));
        for (String value : new String[]{"01:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00", "02:ZZ:00:00:00:00"})
            assertThrows(IllegalArgumentException.class, () -> Validation.mac(value));
    }
    @Test public void parsesMultipleSeparatorsAndDeduplicatesTargets() {
        Set<String> values = Validation.packages("com.example.one\ncom.example.two，com.example.one;io.github.fakegeo.probe");
        assertEquals(3, values.size()); assertTrue(values.contains("io.github.fakegeo.probe"));
    }
    @Test public void excludesSensitiveProcessesAndInvalidPackageNames() {
        for (String value : new String[]{"android", "com.android.systemui", "io.github.fakegeo", "com.google.android.gms", "bad/package", "123.app"})
            assertThrows(IllegalArgumentException.class, () -> Validation.packages(value));
        assertTrue(Validation.packages(" ").isEmpty());
    }
}
