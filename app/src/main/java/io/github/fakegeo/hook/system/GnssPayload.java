package io.github.fakegeo.hook.system;

import android.os.SystemClock;

/**
 * Deterministic dual-band virtual constellation for a Snapdragon L1/L5 receiver.
 * These values do not describe physical satellites; the same second always yields the same sample.
 */
public final class GnssPayload {
    public static final int MIN_COUNT = 18, MAX_COUNT = 22, SLOTS = MAX_COUNT;
    public static final int CONSTELLATION_GPS = 1, CONSTELLATION_GALILEO = 3, CONSTELLATION_BEIDOU = 4, CONSTELLATION_QZSS = 5;
    private static final float L1 = 1575.42e6f, L5 = 1176.45e6f, B1I = 1561.098e6f;
    private static final int[] CONSTELLATIONS = {
            CONSTELLATION_GPS, CONSTELLATION_GPS, CONSTELLATION_GPS, CONSTELLATION_GPS, CONSTELLATION_GPS,
            CONSTELLATION_GPS, CONSTELLATION_GPS, CONSTELLATION_GPS, CONSTELLATION_GPS,
            CONSTELLATION_BEIDOU, CONSTELLATION_BEIDOU, CONSTELLATION_BEIDOU, CONSTELLATION_BEIDOU,
            CONSTELLATION_BEIDOU, CONSTELLATION_BEIDOU, CONSTELLATION_BEIDOU, CONSTELLATION_BEIDOU,
            CONSTELLATION_GALILEO, CONSTELLATION_GALILEO, CONSTELLATION_GALILEO, CONSTELLATION_GALILEO,
            CONSTELLATION_QZSS };
    private static final int[] SATELLITE_IDS = {
            2, 5, 7, 11, 15, 18, 24, 28, 31,
            7, 10, 13, 21, 26, 32, 37, 42,
            4, 8, 12, 21, 193 };
    private static final float[] CARRIERS = {
            L1, L5, L1, L1, L5, L1, L1, L5, L1,
            B1I, L1, L5, B1I, L1, L5, B1I, L1,
            L1, L1, L1, L1, L1 };
    private static volatile GnssPayload cached;

    public final long tick;
    public final int count;
    public final int[] constellation, satelliteId, flags;
    public final float[] cn0, elevation, azimuth, carrier;
    public final float meanCn0, maxCn0;

    private GnssPayload(long tick, int count, int[] constellation, int[] satelliteId, int[] flags,
            float[] cn0, float[] elevation, float[] azimuth, float[] carrier, float meanCn0, float maxCn0) {
        this.tick = tick; this.count = count; this.constellation = constellation; this.satelliteId = satelliteId;
        this.flags = flags; this.cn0 = cn0; this.elevation = elevation; this.azimuth = azimuth; this.carrier = carrier;
        this.meanCn0 = meanCn0; this.maxCn0 = maxCn0;
    }
    /** One sample per elapsed second; the visible satellite count only steps every 30 seconds. */
    public static GnssPayload of(long second) {
        int count = MIN_COUNT + (int) Math.floorMod(second / 30, MAX_COUNT - MIN_COUNT + 1);
        int[] types = new int[count], ids = new int[count], flags = new int[count];
        float[] cn0 = new float[count], elevation = new float[count], azimuth = new float[count], carrier = new float[count];
        float total = 0, peak = 0;
        for (int i = 0; i < count; i++) {
            types[i] = CONSTELLATIONS[i]; ids[i] = SATELLITE_IDS[i];
            // Android 10 callback layout: SVID << 8, constellation << 4, almanac/ephemeris/fix/carrier flags.
            flags[i] = (ids[i] << 8) | (types[i] << 4) | 15;
            cn0[i] = clamp(29 + (i * 5) % 14 + 2f * (float) Math.sin((second + i * 1.7) / 6.0), 18f, 52f);
            elevation[i] = clamp(24 + (i * 7) % 62, 0f, 90f);
            azimuth[i] = (i * 30 + (i % 3) * 11) % 360;
            carrier[i] = CARRIERS[i];
            total += cn0[i]; peak = Math.max(peak, cn0[i]);
        }
        return new GnssPayload(second, count, types, ids, flags, cn0, elevation, azimuth, carrier, total / count, peak);
    }
    public static GnssPayload current() {
        long second = SystemClock.elapsedRealtime() / 1000;
        GnssPayload sample = cached;
        if (sample == null || sample.tick != second) { sample = of(second); cached = sample; }
        return sample;
    }
    public static boolean legacySignature(Class<?>[] types) {
        if (types.length != 6 || types[0] != int.class || types[1] != int[].class) return false;
        for (int i = 2; i < types.length; i++) if (types[i] != float[].class) return false;
        return true;
    }
    public Object[] legacyArguments() { return new Object[]{count, flags, cn0, elevation, azimuth, carrier}; }
    /** Clock uncertainty a dual-frequency L1/L5 combined fix reports on this receiver. */
    public double uncertaintyNanos() { return 18000 + (tick % 5) * 1250; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
