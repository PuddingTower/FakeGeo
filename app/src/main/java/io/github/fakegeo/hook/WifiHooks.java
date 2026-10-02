package io.github.fakegeo.hook;

import android.net.DhcpInfo;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
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
    void install() {
        after("getSSID", p -> "\"" + p.ssid.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        after("getBSSID", p -> p.bssid); after("getMacAddress", p -> p.wifiMac);
        after("getRssi", p -> -55); after("getFrequency", p -> 2412);
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
                if (active() && !call.hasThrowable() && call.getResult() instanceof WifiInfo)
                    call.setResult(clean((WifiInfo) call.getResult(), config.get()));
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
                Profile p = config.get();
                // The default constructor is hidden on API 29, public from API 30.
                ScanResult result = (ScanResult) XposedHelpers.newInstance(ScanResult.class);
                result.SSID = p.ssid; result.BSSID = p.bssid;
                result.capabilities = "[WPA2-PSK-CCMP][ESS]"; result.level = -55;
                result.frequency = 2412; result.timestamp = SystemClock.elapsedRealtimeNanos() / 1000;
                try { XposedHelpers.setObjectField(result, "wifiSsid", wifiSsid(p)); } catch (Throwable ignored) {}
                ArrayList<ScanResult> virtual = new ArrayList<>(); virtual.add(result); call.setResult(virtual);
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
    }
    private interface Value { Object get(Profile p); }
    private void after(String name, Value value) {
        Hooks.all(WifiInfo.class, name, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (active() && !original.get() && !call.hasThrowable()) call.setResult(value.get(config.get()));
            }
        });
    }
    private static Object wifiSsid(Profile p) {
        Class<?> type = XposedHelpers.findClass("android.net.wifi.WifiSsid", null);
        byte[] bytes = p.ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { return XposedHelpers.callStaticMethod(type, "fromBytes", bytes); }
        catch (Throwable oldApi) { return XposedHelpers.callStaticMethod(type, "createFromByteArray", bytes); }
    }
    private static WifiInfo clean(WifiInfo source, Profile p) {
        WifiInfo result = (WifiInfo) XposedHelpers.newInstance(WifiInfo.class, source);
        field(result, "mWifiSsid", wifiSsid(p)); field(result, "mBSSID", p.bssid); field(result, "mMacAddress", p.wifiMac);
        field(result, "mRssi", -55); field(result, "mFrequency", 2412); field(result, "mNetworkId", 1);
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
