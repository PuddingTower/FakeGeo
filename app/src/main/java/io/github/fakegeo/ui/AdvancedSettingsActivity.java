package io.github.fakegeo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import org.json.JSONObject;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AdvancedSettingsActivity extends Activity {
    private LinearLayout content;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Map<String, CheckBox> flags = new LinkedHashMap<>();
    private final ArrayList<View> compatibilityViews = new ArrayList<>();
    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor();
    private TextView updateStatus;
    private Button updateButton, clearCacheButton;
    private final ExecutorService rootWorker = Executors.newSingleThreadExecutor();
    private TextView rootStatus, cacheSelection;
    private String cacheTarget = "";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Profile p = ConfigStore.load(this);
        cacheTarget = state == null ? getSharedPreferences("cache_ui", MODE_PRIVATE).getString("target", p.locationCacheTarget) : state.getString("cacheTarget", p.locationCacheTarget);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(32)); scroll.addView(content);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(UiKit.BACKGROUND);
        page.addView(UiKit.toolbar(this,"高级设置",this::finish)); page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(page);
        UiKit.insetPage(page);
        section("地图服务");
        button("高德地图与搜索设置", () -> startActivity(new Intent(this, MapSettingsActivity.class)));
        section("系统与授权");
        text(MainActivity.moduleActive() ? "LSPosed 已注入模块界面" : "请在 LSPosed 启用模块；界面未被注入不代表目标 App 未生效。", 14, UiKit.MUTED);
        text("Android 10–16 · 系统服务 Hook\n在 LSPosed 中选择 Android 系统／系统框架、电话服务、蓝牙与 Wi-Fi 服务；一加／ColorOS 还需勾选 Oplus 定位与高通定位服务（列表里没有就是本机没有该组件）。首次启用或改动作用域后重启手机。\n蓝牙信标注入、高德结果清洗与调用栈隐藏只在应用兼容模式下生效：需要把目标应用同时加入作用域，并关闭系统模式。", 14, Color.DKGRAY);
        rootStatus = new TextView(this); rootStatus.setText("Root 与系统 Hook 状态：尚未检测"); content.addView(rootStatus);
        button("请求 KernelSU / Root 授权", () -> rootTask("请求 Root 授权…", () -> { RootBridge.authorize(); return "已取得 Root UID 0。请继续在 LSPosed 启用系统作用域。"; }));
        button("查看 LSPosed 系统作用域", this::scopeInstructions);
        button("检测系统 Hook 状态", () -> rootTask("读取系统状态…", this::checkStatus));
        section("虚拟环境");
        flag("enabled", "启用虚拟环境", p.enabled);
        flag("systemMode", "系统模式（统一修改系统分发的数据）", p.systemMode);
        flag("location", "替换定位（含持续回调）", p.location);
        flag("wifi", "替换 Wi-Fi 信息与扫描结果", p.wifi);
        flag("bluetooth", "虚拟蓝牙身份，屏蔽附近设备扫描", p.bluetooth);
        flag("cell", "隐藏基站定位身份", p.cell);
        flag("gnss", "系统 GNSS 状态适配（合成状态与 GPS 附加信息）", p.gnss);
        section("定位缓存");
        cacheSelection = UiKit.text(this,"",14,UiKit.INK,false); content.addView(cacheSelection);
        refreshCacheSelection();
        button("选择应用", this::chooseCacheApp);
        clearCacheButton = UiKit.button(this, "清除所选应用的定位缓存", false);
        LinearLayout.LayoutParams clearLayout = new LinearLayout.LayoutParams(-1, dp(50)); clearLayout.topMargin=dp(8); content.addView(clearCacheButton, clearLayout);
        clearCacheButton.setOnClickListener(v -> clearSelectedCache());
        text("选择应用后，点击按钮才执行清理。操作会关闭所选应用，先备份再移走支持的定位缓存，随后请重新打开应用。保存配置或启用定位不会自动清缓存。", 13, Color.DKGRAY);
        text("支持已确认的高德定位库和百度 SDK 内部离线定位库；未找到会明确提示。仅限主用户的非系统应用。", 13, UiKit.MUTED);
        button("恢复最近一次备份的定位缓存", () -> rootTask("正在恢复定位缓存…", () -> {
            String result = io.github.fakegeo.root.LocationCacheManager.restoreLatest(this);
            return result.contains("LOCATION_CACHE_ALREADY_RESTORED") ? "最近的定位缓存备份已恢复过。" : "定位缓存已恢复，请重新打开备份对应的应用。";
        }));
        text("恢复前需停止虚拟环境；恢复会关闭备份对应的应用，旧缓存可能带回旧位置。", 13, UiKit.MUTED);
        button("清理所选应用的第三方定位缓存（含应用分身）", this::clearVendorCache);
        text("仅移动白名单中的高德定位缓存（shared_prefs/amap*、files/amap、cache/location 等），不触碰登录态与聊天记录；会先关闭该应用，可恢复。没有匹配文件时明确提示，不会误删。", 13, UiKit.MUTED);
        button("恢复最近一次高德定位缓存备份", () -> rootTask("正在恢复高德定位缓存…", () -> {
            String result = io.github.fakegeo.root.LocationCacheManager.restoreVendorCaches(this);
            return result.contains("NO_VENDOR_BACKUP") ? "没有高德定位缓存备份需要恢复。"
                    : result.contains("ALREADY_RESTORED") ? "该备份已经恢复过了。"
                    : result.contains("NOT_FOUND") ? "没有发现可清理的第三方定位缓存。" : "第三方定位缓存已恢复，请重新打开该应用。";
        }));
        section("位置参数 · WGS84");
        button("悬浮摇杆 · 走路／跑步／骑车", () -> startActivity(new Intent(this, MovementSettingsActivity.class)));
        flag("jitter", "模拟小幅卫星定位漂移", p.jitter);
        field("jitterMeters", "定位漂移半径（米，0–30）", String.valueOf(p.jitterMeters), true);
        text("默认在选点附近 2 米内平滑漂移，精度与海拔小幅变化。漂移是虚拟坐标变化，不改变真实卫星信号。移动时各系统定位回调共享速度与方向。",13,UiKit.MUTED);
        field("latitude", "纬度（-90 到 90）", String.valueOf(p.latitude), true);
        field("longitude", "经度（-180 到 180）", String.valueOf(p.longitude), true);
        field("altitude", "海拔（米）", String.valueOf(p.altitude), true);
        field("accuracy", "精度（米）", String.valueOf(p.accuracy), true);
        field("interval", "更新间隔（毫秒，至少 500）", String.valueOf(p.interval), true);
        section("无线信息");
        field("ssid", "虚拟 Wi-Fi SSID", p.ssid, false);
        field("bssid", "虚拟 Wi-Fi BSSID", p.bssid, false);
        field("wifiMac", "虚拟 Wi-Fi MAC", p.wifiMac, false);
        field("bluetoothMac", "虚拟蓝牙 MAC", p.bluetoothMac, false);
        field("bluetoothName", "虚拟蓝牙名称", p.bluetoothName, false);
        flag("wifiConnected", "移动数据时也报告已连接该 Wi-Fi", p.wifiConnected);
        flag("antiDetect", "隐藏模拟定位开关、AppOps 与模块调用栈", p.antiDetect);
        flag("keepAlive", "ColorOS 冻结豁免（仅豁免本模块进程）", p.keepAlive);
        section("蓝牙考勤信标");
        text("填写考勤机 MAC 后二选一：粘贴抓包得到的完整广播十六进制，或填写 iBeacon UUID 与 Major／Minor。留空时维持原来的“屏蔽蓝牙扫描”。", 13, UiKit.MUTED);
        field("beaconMac", "考勤机 MAC（留空表示不注入）", p.beaconMac, false);
        field("beaconRecord", "广播数据十六进制（推荐，最长 31 字节）", p.beaconRecord, false);
        field("beaconUuid", "iBeacon UUID（32 位十六进制）", p.beaconUuid, false);
        field("beaconMajor", "Major（0–65535）", String.valueOf(p.beaconMajor), true);
        field("beaconMinor", "Minor（0–65535）", String.valueOf(p.beaconMinor), true);
        field("beaconName", "信标名称（仅原始广播里包含时才有效）", p.beaconName, false);
        section("应用兼容模式");
        field("targets", "应用兼容模式的包名（系统模式无需填写）", String.join("\n", p.targets), false);
        compatibilityViews.add(content.getChildAt(content.getChildCount() - 2));
        compatibilityViews.add(fields.get("targets"));
        fields.get("targets").setSingleLine(false); fields.get("targets").setMinLines(3);
        button("从已安装应用选择", this::chooseApps);
        compatibilityViews.add(content.getChildAt(content.getChildCount() - 1));
        button("添加测试探针", () -> {
            String old = fields.get("targets").getText().toString();
            if (!old.contains("io.github.fakegeo.probe")) fields.get("targets").setText(old.trim() + "\nio.github.fakegeo.probe");
        });
        compatibilityViews.add(content.getChildAt(content.getChildCount() - 1));
        button("保存并应用配置", this::save);
        button("停止虚拟环境", this::stop);
        text("系统模式通过系统服务修改分发的数据，请保持手机系统定位开关开启。蓝牙扫描与基站地理身份隔离会影响附近设备功能。地图坐标为 WGS84；Wi-Fi／蓝牙／基站 Hook 不改变物理基带、网卡和公网 IP。", 13, Color.DKGRAY);
        section("软件更新");
        updateStatus = UiKit.text(this, "当前版本 " + io.github.fakegeo.config.ModuleVersion.CURRENT, 14, UiKit.MUTED, false); content.addView(updateStatus);
        updateButton = UiKit.button(this, "检查更新", false); LinearLayout.LayoutParams updateLayout = new LinearLayout.LayoutParams(-1, dp(50)); updateLayout.topMargin=dp(8); content.addView(updateButton, updateLayout);
        updateButton.setOnClickListener(v -> checkUpdate());
        section("关于");
        text("FakeGeo · 免费开源\n作者：" + AppInfo.AUTHOR + "\n版本：" + io.github.fakegeo.config.ModuleVersion.CURRENT, 14, UiKit.INK);
        TextView source = UiKit.text(this, AppInfo.REPOSITORY, 14, UiKit.ACCENT, false); source.setTextIsSelectable(true); source.setPadding(0,dp(12),0,dp(12)); source.setOnClickListener(v -> openLink(AppInfo.REPOSITORY)); content.addView(source);
        button("查看源代码", () -> openLink(AppInfo.REPOSITORY));
        button("使用说明与免责声明", () -> NoticeDialog.show(this, false, () -> {}));
        button("重新查看初始引导", () -> startActivity(new Intent(this, OnboardingActivity.class)));
        if (state != null) {
            for (Map.Entry<String, EditText> entry : fields.entrySet()) {
                String value = state.getString("field_" + entry.getKey());
                if (value != null) entry.getValue().setText(value);
            }
            for (Map.Entry<String, CheckBox> entry : flags.entrySet())
                entry.getValue().setChecked(state.getBoolean("flag_" + entry.getKey(), entry.getValue().isChecked()));
        }
        flags.get("systemMode").setOnCheckedChangeListener((box, checked) -> showCompatibility(checked));
        showCompatibility(flags.get("systemMode").isChecked());
    }
    private void showCompatibility(boolean system) {
        for (View view : compatibilityViews) view.setVisibility(system ? View.GONE : View.VISIBLE);
    }
    private void save() {
        try {
            JSONObject json = ConfigStore.load(this).json();
            for (Map.Entry<String, EditText> entry : fields.entrySet()) json.put(entry.getKey(), entry.getValue().getText().toString().trim());
            for (Map.Entry<String, CheckBox> entry : flags.entrySet()) json.put(entry.getKey(), entry.getValue().isChecked());
            json.put("locationCacheTarget", cacheTarget);
            json.put("revision", nextRevision());
            Profile p = new Profile(json);
            for (String target : p.targets) {
                try { getPackageManager().getApplicationInfo(target, 0); }
                catch (android.content.pm.PackageManager.NameNotFoundException absent) { throw new IllegalArgumentException("请先安装目标应用：" + target); }
            }
            publish(p);
        } catch (Exception e) { new AlertDialog.Builder(this).setTitle("配置未保存").setMessage(e.getMessage()).setPositiveButton("确定", null).show(); }
    }
    private void publish(Profile profile) {
        // Always publish the disabled/non-system state too, so a previous system profile is retired.
        rootTask("正在应用配置…", () -> {
            io.github.fakegeo.root.RootProfilePublisher.publishUser(this, profile);
            stopService(new Intent(this, io.github.fakegeo.motion.JoystickService.class));
            return "系统配置已发布，等待 Hook 读取。点击检测系统 Hook 状态确认；首次设置作用域后需要重启手机。";
        });
    }
    private void stop() {
        try {
            JSONObject json = ConfigStore.load(this).json(); json.put("enabled", false); json.put("revision", nextRevision());
            Profile disabled = new Profile(json); flags.get("enabled").setChecked(false); publish(disabled);
        } catch (Exception failure) { new AlertDialog.Builder(this).setTitle("停止失败").setMessage(failure.getMessage()).setPositiveButton("确定", null).show(); }
    }
    private long nextRevision() { return Math.max(System.currentTimeMillis(), ConfigStore.load(this).revision + 1); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("cacheTarget", cacheTarget);
        for (Map.Entry<String, EditText> entry : fields.entrySet()) state.putString("field_" + entry.getKey(), entry.getValue().getText().toString());
        for (Map.Entry<String, CheckBox> entry : flags.entrySet()) state.putBoolean("flag_" + entry.getKey(), entry.getValue().isChecked());
        super.onSaveInstanceState(state);
    }
    private void scopeInstructions() {
        Intent manager = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        new AlertDialog.Builder(this).setTitle("LSPosed 系统作用域")
                .setMessage("启用 FakeGeo 后勾选：\n• Android 系统／系统框架（android）\n• 电话服务（com.android.phone）\n• 蓝牙（com.android.bluetooth）\n• Wi-Fi 服务（若列表存在）\n• ColorOS：com.oplus.location、com.oplus.locationproxy、com.oplus.nhs、com.oplus.athena（若列表存在）\n• 高通：com.qualcomm.location（若列表存在）\n\n首次启用或改变系统作用域后重启手机。KernelSU Root 授权不能代替 LSPosed 作用域授权。")
                .setPositiveButton(manager == null ? "知道了" : "打开 LSPosed", (dialog, which) -> { if (manager != null) startActivity(manager); }).show();
    }
    private interface RootAction { String run() throws Exception; }
    private void rootTask(String pending, RootAction action) {
        rootStatus.setText(pending);
        rootWorker.execute(() -> {
            String result;
            try { result = action.run(); }
            catch (Exception failure) { result = "操作未完成：" + failure.getMessage() + "\n若系统配置同步失败，系统仍可能使用上次发布的配置。"; }
            String message = result; runOnUiThread(() -> { if (!isDestroyed()) rootStatus.setText(message); });
        });
    }
    private String describeStatus(String output) throws Exception {
        if (output.equals("SYSTEM_STATUS_MISSING_NO_PROFILE"))
            return "状态目录已准备，但配置尚未发布成功。请先保存并应用；不能据此判断系统 Hook 没有加载。" + describeServices();
        if (output.equals("SYSTEM_STATUS_MISSING_WITH_PROFILE"))
            return "配置文件存在，但系统状态仍未上报。请检查 LSPosed 中 FakeGeo 的 system_server 加载日志。" + describeServices();
        if (!output.startsWith("{")) return "系统状态输出异常：" + output + describeServices();
        JSONObject status = new JSONObject(output);
        long age = System.currentTimeMillis() - status.optLong("reportedAt");
        boolean stale = age > 15000 || age < 0;
        boolean current = status.optLong("revision") == ConfigStore.load(this).revision;
        return (stale ? "系统状态已过期，不能确认 Hook 正在运行。" : "系统进程已上报 Hook 状态。")
                + "\n配置版本：" + (current ? "已同步" : "未同步") + " · 启用：" + status.optBoolean("enabled")
                + "\n系统模块版本：" + status.optString("moduleVersion", "旧版（重启加载新模块）")
                + "\nProvider：" + status.optJSONArray("providers") + "\n累计合成报告：" + status.optLong("reportCount")
                + "\nGNSS 状态服务：" + (status.optBoolean("gnssCaptured") ? "已捕获" : "未捕获")
                + " · 状态报告：" + status.optLong("gnssReportCount")
                + "\n缓存／回调替换：" + status.optLong("cacheReadReplacements") + "／" + status.optLong("deliveryReplacements")
                + " · 拦截：" + status.optLong("deliveryDrops")
                + "\nWi-Fi 网络能力保护：" + (status.optBoolean("wifiConnectivityProtected") ? "已加载" : "未加载")
                + " · 替换：" + status.optLong("wifiConnectivityReplacements") + " · 降级：" + status.optLong("wifiConnectivityFallbacks")
                + "\n配置桥接：应答 " + status.optLong("configBridgeServed") + " · 拒绝 " + status.optLong("configBridgeDenied")
                + "\n厂商定位服务：捕获 " + status.optLong("vendorServicesCaptured") + " · 替换 " + status.optLong("vendorLocationReplacements")
                + "\nColorOS 冻结豁免：" + status.optLong("keepAliveFreezesSkipped")
                + "\n已安装 Hook：" + status.optJSONArray("hooks")
                + "\n配置错误：" + status.optString("configError") + "\n接口错误：" + status.optJSONArray("errors")
                + describeServices();
    }
    private String checkStatus() throws Exception {
        String output = RootBridge.execute(RootCommands.status());
        // Checking status also prepares its directory, independently from configuration publishing.
        for (int attempt = 0; attempt < 3 && output.startsWith("SYSTEM_STATUS_MISSING_"); attempt++) {
            Thread.sleep(1000); output = RootBridge.execute(RootCommands.status());
        }
        return describeStatus(output);
    }
    private String describeServices() {
        StringBuilder text = new StringBuilder();
        JSONObject statuses = ConfigStore.serviceStatuses(this);
        for (String name : io.github.fakegeo.config.ServicePackages.NAMES) {
            try { getPackageManager().getApplicationInfo(name, 0); }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) { continue; }
            JSONObject state = statuses.optJSONObject(name);
            long age = state == null ? Long.MAX_VALUE : System.currentTimeMillis() - state.optLong("reportedAt");
            text.append("\n").append(name).append(": ");
            if (age < 0 || age > 15000) { text.append("尚未上报／状态过期，请检查作用域"); continue; }
            boolean current = state.optLong("revision") == ConfigStore.load(this).revision;
            org.json.JSONArray hooks = state.optJSONArray("hooks");
            text.append(current ? "配置已同步" : "配置未同步").append(" · Hook 数量 ").append(hooks == null ? 0 : hooks.length());
            text.append("\n  配置错误：").append(state.optString("configError")).append(" · 接口错误：").append(state.optJSONArray("errors"));
        }
        return text.toString();
    }
    @Override public void onDestroy() { rootWorker.shutdown(); updateWorker.shutdown(); super.onDestroy(); }
    private void rememberCacheSelection() { getSharedPreferences("cache_ui", MODE_PRIVATE).edit().putString("target", cacheTarget).apply(); }
    private void clearSelectedCache() {
        if (cacheTarget.isEmpty()) { Toast.makeText(this,"请先选择应用",Toast.LENGTH_SHORT).show(); return; }
        String target = cacheTarget; clearCacheButton.setEnabled(false);
        rootStatus.setText("正在备份并清理所选应用的定位缓存…");
        rootWorker.execute(() -> {
            String message;
            try {
                String result = io.github.fakegeo.root.LocationCacheManager.clear(this, target);
                message = result.contains("LOCATION_CACHE_NOT_FOUND") ? "未发现支持的定位缓存，未清理文件。" : "定位缓存已备份并清除，请重新打开所选应用。";
            } catch (Exception failure) { message = "清理未完成：" + failure.getMessage(); }
            String outcome = message;
            runOnUiThread(() -> { if (!isDestroyed()) { clearCacheButton.setEnabled(true); rootStatus.setText(outcome); Toast.makeText(this, outcome, Toast.LENGTH_LONG).show(); } });
        });
    }
    private void clearVendorCache() {
        if (cacheTarget.isEmpty()) { Toast.makeText(this,"请先选择应用",Toast.LENGTH_SHORT).show(); return; }
        String target = cacheTarget;
        rootStatus.setText("正在备份并清理所选应用的第三方定位缓存…");
        rootWorker.execute(() -> {
            String message;
            try {
                String result = io.github.fakegeo.root.LocationCacheManager.clearVendorCaches(this, target);
                message = result.contains("VENDOR_LOCATION_CACHE_NOT_FOUND")
                        ? "未发现白名单内的第三方定位缓存，未移动任何文件。" : "第三方定位缓存已备份并清除，请重新打开所选应用。";
            } catch (Exception failure) { message = "清理未完成：" + failure.getMessage(); }
            String outcome = message;
            runOnUiThread(() -> { if (!isDestroyed()) { rootStatus.setText(outcome); Toast.makeText(this, outcome, Toast.LENGTH_LONG).show(); } });
        });
    }
    private void checkUpdate() {
        updateButton.setEnabled(false); updateStatus.setText("正在查询 GitHub Release…");
        updateWorker.execute(() -> {
            io.github.fakegeo.update.GitHubRelease release = null; String error = null;
            try { release = io.github.fakegeo.update.GitHubUpdateClient.latest(); }
            catch (Exception failure) { error = failure.getMessage(); }
            io.github.fakegeo.update.GitHubRelease result = release; String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return; updateButton.setEnabled(true);
                if (result == null) { updateStatus.setText("检查失败：" + (failure == null ? "请检查网络后重试" : failure)); return; }
                String current = io.github.fakegeo.config.ModuleVersion.CURRENT;
                if (!result.newerThan(current)) { updateStatus.setText("当前版本 " + current + "，暂无更新。GitHub 正式版：" + result.tag); return; }
                updateStatus.setText("发现新版本 " + result.tag);
                ScrollView scroll = new ScrollView(this); TextView notes=UiKit.text(this,"当前版本：" + current + "\n最新版本：" + result.tag + "\n\n" + (result.notes.isEmpty()?"前往 GitHub Release 查看更新详情。":result.notes),14,UiKit.INK,false); notes.setPadding(dp(24),dp(12),dp(24),dp(12)); scroll.addView(notes);
                new AlertDialog.Builder(this).setTitle("软件版本更新").setView(scroll)
                        .setPositiveButton("前往下载",(dialog,which)->openLink(result.url)).setNegativeButton("稍后",null).show();
            });
        });
    }
    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException absent) { Toast.makeText(this,"未安装浏览器",Toast.LENGTH_SHORT).show(); }
    }
    private void refreshCacheSelection() {
        String label = "尚未选择应用";
        if (!cacheTarget.isEmpty()) {
            try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(cacheTarget, 0)).toString(); }
            catch (android.content.pm.PackageManager.NameNotFoundException absent) { label = "应用已卸载"; }
            label += "\n" + cacheTarget;
        }
        cacheSelection.setText(label);
    }
    private void chooseCacheApp() {
        ArrayList<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo app : getPackageManager().getInstalledApplications(0)) {
            if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            try { io.github.fakegeo.config.Validation.cachePackage(app.packageName); apps.add(app); }
            catch (IllegalArgumentException protectedApp) { /* Exclude protected components and this module. */ }
        }
        apps.sort(Comparator.comparing(a -> getPackageManager().getApplicationLabel(a).toString()));
        CharSequence[] labels = new CharSequence[apps.size()]; int selected = -1;
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = getPackageManager().getApplicationLabel(apps.get(i)) + "\n" + apps.get(i).packageName;
            if (apps.get(i).packageName.equals(cacheTarget)) selected = i;
        }
        new AlertDialog.Builder(this).setTitle("选择定位缓存应用")
                .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    cacheTarget = apps.get(index).packageName; rememberCacheSelection(); refreshCacheSelection(); dialog.dismiss();
                })
                .setNeutralButton("清空选择", (dialog, which) -> {
                    cacheTarget = ""; rememberCacheSelection(); refreshCacheSelection();
                }).setNegativeButton("取消", null).show();
    }
    private void chooseApps() {
        ArrayList<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo app : getPackageManager().getInstalledApplications(0)) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !app.packageName.equals(getPackageName())) apps.add(app);
        }
        apps.sort(Comparator.comparing(a -> getPackageManager().getApplicationLabel(a).toString()));
        CharSequence[] labels = new CharSequence[apps.size()]; boolean[] selected = new boolean[apps.size()];
        java.util.Set<String> existing;
        try { existing = io.github.fakegeo.config.Validation.packages(fields.get("targets").getText().toString()); }
        catch (IllegalArgumentException invalid) { existing = java.util.Collections.emptySet(); }
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = getPackageManager().getApplicationLabel(apps.get(i)) + "\n" + apps.get(i).packageName;
            selected[i] = existing.contains(apps.get(i).packageName);
        }
        new AlertDialog.Builder(this).setTitle("选择目标 App").setMultiChoiceItems(labels, selected, (dialog, index, checked) -> selected[index] = checked)
                .setPositiveButton("使用选择", (dialog, which) -> {
                    ArrayList<String> names = new ArrayList<>();
                    for (int i = 0; i < selected.length; i++) if (selected[i]) names.add(apps.get(i).packageName);
                    fields.get("targets").setText(String.join("\n", names));
                }).setNegativeButton("取消", null).show();
    }
    private void field(String key, String label, String value, boolean numeric) {
        text(label, 13, UiKit.MUTED);
        EditText edit = new EditText(this); edit.setText(value); edit.setSingleLine(true);
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setTextSize(15); edit.setTextColor(UiKit.INK); edit.setPadding(dp(14),dp(12),dp(14),dp(12));
        edit.setBackground(UiKit.rounded(Color.WHITE,12));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2); layout.bottomMargin=dp(6);
        content.addView(edit,layout); fields.put(key, edit);
    }
    private void flag(String key, String label, boolean checked) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextColor(UiKit.INK); box.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT)); box.setChecked(checked); content.addView(box); flags.put(key, box);
    }
    private void text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8)); content.addView(view);
    }
    private void button(String label, Runnable action) {
        Button button = UiKit.button(this,label,false); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,dp(50)); layout.topMargin=dp(8); content.addView(button,layout);
    }
    private void section(String label) { text(label,18,UiKit.INK); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
