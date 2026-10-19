package io.github.fakegeo.hook;

import android.location.Location;
import android.os.SystemClock;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.hook.system.GnssPayload;
import io.github.fakegeo.hook.system.SystemLocations;
import io.github.fakegeo.location.DriftModel;

final class LocationFactory {
    private LocationFactory() {}
    static Location create(Profile profile, String provider) {
        Location result = new Location(provider == null ? "gps" : provider);
        apply(result, profile);
        result.setTime(System.currentTimeMillis());
        result.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return result;
    }
    static Location copy(Location source, Profile profile) {
        Location result = new Location(source);
        apply(result, profile);
        return result;
    }
    private static void apply(Location result, Profile profile) {
        DriftModel.Sample sample = DriftModel.sample(profile, SystemClock.elapsedRealtime());
        result.setLatitude(sample.latitude); result.setLongitude(sample.longitude);
        result.setAltitude(sample.altitude); result.setAccuracy(sample.accuracy);
        result.setSpeed(0f); result.setBearing(0f);
        result.setVerticalAccuracyMeters(sample.accuracy);
        result.setSpeedAccuracyMetersPerSecond(0.2f); result.setBearingAccuracyDegrees(2);
        result.setElapsedRealtimeUncertaintyNanos(GnssPayload.current().uncertaintyNanos());
        result.setExtras(SystemLocations.metadata(profile, result.getProvider()));
        // The flag survives a Location copy and parcel, so clear it on the object, not only on getters.
        if (android.os.Build.VERSION.SDK_INT >= 31) result.setMock(false);
        else try { de.robv.android.xposed.XposedHelpers.setObjectField(result, "mIsFromMockProvider", false); }
        catch (Throwable absent) { /* Older releases renamed the field; the getters still cover it. */ }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            result.removeMslAltitude(); result.removeMslAltitudeAccuracy();
        }
    }
}
