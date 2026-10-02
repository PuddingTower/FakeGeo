package io.github.fakegeo.location;

import org.json.JSONObject;

/** A short-lived explicit request; elapsed/wall agreement prevents requests replaying after reboot. */
public final class RealGpsRequest {
    public final String token;
    public final long elapsed,wall,duration;
    public RealGpsRequest(JSONObject json) {
        token=json.optString("token");elapsed=json.optLong("elapsed");wall=json.optLong("wall");duration=json.optLong("duration");
        if(!token.matches("[a-f0-9]{32}") || elapsed<=0 || wall<=0 || duration<=0 || duration>40000)
            throw new IllegalArgumentException("Invalid real GPS request");
    }
    public boolean active(long now,long currentWall) {
        double age=(double)now-elapsed;
        return age>=0 && age<duration && Math.abs(age-((double)currentWall-wall))<=5000;
    }
    public boolean accepts(long fixElapsed,long now,long currentWall,boolean mock,double lat,double lon,double accuracy) {
        return active(now,currentWall) && !mock && Double.isFinite(lat) && Double.isFinite(lon)
                && lat>=-90 && lat<=90 && lon>=-180 && lon<=180
                && Double.isFinite(accuracy) && accuracy>=0
                && fixElapsed>=elapsed-2000 && fixElapsed<=now && now-fixElapsed<=5000;
    }
}
