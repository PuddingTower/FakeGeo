package io.github.fakegeo.hook;

import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.util.ArrayList;
import io.github.fakegeo.config.Profile;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class WifiHooks {
    private final RuntimeConfig config;
    private final ThreadLocal<Boolean> original = ThreadLocal.withInitial(() -> false);
    WifiHooks(RuntimeConfig config) { this.config = config; }
    private boolean active() { Profile p = config.get(); return p.enabled && p.wifi; }
    /** The "attendance Wi-Fi is connected" story, even while the radio is on 5G. */
    private boolean connected() { Profile p = config.get(); return p.enabled && p.wifi && p.wifiConnected; }
    void install() {
        after("getSSID", p -> quotedSsid(p.primaryWifi.ssid));
        after("getBSSID", p -> p.primaryWifi.bssid); after("getMacAddress", p -> p.wifiMac);
        after("getRssi", p -> WifiScanFactory.signal(p.primaryWifi)); after("getFrequency", p -> p.primaryWifi.frequency);
        after("getIpAddress", p -> 0x0200000A); // 10.0.0.2 in Android's little-endian representation.
        after("getNetworkId", p -> 1);
        after("getPasspointFqdn", p -> null); after("getPasspointProviderFriendlyName", p -> null);
        after("getInformationElements", p -> java.util.Collections.emptyList());
        after("getApMldMacAddress", p -> null); after("getAffiliatedMloLinks", p -> java.util.Collections.emptyList());
        after("getApMloLinkId", p -> -1);
        Hooks.all(WifiInfo.class, "getWifiSsid", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (active() && !original.get() && !call.hasThrowable()) call.setResult(wifiSsid(config.get()));
            }
        });
        // Sanitize the object itself so transport info, toString and parceling use virtual values.
        Hooks.all(WifiManager.class, "getConnectionInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (active() && !call.hasThrowable() && call.getResult() instanceof WifiInfo)
                    call.setResult(clean((WifiInfo) call.getResult(), config.get()));
            }
        });
        Hooks.all(NetworkCapabilities.class, "getTransportInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable()) return;
                Object value = call.getResult();
                try {
                    if (value instanceof WifiInfo) call.setResult(clean((WifiInfo) value, config.get()));
                    else if (connected()) call.setResult(clean((WifiInfo) XposedHelpers.newInstance(WifiInfo.class), config.get()));
                } catch (Throwable unavailable) { /* Never break the caller over a missing snapshot. */ }
            }
        });
        for (String name : new String[]{"writeToParcel", "toString"}) Hooks.all(WifiInfo.class, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) throws Throwable {
                if (!active() || original.get()) return;
                original.set(true);
                try { call.setResult(XposedBridge.invokeOriginalMethod(call.method, clean((WifiInfo) call.thisObject, config.get()), call.args)); }
                finally { original.set(false); }
            }
        });
        Hooks.all(WifiManager.class, "getScanResults", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable()) return;
                call.setResult(new ArrayList<>(WifiScanFactory.results(config.get())));
            }
        });
        Hooks.all(WifiManager.class, "getConfiguredNetworks", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (active() && !call.hasThrowable()) call.setResult(new ArrayList<>());
            }
        });
        Hooks.all(WifiManager.class, "getDhcpInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable() || call.getResult() == null) return;
                DhcpInfo d = new DhcpInfo(); d.ipAddress = 0x0200000A; d.gateway = 0x0100000A;
                d.netmask = 0x00FFFFFF; d.dns1 = 0x08080808; d.serverAddress = d.gateway; d.leaseDuration = 3600;
                call.setResult(d);
            }
        });
        transport();
    }
    /**
     * Wi-Fi attendance checks ask the connectivity layer which transport carries them. Rewriting the
     * transport answers, rather than the objects behind them, avoids touching the platform's
     * 64-bit capability sets whose layout changed after Android 12.
     */
    private void transport() {
        Hooks.all(NetworkCapabilities.class, "hasTransport", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!connected() || call.hasThrowable() || call.args.length == 0 || !(call.args[0] instanceof Integer)) return;
                int transport = (Integer) call.args[0];
                if (transport == NetworkCapabilities.TRANSPORT_WIFI) call.setResult(true);
                else if (transport == NetworkCapabilities.TRANSPORT_CELLULAR) call.setResult(false);
            }
        });
        for (String name : new String[]{"getActiveNetworkInfo", "getNetworkInfo"})
            Hooks.all(ConnectivityManager.class, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (!connected() || call.hasThrowable() || !(call.getResult() instanceof android.net.NetworkInfo)) return;
                    android.net.NetworkInfo source = (android.net.NetworkInfo) call.getResult();
                    if (source.getType() == ConnectivityManager.TYPE_WIFI) return;
                    call.setResult(connectedWifi());
                }
            });
        Hooks.all(ConnectivityManager.class, "isActiveNetworkMetered", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) { if (connected()) call.setResult(false); }
        });
        Hooks.all(android.net.LinkProperties.class, "getInterfaceName", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (connected() && !call.hasThrowable()) call.setResult("wlan0");
            }
        });
    }
    private static android.net.NetworkInfo connectedWifi() {
        android.net.NetworkInfo info = new android.net.NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");
        info.setDetailedState(android.net.NetworkInfo.DetailedState.CONNECTED, null, null);
        return info;
    }
    private interface Value { Object get(Profile p); }
    private void after(String name, Value value) {
        Hooks.all(WifiInfo.class, name, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (active() && !original.get() && !call.hasThrowable()) call.setResult(value.get(config.get()));
            }
        });
    }
    private static Object wifiSsid(Profile p) { return WifiScanFactory.wifiSsid(p.primaryWifi.ssid); }
    private static String quotedSsid(String value) { return WifiScanFactory.quotedSsid(value); }
    private static WifiInfo clean(WifiInfo source, Profile p) {
        WifiInfo result = (WifiInfo) XposedHelpers.newInstance(WifiInfo.class, source);
        field(result, "mWifiSsid", wifiSsid(p)); field(result, "mBSSID", p.primaryWifi.bssid); field(result, "mMacAddress", p.wifiMac);
        field(result, "mRssi", WifiScanFactory.signal(p.primaryWifi)); field(result, "mFrequency", p.primaryWifi.frequency); field(result, "mNetworkId", 1);
        try { field(result, "mIpAddress", java.net.InetAddress.getByAddress(new byte[]{10, 0, 0, 2})); }
        catch (java.net.UnknownHostException impossible) { throw new AssertionError(impossible); }
        field(result, "mFqdn", null); field(result, "mProviderFriendlyName", null);
        field(result, "mInformationElements", null); field(result, "mApMldMacAddress", null);
        field(result, "mAffiliatedMloLinks", new ArrayList<>());
        return result;
    }
    private static void field(Object object, String name, Object value) {
        try { XposedHelpers.setObjectField(object, name, value); }
        catch (Throwable absentOnVersion) { /* Optional fields vary across Android releases. */ }
    }
}
