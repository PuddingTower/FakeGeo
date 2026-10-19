package io.github.fakegeo.hook.system;

import android.location.GnssStatus;
import android.location.Location;
import android.os.Parcel;
import io.github.fakegeo.config.Profile;
import org.json.JSONObject;

/** Runs real Android constructors and parceling without enabling any service hook. */
public final class SystemLocationRegression {
    public static String run() throws Exception {
        Profile p = new Profile(new JSONObject().put("gnss", true));
        GnssPayload sky = GnssPayload.current();
        Location gps = SystemLocations.create(p, "gps");
        if (gps.getExtras() == null || gps.getExtras().getInt("satellites") < GnssPayload.MIN_COUNT
                || gps.getExtras().getInt("satellites") > GnssPayload.MAX_COUNT
                || gps.getExtras().getFloat("maxCn0") <= 0 || !gps.getExtras().containsKey("meanCn0")
                || gps.getExtras().getBoolean("mockLocation", true)) throw new IllegalStateException("GPS metadata mismatch");
        if (!gps.hasElapsedRealtimeUncertaintyNanos() || gps.getElapsedRealtimeUncertaintyNanos() <= 0)
            throw new IllegalStateException("Virtual report lacks clock uncertainty");
        if (android.os.Build.VERSION.SDK_INT >= 31 ? gps.isMock() : gps.isFromMockProvider())
            throw new IllegalStateException("Virtual report has mock flag");
        Location cached = new Location("gps"); cached.setLatitude(1); cached.setLongitude(2);
        android.os.Bundle originalExtras = new android.os.Bundle(); originalExtras.putString("address", "old address");
        cached.setExtras(originalExtras);
        Location sanitized = SystemLocations.create(p, SystemLocations.provider(cached, "passive"));
        if (sanitized.getLatitude()!=p.latitude || sanitized.getLongitude()!=p.longitude
                || !"gps".equals(sanitized.getProvider()) || sanitized.getExtras().containsKey("address"))
            throw new IllegalStateException("Cached source sanitization failed");
        if (cached.getLatitude()!=1 || cached.getLongitude()!=2 || !cached.getExtras().containsKey("address"))
            throw new IllegalStateException("Cached source was mutated");
        if (!"gps".equals(SystemLocations.provider(new Location[]{cached},"passive"))
                || !"gps".equals(SystemLocations.provider(java.util.Collections.singletonList(cached),"passive")))
            throw new IllegalStateException("Passive source provider lost");
        GnssStatus status = GnssStatusFactory.create(sky);
        if (status.getSatelliteCount() != sky.count) throw new IllegalStateException("GNSS count mismatch");
        boolean sawGps = false, sawBeidou = false, sawDualBand = false;
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            if (!status.usedInFix(i)) throw new IllegalStateException("GNSS flags mismatch");
            sawGps |= status.getConstellationType(i) == GnssStatus.CONSTELLATION_GPS;
            sawBeidou |= status.getConstellationType(i) == GnssStatus.CONSTELLATION_BEIDOU;
            sawDualBand |= status.hasCarrierFrequencyHz(i) && Math.abs(status.getCarrierFrequencyHz(i) - 1176.45e6f) < 1e3;
        }
        if (!sawGps || !sawBeidou || !sawDualBand) throw new IllegalStateException("GNSS constellation mix mismatch");
        Parcel parcel = Parcel.obtain();
        try {
            gps.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            Location decoded = Location.CREATOR.createFromParcel(parcel);
            int serialized = decoded.getExtras().getInt("satellites");
            if (serialized < GnssPayload.MIN_COUNT || serialized > GnssPayload.MAX_COUNT)
                throw new IllegalStateException("Serialized GPS quality mismatch");
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                parcel.setDataSize(0); parcel.setDataPosition(0); status.writeToParcel(parcel, 0); parcel.setDataPosition(0);
                GnssStatus decodedStatus = GnssStatus.CREATOR.createFromParcel(parcel);
                if (decodedStatus.getSatelliteCount() != sky.count || !decodedStatus.usedInFix(0))
                    throw new IllegalStateException("GNSS parcel mismatch");
            }
        } finally { parcel.recycle(); }
        return "SYSTEM_LOCATION_CONTRACT_PASSED: satellites=" + sky.count + " mock=false; GPS and GNSS parcels, cached source isolation and provider preservation verified";
    }
}
