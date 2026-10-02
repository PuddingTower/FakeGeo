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
        Location gps = SystemLocations.create(p, "gps");
        if (gps.getExtras() == null || gps.getExtras().getInt("satellites") != GnssPayload.COUNT
                || gps.getExtras().getBoolean("mockLocation", true)) throw new IllegalStateException("GPS metadata mismatch");
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
        GnssStatus status = GnssStatusFactory.create();
        if (status.getSatelliteCount() != GnssPayload.COUNT) throw new IllegalStateException("GNSS count mismatch");
        for (int i=0; i<status.getSatelliteCount(); i++)
            if (!status.usedInFix(i) || status.getConstellationType(i) != GnssStatus.CONSTELLATION_GPS)
                throw new IllegalStateException("GNSS flags mismatch");
        Parcel parcel = Parcel.obtain();
        try {
            gps.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            Location decoded = Location.CREATOR.createFromParcel(parcel);
            if (decoded.getExtras().getInt("satellites") != status.getSatelliteCount())
                throw new IllegalStateException("Serialized GPS quality mismatch");
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                parcel.setDataSize(0); parcel.setDataPosition(0); status.writeToParcel(parcel, 0); parcel.setDataPosition(0);
                GnssStatus decodedStatus = GnssStatus.CREATOR.createFromParcel(parcel);
                if (decodedStatus.getSatelliteCount() != GnssPayload.COUNT || !decodedStatus.usedInFix(0))
                    throw new IllegalStateException("GNSS parcel mismatch");
            }
        } finally { parcel.recycle(); }
        return "SYSTEM_LOCATION_CONTRACT_PASSED: satellites=" + GnssPayload.COUNT + " mock=false; GPS and GNSS parcels, cached source isolation and provider preservation verified";
    }
}
