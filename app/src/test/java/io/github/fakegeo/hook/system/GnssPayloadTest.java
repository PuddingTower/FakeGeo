package io.github.fakegeo.hook.system;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class GnssPayloadTest {
    @Test public void legacyPayloadHasValidConsistentSatellitesAndFixFlags() {
        GnssPayload p = new GnssPayload(); Set<Integer> svids = new HashSet<>();
        for (int i=0; i<GnssPayload.COUNT; i++) {
            assertTrue(svids.add(p.flags[i] >> 8)); assertEquals(1, (p.flags[i] >> 4) & 15);
            assertEquals(15, p.flags[i] & 15);
            assertTrue(p.cn0[i] >= 0 && p.cn0[i] <= 63); assertTrue(p.elevation[i] >= -90 && p.elevation[i] <= 90);
            assertTrue(p.azimuth[i] >= 0 && p.azimuth[i] <= 360); assertTrue(p.carrier[i] > 0);
        }
        assertEquals(GnssPayload.COUNT, svids.size()); assertEquals(GnssPayload.COUNT, p.legacyArguments()[0]);
        float sum=0, max=0; for (float cn0 : p.cn0) { sum+=cn0; max=Math.max(max, cn0); }
        assertEquals(GnssPayload.MEAN_CN0, sum/GnssPayload.COUNT, 0.0001f);
        assertEquals(GnssPayload.MAX_CN0, max, 0);
    }
    @Test public void unknownStatusSignaturesAreRejectedRatherThanGuessingArgumentTypes() {
        assertTrue(GnssPayload.legacySignature(new Class<?>[]{int.class,int[].class,float[].class,float[].class,float[].class,float[].class}));
        assertFalse(GnssPayload.legacySignature(new Class<?>[]{int.class,int[].class,float[].class,float[].class,float[].class}));
        assertFalse(GnssPayload.legacySignature(new Class<?>[]{int.class,int[].class,float[].class,float[].class,float[].class,double[].class}));
        assertFalse(GnssPayload.legacySignature(new Class<?>[]{Object.class}));
    }
    @Test public void statusReportsNeverReuseMutableArraysAcrossCalls() {
        GnssPayload p = new GnssPayload(); p.flags[0]=0;
        assertNotEquals(0, new GnssPayload().flags[0]);
    }
}
