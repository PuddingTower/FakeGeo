package io.github.fakegeo.hook.system;

import android.location.GnssStatus;
import android.os.Build;

public final class GnssStatusFactory {
    private GnssStatusFactory() {}
    public static GnssStatus create() throws ReflectiveOperationException {
        GnssPayload payload = new GnssPayload();
        if (Build.VERSION.SDK_INT >= 30) {
            GnssStatus.Builder builder = new GnssStatus.Builder();
            for (int i = 0; i < GnssPayload.COUNT; i++)
                builder.addSatellite(GnssStatus.CONSTELLATION_GPS, i+1, payload.cn0[i], payload.elevation[i], payload.azimuth[i],
                        true, true, true, true, payload.carrier[i], false, 0f);
            return builder.build();
        }
        java.lang.reflect.Constructor<GnssStatus> constructor = GnssStatus.class.getDeclaredConstructor(int.class, int[].class,
                float[].class, float[].class, float[].class, float[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(payload.legacyArguments());
    }
}
