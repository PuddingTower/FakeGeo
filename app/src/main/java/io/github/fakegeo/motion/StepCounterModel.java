package io.github.fakegeo.motion;

/** Retains already delivered virtual steps when native sampling resumes; no counter rollback. */
public final class StepCounterModel {
    private float lastPhysical, output;
    private long lastVirtual;
    private boolean physicalSeen, virtualStarted;

    public synchronized float nativeValue(float physical, boolean simulated) {
        if (!Float.isFinite(physical) || physical < 0) return physical;
        if (!virtualStarted) output=physical;
        else if (!simulated && physicalSeen) output+=Math.max(0,physical-lastPhysical);
        lastPhysical=physical;physicalSeen=true;
        return virtualStarted?output:physical;
    }

    public synchronized float virtualValue(long steps) {
        if (virtualStarted) output+=Math.max(0,steps-lastVirtual);
        virtualStarted=true;lastVirtual=steps;
        return output;
    }
}
