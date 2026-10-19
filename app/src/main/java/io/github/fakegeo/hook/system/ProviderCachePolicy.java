package io.github.fakegeo.hook.system;

import io.github.fakegeo.config.Profile;
import io.github.fakegeo.motion.MotionModel;

/** Motion renewals keep provider history; deliberate changes still invalidate stale caches. */
public final class ProviderCachePolicy {
    public static boolean clear(Profile previous,Profile next) {
        boolean before=active(previous),after=active(next);
        if(!before && !after) return false;
        if(previous!=null && previous.revision==next.revision && before==after) return false;
        return !(before && after && continuation(previous,next));
    }
    private static boolean active(Profile p) { return p!=null && p.enabled && p.systemMode && p.location; }
    private static boolean continuation(Profile a,Profile b) {
        if(a.motionLease<=0 || b.motionLease<=0 || b.motionElapsed<=a.motionElapsed
                || Math.abs((double)(b.motionWall-a.motionWall)-(b.motionElapsed-a.motionElapsed))>5000
                || !sameSettings(a,b)) return false;
        MotionModel.Sample anchor=MotionModel.anchor(a,b.motionElapsed,b.motionWall);
        double phi=Math.toRadians(anchor.latitude),nextPhi=Math.toRadians(b.latitude);
        double h=Math.pow(Math.sin((nextPhi-phi)/2),2)+Math.cos(phi)*Math.cos(nextPhi)
                *Math.pow(Math.sin(Math.toRadians(b.longitude-anchor.longitude)/2),2);
        return 12742017.6*Math.asin(Math.min(1,Math.sqrt(Math.max(0,h))))<=0.5;
    }
    private static boolean sameSettings(Profile a,Profile b) {
        return a.wifi==b.wifi && a.bluetooth==b.bluetooth && a.cell==b.cell && a.gnss==b.gnss
                && a.jitter==b.jitter && a.jitterMeters==b.jitterMeters && a.interval==b.interval
                && a.accuracy==b.accuracy && a.altitude==b.altitude && a.ssid.equals(b.ssid)
                && a.bssid.equals(b.bssid) && a.wifiMac.equals(b.wifiMac) && a.bluetoothMac.equals(b.bluetoothMac)
                && a.bluetoothName.equals(b.bluetoothName) && a.targets.equals(b.targets)
                && a.wifiConnected==b.wifiConnected && a.beaconEnabled()==b.beaconEnabled()
                && a.locationCacheTarget.equals(b.locationCacheTarget);
    }
    private ProviderCachePolicy() {}
}
