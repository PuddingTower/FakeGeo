package io.github.fakegeo.debug;

import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Parcel;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.hook.system.WifiIdentity;
import java.lang.reflect.Field;
import org.json.JSONObject;

/** Synthetic framework snapshots only; does not query radios or publish a profile. */
public final class WifiIdentityRegression {
    public static void main(String[] args) throws Exception { System.out.println(run()); }
    public static String run() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 31) throw new IllegalStateException("This contract uses Android 12+ WifiInfo redaction");
        Profile p = new Profile(new JSONObject().put("ssid", "Virtual-Regression"));
        WifiInfo original = new WifiInfo.Builder().setSsid("Synthetic-Source".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setBssid("12:34:56:78:9a:bc").setNetworkId(18).setRssi(-61).build();
        field(original, "mNetworkKey").set(original, "Synthetic-Source-network-key");
        WifiInfo virtual = WifiIdentity.copy(original, p);
        if (!virtual.getSSID().contains(p.ssid) || !p.bssid.equals(virtual.getBSSID())
                || virtual.toString().contains("Synthetic-Source") || field(virtual, "mNetworkKey").get(virtual) != null)
            throw new AssertionError("Wi-Fi serialized identity was not cleared");
        if (!original.getSSID().contains("Synthetic-Source") || !"12:34:56:78:9a:bc".equals(original.getBSSID())
                || !"Synthetic-Source-network-key".equals(field(original, "mNetworkKey").get(original)))
            throw new AssertionError("Live source identity was mutated");

        NetworkCapabilities source = new NetworkCapabilities();
        field(source, "mTransportTypes").setLong(source, 1L << NetworkCapabilities.TRANSPORT_WIFI);
        Field capabilities = field(source, "mNetworkCapabilities");
        capabilities.setLong(source, capabilities.getLong(source)
                | (1L << NetworkCapabilities.NET_CAPABILITY_INTERNET) | (1L << NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        field(source, "mTransportInfo").set(source, original); field(source, "mSSID").set(source, "Synthetic-Source");
        NetworkCapabilities replaced = WifiIdentity.copy(source, p);
        if (!(replaced.getTransportInfo() instanceof WifiInfo) || replaced.toString().contains("Synthetic-Source")
                || !replaced.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                || !replaced.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            throw new AssertionError("Network snapshot identity or capabilities mismatch");
        if (source.getTransportInfo() != original || !"Synthetic-Source".equals(field(source, "mSSID").get(source)))
            throw new AssertionError("Network controller source was mutated");
        NetworkCapabilities fallback = WifiIdentity.withoutIdentity(source);
        if (fallback.getTransportInfo() != null || field(fallback, "mSSID").get(fallback) != null
                || !fallback.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !fallback.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            throw new AssertionError("Identity fallback altered network availability");

        WifiInfo redacted = original.makeCopy(-1L), protectedCopy = WifiIdentity.copy(redacted, p);
        if (!java.util.Objects.equals(redacted.getSSID(), protectedCopy.getSSID())
                || !java.util.Objects.equals(redacted.getBSSID(), protectedCopy.getBSSID())
                || !java.util.Objects.equals(redacted.getMacAddress(), protectedCopy.getMacAddress())
                || protectedCopy.getNetworkId() != redacted.getNetworkId())
            throw new AssertionError("Android permission redactions were replaced with identities");

        Parcel parcel = Parcel.obtain();
        try {
            virtual.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            WifiInfo decoded = (WifiInfo) ((android.os.Parcelable.Creator<?>) WifiInfo.class.getField("CREATOR").get(null)).createFromParcel(parcel);
            if (decoded.toString().contains("Synthetic-Source") || !p.bssid.equals(decoded.getBSSID()))
                throw new AssertionError("Wi-Fi parcel restored source identity");
            parcel.setDataSize(0); parcel.setDataPosition(0); replaced.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            NetworkCapabilities decodedCaps = NetworkCapabilities.CREATOR.createFromParcel(parcel);
            if (decodedCaps.toString().contains("Synthetic-Source") || !decodedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                throw new AssertionError("Network capabilities parcel restored source identity");
        } finally { parcel.recycle(); }
        return "WIFI_IDENTITY_CONTRACT_PASSED: WifiInfo + NetworkCapabilities parcels; network key removal, source isolation, permission sentinels and validation preserved";
    }
    private static Field field(Object source, String name) throws ReflectiveOperationException {
        Field field = source.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
}
