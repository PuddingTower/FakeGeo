package io.github.fakegeo.hook.system;

/** Deterministic virtual constellation; these values do not describe physical satellites. */
public final class GnssPayload {
    public static final int COUNT = 12;
    public static final float MEAN_CN0 = 38f, MAX_CN0 = 43f;
    public final int[] flags = new int[COUNT];
    public final float[] cn0 = new float[COUNT], elevation = new float[COUNT], azimuth = new float[COUNT], carrier = new float[COUNT];
    public GnssPayload() {
        for (int i = 0; i < COUNT; i++) {
            // Android 10 layout: SVID << 8, GPS constellation << 4, ephemeris/almanac/fix/carrier flags.
            flags[i] = ((i+1) << 8) | (1 << 4) | 15;
            cn0[i] = 33 + (i%6)*2; elevation[i] = 20 + (i*7)%60; azimuth[i] = i*30; carrier[i] = 1575420000f;
        }
    }
    public static boolean legacySignature(Class<?>[] types) {
        if (types.length != 6 || types[0] != int.class || types[1] != int[].class) return false;
        for (int i = 2; i < types.length; i++) if (types[i] != float[].class) return false;
        return true;
    }
    public Object[] legacyArguments() { return new Object[]{COUNT, flags, cn0, elevation, azimuth, carrier}; }
}
