package io.github.fakegeo.hook;

import io.github.fakegeo.config.Profile;
import io.github.fakegeo.hook.system.GnssPayload;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * AMap location SDK cleanup for Feishu, DingTalk and WeCom builds that embed it.
 * AMapLocation overrides the Location getters, so the generic Location hooks never see it.
 * Coordinates are deliberately not re-projected: the SDK already converts a GPS fix to GCJ-02,
 * and applying {@link Gcj02} again would introduce the 300-500 m offset it is meant to remove.
 */
final class LarkLocationHooks {
    private final RuntimeConfig config;
    private final ClassLoader loader;
    LarkLocationHooks(RuntimeConfig config, ClassLoader loader) { this.config = config; this.loader = loader; }
    private boolean active() { Profile p = config.get(); return p.enabled && p.location && p.antiDetect; }
    void install() {
        Class<?> fix = XposedHelpers.findClassIfExists("com.amap.api.location.AMapLocation", loader);
        if (fix == null) return;
        int success = constant(fix, "LOCATION_SUCCESS", 0);
        int simulation = constant(fix, "ERROR_CODE_FAILURE_SIMULATION_LOCATION", 15);
        int trustedHigh = constant(fix, "TRUSTED_LEVEL_HIGH", 1);
        value(fix, "isMock", () -> false);
        value(fix, "getTrustedLevel", () -> trustedHigh);
        value(fix, "getSatellites", () -> GnssPayload.current().count);
        Hooks.all(fix, "getErrorCode", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!active() || call.hasThrowable() || !(call.getResult() instanceof Integer)) return;
                // Only the simulation verdict is overridden; genuine failures stay visible.
                if (((Integer) call.getResult()) == simulation) call.setResult(success);
            }
        });
        Class<?> report = XposedHelpers.findClassIfExists("com.amap.api.location.AMapLocationQualityReport", loader);
        if (report != null) {
            value(report, "isInstalledHighDangerMockApp", () -> false);
            value(report, "getGPSSatellites", () -> GnssPayload.current().count);
            value(report, "getGPSStatus", () -> constant(report, "GPS_STATUS_OK", 0));
            value(report, "getAdviseMessage", () -> "");
        }
        XposedBridge.log("FakeGeo: AMap cleanup installed for " + config.context.getPackageName());
    }
    private void value(Class<?> type, String name, java.util.function.Supplier<Object> result) {
        Hooks.all(type, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                if (active()) call.setResult(result.get());
            }
        });
    }
    private static int constant(Class<?> type, String name, int fallback) {
        try { return XposedHelpers.getStaticIntField(type, name); }
        catch (Throwable absent) { return fallback; }
    }
}
