package io.github.fakegeo.hook.system;

import android.location.Location;
import android.os.SystemClock;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.location.DriftModel;
import java.util.Collections;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class SystemLocations {
    private SystemLocations() {}
    /** Shared GPS quality metadata so the system and per-app delivery paths never disagree. */
    public static android.os.Bundle metadata(Profile p, String provider) {
        android.os.Bundle metadata = new android.os.Bundle();
        metadata.putBoolean("mockLocation", false);
        if (p.gnss && "gps".equals(provider)) {
            GnssPayload sky = GnssPayload.current();
            metadata.putInt("satellites", sky.count);
            metadata.putFloat("meanCn0", sky.meanCn0);
            metadata.putFloat("maxCn0", sky.maxCn0);
        }
        return metadata;
    }
    static String provider(Object value, String fallback) throws ReflectiveOperationException {
        Object last = value;
        if (value instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) value;
            last = list.isEmpty() ? null : list.get(list.size()-1);
        } else if (value instanceof Location[]) {
            Location[] locations = (Location[]) value;
            last = locations.length == 0 ? null : locations[locations.length-1];
        } else if (value != null && value.getClass().getName().equals("android.location.LocationResult")) {
            last = value.getClass().getMethod("getLastLocation").invoke(value);
        }
        if (last instanceof Location) {
            String provider = ((Location) last).getProvider();
            if (provider != null && !provider.isEmpty()) return provider;
        }
        return fallback;
    }
    static Location create(Profile p, String provider) {
        Location result = new Location(provider);
        DriftModel.Sample sample = DriftModel.sample(p, SystemClock.elapsedRealtime());
        result.setLatitude(sample.latitude); result.setLongitude(sample.longitude); result.setAltitude(sample.altitude);
        result.setAccuracy(sample.accuracy); result.setVerticalAccuracyMeters(sample.accuracy);
        result.setSpeed(0f); result.setBearing(0f); result.setSpeedAccuracyMetersPerSecond(0.2f); result.setBearingAccuracyDegrees(2);
        result.setTime(System.currentTimeMillis()); result.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        result.setElapsedRealtimeUncertaintyNanos(GnssPayload.current().uncertaintyNanos());
        if (android.os.Build.VERSION.SDK_INT >= 31) result.setMock(false);
        android.os.Bundle metadata = metadata(p, provider);
        result.setExtras(metadata);
        if (android.os.Build.VERSION.SDK_INT == 29) {
            // Android 10 derives coarse positions from this internal extra.
            metadata.putParcelable("noGPSLocation", new Location(result)); result.setExtras(metadata);
        }
        return result;
    }
    static Object batch(Class<?> type, Location location) throws ReflectiveOperationException {
        if (type == Location.class) return location;
        if (type == Location[].class) return new Location[]{location};
        if (java.util.List.class.isAssignableFrom(type)) return new java.util.ArrayList<>(Collections.singletonList(location));
        if (!type.getName().equals("android.location.LocationResult")) throw new IllegalArgumentException("Unsupported location batch: " + type);
        for (String name : new String[]{"wrap", "create"}) for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) continue;
            Class<?> argument = method.getParameterTypes()[0]; Object value;
            if (argument == Location[].class) value = new Location[]{location};
            else if (java.util.List.class.isAssignableFrom(argument)) value = new java.util.ArrayList<>(Collections.singletonList(location));
            else continue;
            method.setAccessible(true); return method.invoke(null, new Object[]{value});
        }
        throw new NoSuchMethodException("No supported LocationResult factory");
    }
}
