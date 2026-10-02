package io.github.fakegeo.hook.system;

import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.util.SparseArray;
import io.github.fakegeo.config.Profile;
import java.lang.reflect.Field;
import java.util.ArrayList;

/** Copies app-facing snapshots. Never changes the controller's live WifiInfo or capabilities. */
public final class WifiIdentity {
    private WifiIdentity() {}
    public static WifiInfo copy(WifiInfo source, Profile p) throws ReflectiveOperationException {
        WifiInfo result;
        if (Build.VERSION.SDK_INT >= 31) result = source.makeCopy(0);
        else {
            java.lang.reflect.Constructor<WifiInfo> constructor = WifiInfo.class.getDeclaredConstructor(WifiInfo.class);
            constructor.setAccessible(true); result = constructor.newInstance(source);
        }
        // Keep privacy sentinels produced by Android's permission filtering.
        if (visible(source.getSSID()) && !"<unknown ssid>".equals(source.getSSID()))
            set(result, "mWifiSsid", ssid(p.ssid));
        if (visibleMac(source.getBSSID())) set(result, "mBSSID", p.bssid);
        if (visibleMac(source.getMacAddress())) set(result, "mMacAddress", p.wifiMac);
        optional(result, "mNetworkId", source.getNetworkId() < 0 ? -1 : 1);
        // These are independently serialized; changing SSID/BSSID alone leaves real identities.
        for (String name : new String[]{"mNetworkKey", "mPasspointUniqueId", "mFqdn",
                "mProviderFriendlyName", "mInformationElements", "mApMldMacAddress"}) optional(result, name, null);
        optional(result, "mVendorData", new ArrayList<>());
        optional(result, "mAffiliatedMloLinks", new ArrayList<>());
        optional(result, "mAffiliatedMloLinksMap", new SparseArray<>());
        optional(result, "mApMloLinkId", -1);
        return result;
    }
    public static NetworkCapabilities copy(NetworkCapabilities source, Profile p) throws ReflectiveOperationException {
        NetworkCapabilities result = copyCapabilities(source);
        Object transport = source.getTransportInfo();
        if (transport instanceof WifiInfo) set(result, "mTransportInfo", copy((WifiInfo) transport, p));
        Object name = get(result, "mSSID");
        if (name != null && (source.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || transport instanceof WifiInfo))
            set(result, "mSSID", "\"" + p.ssid.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        Object specifier = get(result, "mNetworkSpecifier");
        if (specifier != null && specifier.getClass().getName().startsWith("android.net.wifi."))
            set(result, "mNetworkSpecifier", null);
        return result;
    }
    /** An identity-only fallback retains network availability and validation capabilities. */
    public static NetworkCapabilities withoutIdentity(NetworkCapabilities source) throws ReflectiveOperationException {
        NetworkCapabilities result = copyCapabilities(source);
        if (result.getTransportInfo() instanceof WifiInfo) set(result, "mTransportInfo", null);
        set(result, "mSSID", null);
        Object specifier = get(result, "mNetworkSpecifier");
        if (specifier != null && specifier.getClass().getName().startsWith("android.net.wifi."))
            set(result, "mNetworkSpecifier", null);
        return result;
    }
    private static boolean visible(String value) { return value != null && !value.isEmpty(); }
    private static NetworkCapabilities copyCapabilities(NetworkCapabilities source) throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT >= 30) return new NetworkCapabilities(source);
        // The constructor exists on Android 10 but is absent from that version's public SDK.
        java.lang.reflect.Constructor<NetworkCapabilities> constructor = NetworkCapabilities.class.getDeclaredConstructor(NetworkCapabilities.class);
        constructor.setAccessible(true); return constructor.newInstance(source);
    }
    private static boolean visibleMac(String value) { return visible(value) && !"02:00:00:00:00:00".equals(value); }
    private static Object ssid(String value) throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.net.wifi.WifiSsid");
        java.lang.reflect.Method method;
        try { method = type.getDeclaredMethod("fromBytes", byte[].class); }
        catch (NoSuchMethodException old) { method = type.getDeclaredMethod("createFromByteArray", byte[].class); }
        method.setAccessible(true); return method.invoke(null, (Object) value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static Field field(Object object, String name) throws NoSuchFieldException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException absent) { /* A vendor subclass may inherit the platform field. */ }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object object, String name) throws ReflectiveOperationException { return field(object, name).get(object); }
    private static void set(Object object, String name, Object value) throws ReflectiveOperationException { field(object, name).set(object, value); }
    private static void optional(Object object, String name, Object value) throws ReflectiveOperationException {
        try { set(object, name, value); } catch (NoSuchFieldException olderRelease) { /* Fields added in later Android releases. */ }
    }
}
