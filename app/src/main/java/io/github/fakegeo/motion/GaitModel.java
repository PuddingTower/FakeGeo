package io.github.fakegeo.motion;

import io.github.fakegeo.config.Profile;

/** One gait clock per application process; shared by every sensor and listener. SI units. */
public final class GaitModel {
    public static boolean enabled(Profile p) {
        return p.enabled && p.location && p.sensors && p.motionMode != 2;
    }

    public static final class Frame {
        public final float[] acceleration, linear, gravity, gyroscope;
        public final long steps;
        public final double cadence;
        Frame(float[] linear, float[] gravity, float[] gyro, long steps, double cadence) {
            this.linear = linear; this.gravity = gravity; this.gyroscope = gyro;
            acceleration = new float[]{linear[0]+gravity[0], linear[1]+gravity[1], linear[2]+gravity[2]};
            this.steps = steps; this.cadence = cadence;
        }
    }

    public static double cadence(int mode, double speed) {
        if (mode == 2 || speed < .15) return 0;
        double stride = mode == 1 ? Math.min(1.55, .70 + .14 * speed) : Math.min(.95, .45 + .20 * speed);
        return Math.min(mode == 1 ? 3.4 : 2.5, speed / stride);
    }

    public static final class State {
        private long lastElapsed = -1;
        private double phase, lastCadence;
        private int lastMode = -1;
        private long lastSeed;
        private boolean wasEnabled;
        private Frame cached;

        public synchronized Frame sample(Profile p, long elapsed, long wall) {
            if (elapsed == lastElapsed && cached != null) return cached;
            boolean enabled = enabled(p);
            MotionModel.Sample motion = MotionModel.anchor(p, elapsed, wall);
            double nextCadence = enabled ? cadence(p.motionMode, motion.speed) : 0;
            // Do not replay missed steps after a blocked Looper, stop, mode change or stale lease.
            long delta = elapsed - lastElapsed;
            if (lastElapsed >= 0 && delta > 0 && delta <= 250 && wasEnabled && enabled
                    && lastMode == p.motionMode && lastSeed == p.motionSeed && nextCadence > 0 && lastCadence > 0)
                phase += (nextCadence + lastCadence) * .5 * delta / 1000.0;
            lastElapsed = elapsed; lastCadence = nextCadence; wasEnabled = enabled;
            lastMode = p.motionMode; lastSeed = p.motionSeed;
            double theta = 2 * Math.PI * (phase % 2), omega = nextCadence * 2 * Math.PI;
            double envelope = nextCadence > 0 ? Math.min(1, motion.speed / .8) : 0;
            double roll = .08 + .07 * envelope * Math.sin(theta + .6);
            double pitch = .40 + .045 * envelope * Math.sin(theta / 2);
            float[] gravity = {(float)(-9.80665 * Math.sin(roll)),
                    (float)(9.80665 * Math.sin(pitch) * Math.cos(roll)),
                    (float)(9.80665 * Math.cos(pitch) * Math.cos(roll))};
            double impact = p.motionMode == 1 ? 2.6 : 1.1;
            float[] linear = {(float)(envelope * .3 * Math.sin(theta / 2)),
                    (float)(envelope * .45 * Math.sin(theta + .3)),
                    (float)(envelope * impact * (Math.cos(theta) + .35 * Math.cos(2 * theta)))};
            float[] gyro = {(float)(.045 * envelope * omega / 2 * Math.cos(theta / 2)),
                    (float)(.07 * envelope * omega * Math.cos(theta + .6)),
                    (float)(.025 * envelope * omega / 2 * Math.cos(theta / 2))};
            cached = new Frame(linear, gravity, gyro, (long)Math.floor(phase), nextCadence);
            return cached;
        }
    }

    private GaitModel() {}
}
