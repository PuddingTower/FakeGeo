package io.github.fakegeo.hook;

import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BeaconRecordTest {
    private Profile beacon() throws Exception {
        return new Profile(new JSONObject().put("bluetooth", true)
                .put("beaconMac", "02:00:00:00:00:20")
                .put("beaconUuid", "E2C56DB5DFFB48D2B060D0F5A71096E0")
                .put("beaconMajor", 1).put("beaconMinor", 2));
    }
    @Test public void uuidBuildsAStandardAppleIBeaconFrame() throws Exception {
        byte[] raw = BeaconRecord.advertising(beacon());
        assertEquals(30, raw.length);
        assertEquals(0x02, raw[0] & 0xFF); assertEquals(0x01, raw[1] & 0xFF); assertEquals(0x06, raw[2] & 0xFF);
        assertEquals(0x1A, raw[3] & 0xFF); assertEquals(0xFF, raw[4] & 0xFF);
        assertEquals(0x4C, raw[5] & 0xFF); assertEquals(0x00, raw[6] & 0xFF);
        assertEquals(0x02, raw[7] & 0xFF); assertEquals(0x15, raw[8] & 0xFF);
        assertArrayEquals(BeaconRecord.decode("E2C56DB5DFFB48D2B060D0F5A71096E0"), java.util.Arrays.copyOfRange(raw, 9, 25));
        assertEquals(0x00, raw[25] & 0xFF); assertEquals(0x01, raw[26] & 0xFF); // Major, big-endian.
        assertEquals(0x00, raw[27] & 0xFF); assertEquals(0x02, raw[28] & 0xFF); // Minor, big-endian.
        assertEquals(-59, raw[29]);
    }
    @Test public void capturedAdvertisingBytesWinOverTheTemplate() throws Exception {
        Profile p = new Profile(new JSONObject(beacon().json().toString()).put("beaconRecord", "0201061AFF4C000215"));
        assertArrayEquals(new byte[]{0x02, 0x01, 0x06, 0x1A, (byte) 0xFF, 0x4C, 0x00, 0x02, 0x15}, BeaconRecord.advertising(p));
    }
    @Test public void anUnconfiguredBeaconYieldsNoFrame() {
        assertEquals(0, BeaconRecord.advertising(Profile.defaults()).length);
    }
    @Test public void oddLengthHexadecimalIsRefusedRatherThanHalfDecoded() {
        try {
            BeaconRecord.decode("ABC");
            fail("Odd-length hexadecimal must be rejected");
        } catch (IllegalArgumentException expected) { /* Both halves of a byte are required. */ }
    }
}
