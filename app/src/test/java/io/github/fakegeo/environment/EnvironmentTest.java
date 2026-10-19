package io.github.fakegeo.environment;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class EnvironmentTest {
    private static final String BEACON = "0201061AFF4C000215E2C56DB5DFFB48D2B060D0F5A71096E000010002C5";

    @Test public void snapshotRoundTripKeepsAnchorsAndLocation() throws Exception {
        EnvironmentSnapshot source = new EnvironmentSnapshot("lab", "实验室", 1000, true, 31.2, 121.4, 12, 5,
                Arrays.asList(wifi("lab", "02:11:22:33:44:55", -60, false), wifi("lab", "02:11:22:33:44:66", -42, true)),
                Collections.singletonList(new BleFingerprint("A4:C1:38:11:22:33", "beacon", -70, BEACON)),
                Collections.singletonList(new CellFingerprint("lte", "460", "00", 1234, 56789, 10, 1650, -85, true)));
        EnvironmentSnapshot parsed = EnvironmentSnapshot.parse(source.json());
        assertEquals("lab", parsed.id); assertTrue(parsed.hasLocation);
        assertEquals(2, parsed.wifi.size());
        assertEquals("02:11:22:33:44:66", parsed.wifi.get(0).bssid); // Connected AP leads.
        assertEquals(1, parsed.ble.size()); assertTrue(parsed.ble.get(0).beaconLike);
        assertEquals(1, parsed.cells.size()); assertTrue(parsed.cells.get(0).registered);
        assertEquals(31.2, parsed.latitude, 0); assertEquals(121.4, parsed.longitude, 0);
    }

    @Test public void filterDropsWeakOneOffAndKeepsConnectedAnchor() {
        List<WifiFingerprint> result = EnvironmentFilter.wifi(Arrays.asList(
                wifi("lab", "02:00:00:00:00:01", -50, false),
                wifi("lab", "02:00:00:00:00:01", -56, false),
                wifi("lab", "02:00:00:00:00:02", -90, false),
                wifi("lab", "02:00:00:00:00:03", -60, false),
                wifi("lab", "02:00:00:00:00:04", -92, true)));
        assertEquals(3, result.size());
        assertEquals("02:00:00:00:00:04", result.get(0).bssid);
        assertFalse(result.stream().anyMatch(point -> point.bssid.endsWith(":02")));
    }

    @Test public void filterKeepsBeaconAndStableAdvertiserButDropsRotatingIdentity() {
        List<BleFingerprint> result = EnvironmentFilter.ble(Arrays.asList(
                new BleFingerprint("C2:00:00:00:00:01", "", -95, BEACON),
                new BleFingerprint("A4:C1:38:11:22:33", "watch", -70, "0201060AFF4C0010050B1C"),
                new BleFingerprint("A4:C1:38:11:22:33", "watch", -72, "0201060AFF4C0010050B1C"),
                new BleFingerprint("C2:00:00:00:00:02", "phone", -55, "0201060AFF4C0010051122"),
                new BleFingerprint("C2:00:00:00:00:03", "phone", -54, "0201060AFF4C0010051122"),
                new BleFingerprint("C2:00:00:00:00:04", "phone", -53, "0201060AFF4C0010051122")));
        assertEquals(2, result.size());
        assertTrue(result.get(0).beaconLike);
        assertEquals("A4:C1:38:11:22:33", result.get(1).address);
    }

    @Test public void profileCarriesEnvironmentAndKeepsLegacyFallback() throws Exception {
        EnvironmentSnapshot env = new EnvironmentSnapshot("lab", "实验室", 1000, true, 31.2, 121.4, 12, 5,
                Arrays.asList(wifi("lab", "02:11:22:33:44:55", -50, false), wifi("lab", "02:11:22:33:44:66", -42, true)),
                Collections.singletonList(new BleFingerprint("A4:C1:38:11:22:33", "beacon", -70, BEACON)),
                Collections.singletonList(new CellFingerprint("lte", "460", "00", 1234, 56789, 10, 1650, -85, true)));
        Profile profile = new Profile(new JSONObject().put("environment", env.json()).put("cell", true));
        assertEquals("02:11:22:33:44:66", profile.primaryWifi.bssid);
        assertEquals(2, profile.wifiPoints.size()); assertEquals(1, profile.blePoints.size());
        assertEquals(1, profile.cellPoints.size()); assertTrue(profile.hasCellEnvironment());
        Profile again = Profile.parse(profile.json().toString());
        assertEquals("lab", again.environment.id); assertEquals(1, again.blePoints.size());
        assertEquals("02:11:22:33:44:66", again.primaryWifi.bssid);
        Profile legacy = Profile.defaults();
        assertEquals("EnvSim-Lab", legacy.primaryWifi.ssid);
        assertTrue(legacy.blePoints.isEmpty()); assertFalse(legacy.hasCellEnvironment());
    }

    @Test public void maximumCapturedEnvironmentFitsTheSystemConfigChannel() throws Exception {
        List<WifiFingerprint> wifi = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++)
            wifi.add(wifi("lab-" + i, String.format(java.util.Locale.ROOT, "02:00:00:00:%02X:%02X", i + 1, i), -50 - i, i == 0));
        List<BleFingerprint> ble = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++)
            ble.add(new BleFingerprint(String.format(java.util.Locale.ROOT, "A4:C1:38:11:22:%02X", i), "device" + i,
                    -60 - i, "AA".repeat(255)));
        List<CellFingerprint> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++)
            cells.add(new CellFingerprint("lte", "460", "00", 100 + i, 1000 + i, i, 1650 + i, -80 - i, i == 0));
        EnvironmentSnapshot env = new EnvironmentSnapshot("max", "max", 1000, true, 31.2, 121.4, 12, 5, wifi, ble, cells);
        Profile profile = new Profile(new JSONObject().put("environment", env.json()).put("enabled", true));
        assertTrue(env.json().toString().length() < 60000);
        assertTrue(profile.json().toString().length() < 65536);
        assertEquals(32, profile.wifiPoints.size());
        assertEquals(16, profile.blePoints.size());
        assertEquals(8, profile.cellPoints.size());
    }

    private static WifiFingerprint wifi(String ssid, String bssid, int rssi, boolean connected) {
        return new WifiFingerprint(ssid, bssid, rssi, 2412, "[WPA2-PSK-CCMP][ESS]", connected);
    }
}
