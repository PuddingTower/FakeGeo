package io.github.fakegeo.config;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileTest {
    @Test public void defaultIsDisabledAndCannotActivateAnyTarget() {
        Profile p = Profile.defaults(); assertFalse(p.enabled); assertTrue(p.targets.isEmpty());
    }
    @Test public void roundTripPreservesVirtualEnvironmentAndAllowlist() throws Exception {
        JSONObject j = new JSONObject(); j.put("enabled", true); j.put("targets", "com.example.one\nio.github.fakegeo.probe");
        j.put("latitude", -33.86); j.put("longitude", 151.2); j.put("ssid", "实验室"); j.put("cell", false);
        Profile source = new Profile(j), result = Profile.parse(source.json().toString());
        assertTrue(result.enabled); assertFalse(result.cell); assertEquals(-33.86, result.latitude, 0);
        assertEquals(151.2, result.longitude, 0); assertEquals("实验室", result.ssid); assertEquals(source.targets, result.targets);
        assertThrows(UnsupportedOperationException.class, () -> result.targets.add("com.other.app"));
    }
    @Test public void refusesEnabledProfileWithoutTargets() throws Exception {
        JSONObject j = new JSONObject().put("enabled", true).put("systemMode", false);
        assertThrows(IllegalArgumentException.class, () -> new Profile(j));
    }
    @Test public void globalSystemModeDoesNotRequireAppTargets() throws Exception {
        Profile p = new Profile(new JSONObject().put("enabled", true).put("systemMode", true).put("revision", 7));
        assertTrue(p.enabled); assertTrue(p.systemMode); assertTrue(p.targets.isEmpty());
        assertEquals(7, Profile.parse(p.json().toString()).revision);
    }
    @Test public void preventsTheSystemDroppingAnEnabledZeroZeroFix() throws Exception {
        JSONObject point = new JSONObject().put("enabled", true).put("latitude", 0).put("longitude", 0);
        assertThrows(IllegalArgumentException.class, () -> new Profile(point));
        assertTrue(new Profile(new JSONObject(point.toString()).put("longitude", 1)).enabled);
        assertFalse(new Profile(new JSONObject(point.toString()).put("enabled", false)).enabled);
        assertTrue(new Profile(new JSONObject(point.toString()).put("location", false)).enabled);
    }
    @Test public void enforcesSsidByteLimitAndIntegralInterval() throws Exception {
        JSONObject unicode = new JSONObject().put("ssid", "中".repeat(11));
        JSONObject fractional = new JSONObject().put("interval", 999.5);
        assertThrows(IllegalArgumentException.class, () -> new Profile(unicode));
        assertThrows(IllegalArgumentException.class, () -> new Profile(fractional));
    }
    @Test public void concealmentAndBeaconFieldsSurvivePublishing() throws Exception {
        Profile defaults = Profile.defaults();
        assertTrue(defaults.antiDetect); assertTrue(defaults.keepAlive); assertFalse(defaults.wifiConnected);
        assertFalse(defaults.beaconEnabled());
        Profile beacon = new Profile(new JSONObject().put("bluetooth", true).put("beaconMac", "02:00:00:00:00:20")
                .put("beaconRecord", "0201061AFF4C000215").put("beaconMajor", 7).put("beaconMinor", 9).put("wifiConnected", true));
        Profile again = Profile.parse(beacon.json().toString());
        assertTrue(again.beaconEnabled()); assertTrue(again.wifiConnected);
        assertEquals("02:00:00:00:00:20", again.beaconMac);
        assertEquals("0201061AFF4C000215", again.beaconRecord);
        assertEquals(7, again.beaconMajor); assertEquals(9, again.beaconMinor);
        assertThrows(IllegalArgumentException.class, () -> new Profile(new JSONObject().put("beaconMac", "02:00:00:00:00:20")));
        assertThrows(IllegalArgumentException.class, () -> new Profile(new JSONObject().put("beaconRecord", "ZZ")));
    }
    @Test public void obsoleteSdkCompatibilityFieldsAreIgnoredAndNotPublished() throws Exception {
        Profile p = new Profile(new JSONObject().put("sdkCompat", true).put("sdkTargets", "com.wisedu.cpdaily"));
        assertTrue(p.systemMode);assertTrue(p.targets.isEmpty());
        assertFalse(p.json().has("sdkCompat"));assertFalse(p.json().has("sdkTargets"));
    }
    @Test public void gnssAdaptationIsExplicitAndSurvivesPublishing() throws Exception {
        assertFalse(Profile.defaults().gnss);
        Profile p = new Profile(new JSONObject().put("gnss", true));
        assertTrue(Profile.parse(p.json().toString()).gnss);
        assertFalse(Profile.parse("{\"enabled\":false,\"systemMode\":true}").gnss);
    }
}
