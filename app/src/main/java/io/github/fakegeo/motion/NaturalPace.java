package io.github.fakegeo.motion;

/** Smooth, seeded changes in effort. All consumers evaluate the same boot-time curve. */
public final class NaturalPace {
    public static double factor(int mode, long seed, double seconds) {
        double slow = noise(seconds / 17.0, seed);
        double effort = noise(seconds / 4.3, seed ^ 0x632BE59BD9B4E019L);
        double stride = noise(seconds / 0.83, seed ^ 0x157260D9F32A2C85L);
        if (mode == 2) return 1 + .035 * slow + .02 * effort + .006 * stride;
        if (mode == 1) return 1 + .11 * slow + .07 * effort + .02 * stride;
        return 1 + .085 * slow + .055 * effort + .015 * stride;
    }

    private static double noise(double time, long seed) {
        long index = (long) Math.floor(time);
        double fraction = time - index;
        double blend = fraction * fraction * fraction * (fraction * (fraction * 6 - 15) + 10);
        double a = value(index, seed), b = value(index + 1, seed);
        return a + (b - a) * blend;
    }

    private static double value(long index, long seed) {
        long z = index + seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53 * 2 - 1;
    }

    private NaturalPace() {}
}
