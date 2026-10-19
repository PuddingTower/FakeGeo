package io.github.fakegeo.root;

import io.github.fakegeo.config.ModuleVersion;
import org.json.JSONObject;

/** Updating the APK does not replace code already loaded in system_server. */
public final class RuntimeGate {
    private RuntimeGate() {}
    public static void requireCurrent(JSONObject status, long now) {
        String version = status.optString("moduleVersion");
        if (!ModuleVersion.CURRENT.equals(version) && !ModuleVersion.SYSTEM_BASELINE.equals(version))
            throw new IllegalStateException("系统进程仍加载旧版模块。请保持禁用并重启手机，加载 " + ModuleVersion.CURRENT + " 后再启用。");
        long reported = status.optLong("reportedAt");
        if (reported <= 0 || now < reported || now - reported > 15000)
            throw new IllegalStateException("系统状态已过期，请等待新的系统 Hook 上报后再启用。");
        if (!status.optString("configError").isEmpty())
            throw new IllegalStateException("系统配置读取异常，请恢复配置同步后再启用。");
    }
    public static void requireWifiConnectivity(JSONObject status) {
        if (!status.optBoolean("wifiConnectivityProtected"))
            throw new IllegalStateException("系统 Wi-Fi 网络能力保护尚未加载。请保持禁用并重启手机，检查新的系统 Hook 上报。");
    }
    public static void requireRealGps(JSONObject status) {
        if(!status.optBoolean("realGpsCaptureSupported"))
            throw new IllegalStateException("真实位置入口需要新版系统 Hook，请重启手机后重试。");
    }
}
