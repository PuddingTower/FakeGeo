package io.github.fakegeo.location;

import io.github.fakegeo.config.Profile;

/** Coherent bounded receiver drift around the published anchor. */
public final class DriftModel {
    private static final double EARTH = 6371008.8;

    public static final class Sample {
        public final double latitude, longitude, altitude;
        public final float accuracy;
        private Sample(double latitude, double longitude, double altitude, float accuracy) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.accuracy = accuracy;
        }
    }

    public static Sample sample(Profile p, long elapsed) {
        double lat = p.latitude, lon = p.longitude, altitude = p.altitude;
        float accuracy = p.accuracy;
        if (p.enabled && p.location && p.jitter && p.jitterMeters > 0) {
            double t = elapsed / 1000.0;
            double east = p.jitterMeters * (0.65 * Math.sin(t / 9.7) + 0.20 * Math.sin(t / 3.1));
            double north = p.jitterMeters * (0.65 * Math.cos(t / 11.3) + 0.20 * Math.sin(t / 4.9));
            double scale = Math.min(1, p.jitterMeters / Math.max(1e-12, Math.hypot(east, north)));
            double[] point = offset(lat, lon, east * scale, north * scale);
            lat = point[0];
            lon = point[1];
            accuracy = (float)(Math.max(p.accuracy, p.jitterMeters) * (1 + 0.08 * Math.sin(t / 13)));
            altitude += p.jitterMeters * 0.2 * Math.sin(t / 15.7);
        }
        return new Sample(lat, lon, altitude, accuracy);
    }

    /** Great-circle displacement keeps the date line and poles valid. east/north are metres. */
    private static double[] offset(double lat, double lon, double east, double north) {
        double distance = Math.hypot(east, north);
        if (distance == 0) return new double[]{lat, lon};
        double bearing = Math.atan2(east, north), phi = Math.toRadians(lat), lambda = Math.toRadians(lon), delta = distance / EARTH;
        double nextPhi = Math.asin(Math.max(-1, Math.min(1, Math.sin(phi) * Math.cos(delta) + Math.cos(phi) * Math.sin(delta) * Math.cos(bearing))));
        double nextLambda = lambda + Math.atan2(Math.sin(bearing) * Math.sin(delta) * Math.cos(phi), Math.cos(delta) - Math.sin(phi) * Math.sin(nextPhi));
        return new double[]{Math.toDegrees(nextPhi), ((Math.toDegrees(nextLambda) + 540) % 360) - 180};
    }

    private DriftModel() {}
}
