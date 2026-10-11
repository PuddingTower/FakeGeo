package io.github.fakegeo.motion;

import io.github.fakegeo.config.Profile;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure WGS84 motion and coherent bounded receiver drift, shared by every delivery path. */
public final class MotionModel {
    private static final double EARTH = 6371008.8;
    public static final long LEASE_MS = 6000;
    public static final class Sample {
        public final double latitude, longitude, altitude, baseSpeed;
        public final float speed, bearing, accuracy;
        Sample(double lat, double lon, double alt, double base, double speed, double bearing, double accuracy) {
            latitude=lat; longitude=lon; altitude=alt; baseSpeed=base;
            this.speed=(float)speed; this.bearing=(float)bearing; this.accuracy=(float)accuracy;
        }
    }
    public static Sample anchor(Profile p, long elapsed, long wall) { return evaluate(p, elapsed, wall, false); }
    public static Sample sample(Profile p, long elapsed, long wall) { return evaluate(p, elapsed, wall, true); }
    private static Sample evaluate(Profile p, long elapsed, long wall, boolean drift) {
        double lat=p.latitude, lon=p.longitude, speed=0, base=0, bearing=0;
        long age=elapsed-p.motionElapsed, wallAge=wall-p.motionWall;
        boolean valid=p.enabled && p.location && p.motionLease>0 && p.motionElapsed>0 && p.motionWall>0
                && age>=0 && Math.abs((double)age-wallAge)<=5000;
        if (valid) {
            double seconds=Math.min(age,p.motionLease)/1000.0;
            double start=p.motionElapsed/1000.0;
            // Simpson integration also follows acceleration, rather than distance = target speed * time.
            int count=24; double h=seconds/count, distance=0;
            for (int i=0;i<=count;i++) {
                double t=i*h;
                distance+=(i==0 || i==count?1:i%2==0?2:4)*baseSpeed(p,t)*NaturalPace.factor(p.motionMode,p.motionSeed,start+t);
            }
            distance*=h/3;
            double[] point=offset(lat,lon,distance*Math.sin(Math.toRadians(p.motionBearing)),distance*Math.cos(Math.toRadians(p.motionBearing)));
            lat=point[0]; lon=point[1]; bearing=p.motionBearing%360;
            if (age<p.motionLease) { base=baseSpeed(p,seconds); speed=base*NaturalPace.factor(p.motionMode,p.motionSeed,start+seconds); }
        }
        double accuracy=p.accuracy, altitude=p.altitude;
        if (drift && p.enabled && p.location && p.jitter && p.jitterMeters>0) {
            double t=elapsed/1000.0;
            double east=p.jitterMeters*(0.65*Math.sin(t/9.7)+0.20*Math.sin(t/3.1));
            double north=p.jitterMeters*(0.65*Math.cos(t/11.3)+0.20*Math.sin(t/4.9));
            double scale=Math.min(1,p.jitterMeters/Math.max(1e-12,Math.hypot(east,north)));
            double[] point=offset(lat,lon,east*scale,north*scale); lat=point[0];lon=point[1];
            accuracy=Math.max(p.accuracy,p.jitterMeters)*(1+0.08*Math.sin(t/13));
            altitude+=p.jitterMeters*0.2*Math.sin(t/15.7);
        }
        return new Sample(lat,lon,altitude,base,speed,bearing,accuracy);
    }
    private static double baseSpeed(Profile p,double seconds) {
        return p.motionInitialSpeed+(p.motionSpeed-p.motionInitialSpeed)*(1-Math.exp(-seconds/0.65));
    }
    /** Great-circle displacement keeps the date line and poles valid. east/north are metres. */
    public static double[] offset(double lat,double lon,double east,double north) {
        double distance=Math.hypot(east,north); if(distance==0) return new double[]{lat,lon};
        double bearing=Math.atan2(east,north), phi=Math.toRadians(lat), lambda=Math.toRadians(lon), delta=distance/EARTH;
        double nextPhi=Math.asin(Math.max(-1,Math.min(1,Math.sin(phi)*Math.cos(delta)+Math.cos(phi)*Math.sin(delta)*Math.cos(bearing))));
        double nextLambda=lambda+Math.atan2(Math.sin(bearing)*Math.sin(delta)*Math.cos(phi),Math.cos(delta)-Math.sin(phi)*Math.sin(nextPhi));
        return new double[]{Math.toDegrees(nextPhi),((Math.toDegrees(nextLambda)+540)%360)-180};
    }
    public static void clear(JSONObject json) throws JSONException {
        json.put("motionSpeed",0).put("motionInitialSpeed",0).put("motionElapsed",0).put("motionWall",0).put("motionLease",0);
    }
    private MotionModel() {}
}
