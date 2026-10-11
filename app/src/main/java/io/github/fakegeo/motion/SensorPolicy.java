package io.github.fakegeo.motion;

/** Android sensor IDs kept here so permission and rate rules are testable without a device. */
public final class SensorPolicy {
    public static boolean supports(int type) {
        return type == 1 || type == 4 || type == 9 || type == 10 || type == 16
                || type == 18 || type == 19 || type == 35;
    }
    public static boolean step(int type) { return type == 18 || type == 19; }
    public static long periodMillis(int delayUs, int minimumDelayUs) {
        // Never synthesize faster than hardware permits or faster than 50 Hz.
        return Math.max(20, (Math.max(0L, Math.max(delayUs, minimumDelayUs)) + 999) / 1000);
    }
    private SensorPolicy() {}
}
