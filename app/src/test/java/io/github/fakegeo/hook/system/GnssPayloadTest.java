package io.github.fakegeo.hook.system;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class GnssPayloadTest {
    @Test public void samplesAreDeterministicPerSecondAndNeverShareArrays() {
        GnssPayload first = GnssPayload.of(1000), second = GnssPayload.of(1000);
        assertEquals(first.count, second.count);
        assertArrayEquals(first.cn0, second.cn0, 0.0001f);
        assertNotSame(first.flags, second.flags);
        first.cn0[0] = 1; assertNotEquals(1.0, GnssPayload.of(1000).cn0[0], 0.0);
    }
    @Test public void everySampleIsADualConstellationMixInsideTheReceiverRange() {
        Set<Integer> counts = new HashSet<>();
        for (long second = 0; second < 240; second++) {
            GnssPayload p = GnssPayload.of(second);
            assertTrue("count=" + p.count, p.count >= GnssPayload.MIN_COUNT && p.count <= GnssPayload.MAX_COUNT);
            counts.add(p.count);
            boolean gps = false, beidou = false, dualBand = false;
            for (int i = 0; i < p.count; i++) {
                assertEquals(p.flags[i] >> 8, p.satelliteId[i]);
                assertEquals(p.flags[i] >> 4 & 15, p.constellation[i]);
                assertEquals(15, p.flags[i] & 15);
                assertTrue(p.cn0[i] >= 0 && p.cn0[i] <= 63);
                assertTrue(p.elevation[i] >= -90 && p.elevation[i] <= 90);
                assertTrue(p.azimuth[i] >= 0 && p.azimuth[i] <= 360);
                assertTrue(p.carrier[i] > 0);
                gps |= p.constellation[i] == GnssPayload.CONSTELLATION_GPS;
                beidou |= p.constellation[i] == GnssPayload.CONSTELLATION_BEIDOU;
                dualBand |= Math.abs(p.carrier[i] - 1176.45e6f) < 1e3;
            }
            assertTrue(gps); assertTrue(beidou); assertTrue(dualBand);
        }
        assertTrue("The constellation size must change over time", counts.size() > 1);
    }
    @Test public void meanAndMaxQualityMatchTheActiveSample() {
        for (long second : new long[]{0, 7, 41, 96}) {
            GnssPayload p = GnssPayload.of(second);
            float sum = 0, max = 0;
            for (float cn0 : p.cn0) { sum += cn0; max = Math.max(max, cn0); }
            assertEquals(sum / p.count, p.meanCn0, 0.0001f);
            assertEquals(max, p.maxCn0, 0.0001f);
        }
    }
    @Test public void legacyArgumentsFollowTheAndroidTenCallbackLayout() {
        GnssPayload p = GnssPayload.of(1234); Object[] args = p.legacyArguments();
        assertEquals(6, args.length); assertEquals(p.count, args[0]);
        assertEquals(p.count, ((int[]) args[1]).length); assertEquals(p.count, ((float[]) args[3]).length);
    }
    @Test public void unknownStatusSignaturesAreRejectedRatherThanGuessingArgumentTypes() {
        assertTrue(GnssPayload.legacySignature(new Class<?>[]{int.class,int[].class,float[].class,float[].class,float[].class,float[].class}));
        assertFalse(GnssPayload.legacySignature(new Class<?>[]{int.class,int[].class,float[].class,float[].class,float[].class}));
        assertFalse(GnssPayload.legacySignature(new Class<?>[]{int.class,int[].class,float[].class,float[].class,float[].class,double[].class}));
        assertFalse(GnssPayload.legacySignature(new Class<?>[]{Object.class}));
    }
}
