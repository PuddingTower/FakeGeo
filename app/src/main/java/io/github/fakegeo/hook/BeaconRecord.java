package io.github.fakegeo.hook;

import android.util.SparseArray;
import io.github.fakegeo.config.Profile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Builds the advertising payload a Bluetooth attendance machine broadcasts. */
public final class BeaconRecord {
    private static final int TYPE_FLAGS = 0x01, TYPE_NAME = 0x09, TYPE_MANUFACTURER = 0xFF;
    private static final int APPLE = 0x004C;
    private BeaconRecord() {}
    /** Prefer the captured bytes; a UUID is only enough to rebuild a standard iBeacon frame. */
    public static byte[] advertising(Profile profile) {
        if (!profile.beaconRecord.isEmpty()) return decode(profile.beaconRecord);
        if (profile.beaconUuid.isEmpty()) return new byte[0];
        byte[] uuid = decode(profile.beaconUuid);
        List<Byte> payload = new ArrayList<>();
        payload.add((byte) APPLE); payload.add((byte) (APPLE >> 8));
        payload.add((byte) 0x02); payload.add((byte) 0x15);
        for (byte value : uuid) payload.add(value);
        payload.add((byte) (profile.beaconMajor >> 8)); payload.add((byte) profile.beaconMajor);
        payload.add((byte) (profile.beaconMinor >> 8)); payload.add((byte) profile.beaconMinor);
        payload.add((byte) -59); // Measured power one metre from the beacon.
        byte[] advertising = new byte[payload.size()];
        for (int i = 0; i < payload.size(); i++) advertising[i] = payload.get(i);
        List<Byte> out = new ArrayList<>();
        structure(out, TYPE_FLAGS, (byte) 0x06);
        structure(out, TYPE_MANUFACTURER, advertising);
        // A legacy advertising packet holds 31 bytes; an iBeacon plus the local name does not fit,
        // so a named beacon must be captured verbatim into the raw field.
        return out.size() <= 31 ? bytes(out) : new byte[0];
    }
    public static byte[] decode(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("广播内容必须是完整的字节对");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    /** A legacy advertising packet for a captured device that only exposed a name. */
    public static byte[] named(String name) {
        if (name == null || name.isEmpty()) return new byte[0];
        List<Byte> out = new ArrayList<>();
        structure(out, TYPE_FLAGS, (byte) 0x06);
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 29) return new byte[0];
        structure(out, TYPE_NAME, bytes);
        return out.size() <= 31 ? bytes(out) : new byte[0];
    }
    /** {@code ScanRecord.parseFromBytes} is not public API, so fall back to filling its fields. */
    public static Object record(byte[] raw) throws ReflectiveOperationException {
        Class<?> type = android.bluetooth.le.ScanRecord.class;
        try { return XposedHelpers.callStaticMethod(type, "parseFromBytes", (Object) raw); }
        catch (Throwable blocked) { /* Hidden API enforcement inside the target process. */ }
        Object record = XposedHelpers.newInstance(type);
        XposedHelpers.setObjectField(record, "mBytes", raw);
        XposedHelpers.setIntField(record, "mAdvertiseFlags", -1);
        XposedHelpers.setObjectField(record, "mDeviceName", null);
        XposedHelpers.setObjectField(record, "mTxPowerLevel", Integer.MIN_VALUE);
        XposedHelpers.setObjectField(record, "mManufacturerSpecificData", new SparseArray<byte[]>());
        for (int cursor = 0; cursor + 1 < raw.length;) {
            int length = raw[cursor] & 0xFF;
            int end = cursor + 1 + length;
            if (length == 0 || end > raw.length) break;
            int field = raw[cursor + 1] & 0xFF;
            if (field == TYPE_FLAGS) XposedHelpers.setIntField(record, "mAdvertiseFlags", raw[cursor + 2] & 0xFF);
            else if (field == TYPE_NAME) XposedHelpers.setObjectField(record, "mDeviceName",
                    new String(raw, cursor + 2, end - cursor - 2, StandardCharsets.UTF_8));
            else if (field == TYPE_MANUFACTURER && end - cursor >= 4) {
                int companyId = (raw[cursor + 2] & 0xFF) | ((raw[cursor + 3] & 0xFF) << 8);
                byte[] data = new byte[end - cursor - 4];
                System.arraycopy(raw, cursor + 4, data, 0, data.length);
                ((SparseArray<byte[]>) XposedHelpers.getObjectField(record, "mManufacturerSpecificData")).put(companyId, data);
            }
            cursor = end;
        }
        return record;
    }
    private static void structure(List<Byte> out, int type, byte... payload) {
        out.add((byte) (payload.length + 1)); out.add((byte) type);
        for (byte value : payload) out.add(value);
    }
    private static byte[] bytes(List<Byte> values) {
        byte[] raw = new byte[values.size()];
        for (int i = 0; i < raw.length; i++) raw[i] = values.get(i);
        return raw;
    }
}
