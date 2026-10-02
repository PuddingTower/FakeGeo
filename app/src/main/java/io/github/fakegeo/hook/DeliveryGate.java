package io.github.fakegeo.hook;

/** Cancellation, expiry and single-update limits shared by queued location deliveries. */
final class DeliveryGate {
    private final long deadline;
    private final int maxUpdates;
    private boolean canceled, pending;
    private int delivered;
    DeliveryGate(long startedAt, long duration, int maxUpdates) {
        if (duration < 0 || maxUpdates < 1) throw new IllegalArgumentException("Invalid location request limits");
        deadline = duration == Long.MAX_VALUE ? Long.MAX_VALUE : startedAt + Math.min(duration, Long.MAX_VALUE - startedAt);
        this.maxUpdates = maxUpdates;
    }
    synchronized boolean finished(long now) { return canceled || now >= deadline || delivered >= maxUpdates; }
    synchronized boolean queue(long now) {
        if (pending || finished(now)) return false;
        pending = true; return true;
    }
    synchronized boolean mayDeliver(long now) { return pending && !finished(now); }
    synchronized void complete(boolean success) { if (success) delivered++; pending = false; }
    synchronized void cancel() { canceled = true; }
}
