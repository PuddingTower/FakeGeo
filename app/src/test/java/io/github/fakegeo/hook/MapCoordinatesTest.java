package io.github.fakegeo.hook;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapCoordinatesTest {
    @Test public void knownMapPointRestoresGpsCoordinatesInsteadOfPublishingTheMapOffset() {
        double[] gps=Gcj02.toWgs84(39.910226498,116.403713582);
        assertEquals(39.908823,gps[0],1e-8); assertEquals(116.397470,gps[1],1e-8);
    }
    @Test public void reopenAndReselectDoesNotAccumulateOffsetAcrossCities() {
        for(double[] point:new double[][]{{31.2304,121.4737},{22.5431,114.0579},{30.5728,104.0668}}) {
            double lat=point[0],lon=point[1];
            for(int i=0;i<20;i++) { double[] map=Gcj02.fromWgs84(lat,lon); double[] gps=Gcj02.toWgs84(map[0],map[1]); lat=gps[0]; lon=gps[1]; }
            assertEquals(point[0],lat,1e-8); assertEquals(point[1],lon,1e-8);
        }
    }
    @Test public void overseasCoordinatesRemainUnchanged() {
        assertArrayEquals(new double[]{-33.86,151.2},Gcj02.toWgs84(-33.86,151.2),0);
    }
}
