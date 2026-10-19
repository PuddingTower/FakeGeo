package io.github.fakegeo.environment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns repeated raw scans into a small set of anchors. Thresholds are deliberately conservative:
 * a weak, one-off beacon is noise, while an attendance beacon is kept even at a distance.
 */
public final class EnvironmentFilter {
    public static final int WIFI_MEDIAN_FLOOR = -88;
    public static final int WIFI_DIRECT_FLOOR = -75;
    public static final int BLE_MEDIAN_FLOOR = -90;
    public static final int BLE_DIRECT_FLOOR = -75;

    private EnvironmentFilter() {}

    public static List<WifiFingerprint> wifi(List<WifiFingerprint> samples) {
        Map<String, List<WifiFingerprint>> groups = new LinkedHashMap<>();
        if (samples != null) for (WifiFingerprint sample : samples) {
            if (sample == null) continue;
            groups.computeIfAbsent(sample.bssid, key -> new ArrayList<>()).add(sample);
        }
        List<WifiFingerprint> result = new ArrayList<>();
        for (List<WifiFingerprint> group : groups.values()) {
            List<Integer> values = new ArrayList<>();
            WifiFingerprint latest = group.get(group.size() - 1);
            boolean connected = false;
            for (WifiFingerprint sample : group) {
                values.add(sample.rssi);
                connected |= sample.connected;
                latest = sample;
            }
            int median = median(values);
            int best = best(values);
            if (!connected && !(group.size() >= 2 && median >= WIFI_MEDIAN_FLOOR) && best < WIFI_DIRECT_FLOOR)
                continue;
            try {
                result.add(new WifiFingerprint(latest.ssid, latest.bssid, median, latest.frequency,
                        latest.capabilities, connected));
            } catch (RuntimeException malformed) { /* Skip one corrupt scan entry. */ }
        }
        result.sort(Comparator.comparing((WifiFingerprint point) -> !point.connected)
                .thenComparingInt(point -> -point.rssi));
        return result.size() > EnvironmentSnapshot.MAX_WIFI
                ? new ArrayList<>(result.subList(0, EnvironmentSnapshot.MAX_WIFI)) : result;
    }

    public static List<BleFingerprint> ble(List<BleFingerprint> samples) {
        Map<String, List<BleFingerprint>> groups = new LinkedHashMap<>();
        if (samples != null) for (BleFingerprint sample : samples) {
            if (sample == null) continue;
            groups.computeIfAbsent(sample.address, key -> new ArrayList<>()).add(sample);
        }
        Map<String, Integer> identityAddresses = new HashMap<>();
        for (List<BleFingerprint> group : groups.values()) {
            String identity = identity(group.get(group.size() - 1));
            if (!identity.isEmpty()) identityAddresses.merge(identity, 1, Integer::sum);
        }
        List<BleFingerprint> result = new ArrayList<>();
        for (List<BleFingerprint> group : groups.values()) {
            List<Integer> values = new ArrayList<>();
            BleFingerprint latest = group.get(group.size() - 1);
            for (BleFingerprint sample : group) { values.add(sample.rssi); latest = sample; }
            int median = median(values);
            int best = best(values);
            boolean beacon = latest.beaconLike;
            boolean useful = !latest.payload.isEmpty() || !latest.name.isEmpty();
            if (!useful) continue;
            if (latest.isLocallyAdministered() && group.size() == 1 && !beacon && best < BLE_DIRECT_FLOOR) continue;
            String identity = identity(latest);
            boolean rotating = !identity.isEmpty() && identityAddresses.getOrDefault(identity, 0) >= 3 && !beacon;
            if (rotating) continue;
            if (!beacon && group.size() < 2 && best < BLE_DIRECT_FLOOR) continue;
            if (!beacon && median < BLE_MEDIAN_FLOOR) continue;
            try {
                result.add(new BleFingerprint(latest.address, latest.name, median, latest.payload));
            } catch (RuntimeException malformed) { /* Skip one corrupt advertiser. */ }
        }
        result.sort(Comparator.comparing((BleFingerprint point) -> !point.beaconLike)
                .thenComparingInt(point -> -point.rssi));
        return result.size() > EnvironmentSnapshot.MAX_BLE
                ? new ArrayList<>(result.subList(0, EnvironmentSnapshot.MAX_BLE)) : result;
    }

    public static List<CellFingerprint> cells(List<CellFingerprint> samples) {
        Map<String, CellFingerprint> unique = new LinkedHashMap<>();
        if (samples != null) for (CellFingerprint sample : samples) {
            if (sample == null || sample.ci <= 0) continue;
            CellFingerprint previous = unique.get(sample.key());
            if (previous == null || (!previous.registered && sample.registered)
                    || (previous.registered == sample.registered && sample.signal > previous.signal))
                unique.put(sample.key(), sample);
        }
        List<CellFingerprint> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing((CellFingerprint point) -> !point.registered)
                .thenComparingInt(point -> -point.signal));
        return result.size() > EnvironmentSnapshot.MAX_CELLS
                ? new ArrayList<>(result.subList(0, EnvironmentSnapshot.MAX_CELLS)) : result;
    }

    private static String identity(BleFingerprint point) {
        if (!point.payload.isEmpty()) return point.payload;
        return point.name.isEmpty() ? "" : point.name.toLowerCase(Locale.ROOT);
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        if (sorted.isEmpty()) return -90;
        return sorted.get(sorted.size() / 2);
    }

    private static int best(List<Integer> values) {
        int best = Integer.MIN_VALUE;
        for (int value : values) best = Math.max(best, value);
        return best == Integer.MIN_VALUE ? -90 : best;
    }
}
