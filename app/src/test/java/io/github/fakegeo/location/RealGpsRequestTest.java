package io.github.fakegeo.location;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RealGpsRequestTest {
    private JSONObject request() throws Exception { return new JSONObject().put("token","a".repeat(32)).put("elapsed",100000).put("wall",1800000000000L).put("duration",40000); }
    @Test public void expiresAndCannotReplayAcrossAReboot() throws Exception {
        RealGpsRequest p=new RealGpsRequest(request());
        assertTrue(p.active(100001,1800000000001L));assertFalse(p.active(140000,1800000040000L));
        assertFalse(p.active(500,1800000000000L));assertFalse(p.active(100001,1800086400001L));
    }
    @Test public void acceptsOnlyFreshNonMockFinitePositions() throws Exception {
        RealGpsRequest p=new RealGpsRequest(request());long now=104000,wall=1800000004000L;
        assertTrue(p.accepts(103000,now,wall,false,31.2,121.5,3));
        assertFalse(p.accepts(97000,now,wall,false,31.2,121.5,3));
        assertFalse(p.accepts(104001,now,wall,false,31.2,121.5,3));
        assertFalse(p.accepts(103000,now,wall,true,31.2,121.5,3));
        assertFalse(p.accepts(103000,now,wall,false,Double.NaN,121.5,3));
        assertFalse(p.accepts(103000,now,wall,false,91,121.5,3));
        assertFalse(p.accepts(103000,now,wall,false,31.2,181,3));
    }
    @Test public void rejectsMalformedTokensAndRequestDurations() throws Exception {
        for(String token:new String[]{"","../file","a".repeat(31),"A".repeat(32)})
            assertThrows(IllegalArgumentException.class,()->new RealGpsRequest(request().put("token",token)));
        assertThrows(IllegalArgumentException.class,()->new RealGpsRequest(request().put("duration",40001)));
        assertThrows(IllegalArgumentException.class,()->new RealGpsRequest(request().put("elapsed",-1)));
    }
    @Test public void rootCommandsUseFixedPathsAndAnEncodedPayloadWithoutPublishingVirtualProfiles() throws Exception {
        String token="b".repeat(32),begin=io.github.fakegeo.root.RealLocationCommands.begin(token,100000,1800000000000L);
        assertTrue(begin.contains("base64 -d"));assertTrue(begin.contains("chmod 600"));
        assertFalse(begin.contains("profile.new"));assertFalse(begin.contains("'"+token+"'"));
        assertTrue(io.github.fakegeo.root.RealLocationCommands.read(token).contains("REAL_GPS_PENDING"));
        assertTrue(io.github.fakegeo.root.RealLocationCommands.cancel(token).contains("grep -Fq"));
        assertThrows(IllegalArgumentException.class,()->io.github.fakegeo.root.RealLocationCommands.read("';id;#"));
        assertThrows(IllegalArgumentException.class,()->io.github.fakegeo.root.RealLocationCommands.cancel("../file"));
    }
}
