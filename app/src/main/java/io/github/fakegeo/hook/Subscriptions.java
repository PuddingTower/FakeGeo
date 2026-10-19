package io.github.fakegeo.hook;

import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.location.DriftModel;
import de.robv.android.xposed.XposedBridge;

/** Each queued delivery checks cancellation again on the caller's executor. */
final class Subscriptions {
    interface Delivery { void accept(Location location) throws Exception; }
    private final RuntimeConfig config;
    private final Handler timer;
    private final Map<Key, Entry> entries = new HashMap<>();
    Subscriptions(RuntimeConfig config) {
        this.config = config;
        HandlerThread worker = new HandlerThread("FakeGeo-updates");
        worker.start(); timer = new Handler(worker.getLooper());
    }
    synchronized void register(Object token, String provider, long interval, float distance,
            long duration, int maxUpdates, Executor executor, Delivery delivery) {
        Key key = new Key(token, provider);
        Entry previous = entries.remove(key);
        if (previous != null) previous.close();
        Entry entry = new Entry(key, Math.max(500, interval), distance, duration, maxUpdates, executor, delivery);
        entries.put(key, entry); timer.post(entry);
    }
    synchronized boolean remove(Object token) {
        boolean found = false;
        java.util.Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> item = iterator.next();
            if (item.getKey().token.equals(token)) {
                item.getValue().close(); iterator.remove(); found = true;
            }
        }
        return found;
    }
    private synchronized void finish(Entry entry) {
        if (entries.get(entry.key) == entry) entries.remove(entry.key);
        entry.close();
    }
    private static final class Key {
        final Object token; final String provider;
        Key(Object token, String provider) { this.token = token; this.provider = provider; }
        @Override public int hashCode() { return 31 * token.hashCode() + provider.hashCode(); }
        @Override public boolean equals(Object value) {
            return value instanceof Key && token.equals(((Key) value).token) && provider.equals(((Key) value).provider);
        }
    }
    private final class Entry implements Runnable {
        final Key key; final long interval; final float minDistance;
        final Executor executor; final Delivery delivery; final DeliveryGate gate;
        boolean delivered;
        double lastLatitude, lastLongitude;
        Entry(Key key, long interval, float minDistance, long duration, int maxUpdates, Executor executor, Delivery delivery) {
            this.key = key; this.interval = interval; this.minDistance = minDistance;
            gate = new DeliveryGate(SystemClock.elapsedRealtime(), duration, maxUpdates);
            this.executor = executor; this.delivery = delivery;
        }
        void close() { gate.cancel(); timer.removeCallbacks(this); }
        @Override public void run() {
            Profile p = config.get();
            if (gate.finished(SystemClock.elapsedRealtime()) || !p.enabled || !p.location || !config.locationAllowed()) { finish(this); return; }
            float[] distance = new float[1];
            DriftModel.Sample sample = DriftModel.sample(p, SystemClock.elapsedRealtime());
            if (delivered) Location.distanceBetween(lastLatitude, lastLongitude, sample.latitude, sample.longitude, distance);
            if ((!delivered || minDistance <= 0 || distance[0] >= minDistance) && gate.queue(SystemClock.elapsedRealtime())) {
                try {
                    executor.execute(() -> {
                        boolean success = false;
                        try {
                            Profile current = config.get();
                            if (gate.mayDeliver(SystemClock.elapsedRealtime()) && current.enabled && current.location && config.locationAllowed()) {
                                Location fix=LocationFactory.create(current, key.provider);
                                DriftModel.Sample currentPoint=DriftModel.sample(current,SystemClock.elapsedRealtime());
                                delivery.accept(fix); success = true;
                                lastLatitude = currentPoint.latitude; lastLongitude = currentPoint.longitude;
                            }
                        } catch (Exception e) {
                            XposedBridge.log("FakeGeo: callback ended: " + e); gate.cancel();
                        } finally {
                            final boolean didDeliver = success;
                            timer.post(() -> {
                                gate.complete(didDeliver);
                                if (didDeliver) delivered = true;
                                if (gate.finished(SystemClock.elapsedRealtime())) finish(this);
                            });
                        }
                    });
                } catch (RuntimeException rejected) { finish(this); return; }
            }
            if (!gate.finished(SystemClock.elapsedRealtime())) timer.postDelayed(this, Math.max(p.interval, interval));
        }
    }
}
