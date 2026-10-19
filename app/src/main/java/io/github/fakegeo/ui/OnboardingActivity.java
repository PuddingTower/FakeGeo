package io.github.fakegeo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.fakegeo.root.RootBridge;
import io.github.fakegeo.root.RootCommands;
import io.github.fakegeo.root.RuntimeGate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** A guide, not a claim that the module can grant LSPosed scopes or an API Key itself. */
public final class OnboardingActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView rootState, lspState, mapState;
    private Button request, check;
    private boolean checking;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(UiKit.BACKGROUND);
        page.addView(UiKit.toolbar(this, "欢迎使用 拟境", this::finish));
        ScrollView scroll = new ScrollView(this); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(12), dp(24), dp(24)); scroll.addView(body); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page); UiKit.insetPage(page);
        add(body, "三步完成初始设置", 25, true);
        add(body, "拟境免费开源。按下面的步骤准备 Root、系统 Hook 和地图服务。", 14, false);
        add(body, "1 · Root 授权", 19, true);
        rootState = add(body, "点击按钮，在 KernelSU 或 Root 管理器中允许拟境。", 14, false);
        request = button(body, "请求 Root 授权", () -> {
            rootState.setText("正在请求 Root 授权…"); request.setEnabled(false);
            worker.execute(() -> {
                boolean success; String result;
                try { RootBridge.authorize(); success = true; result = "已取得 Root 权限。"; }
                catch (Exception failure) { success = false; result = "授权未完成，请检查 Root 管理器后重试。"; }
                boolean ready = success; String message = result;
                runOnUiThread(() -> { if (isDestroyed()) return; SetupPreferences.rootSeen(this, ready); rootState.setText(message); request.setEnabled(true); if (ready) checkHook(); });
            });
        });
        add(body, "2 · LSPosed 系统作用域", 19, true);
        lspState = add(body, "在 LSPosed 启用拟境 并勾选系统作用域。Root 授权不能代替这一步。", 14, false);
        button(body, "配置 LSPosed", this::showScopes);
        check = button(body, "检查系统 Hook", this::checkHook);
        add(body, "3 · 高德地图与搜索", 19, true);
        mapState = add(body, "填写绑定本应用的 Android SDK Key，并同意高德服务。", 14, false);
        button(body, "配置高德 API Key", () -> startActivity(new Intent(this, MapSettingsActivity.class).putExtra("from_setup", true)));
        add(body, "高德 Key 需自行申请。暂不配置也可以进入软件，随后在高级设置补齐；地图与地址搜索在配置完成后可用。", 13, false);
        Button enter = UiKit.button(this, "进入首页", true);
        LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(-1, dp(54)); next.topMargin = dp(24); body.addView(enter, next);
        enter.setOnClickListener(v -> {
            if (!SetupPreferences.accepted(this)) return;
            SetupPreferences.complete(this, true);
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK)); finish();
        });
        button(body, "查看免费开源声明与免责声明", () -> NoticeDialog.show(this, false, () -> {}));
        if (!SetupPreferences.accepted(this)) NoticeDialog.show(this, true, this::refresh);
    }
    @Override protected void onResume() { super.onResume(); if (rootState != null && SetupPreferences.accepted(this)) refresh(); }
    private void refresh() {
        rootState.setText(SetupPreferences.rootSeen(this) ? "此前已完成 Root 授权，可点击授权按钮重新确认。" : "尚未完成 Root 授权，请在 Root 管理器中允许拟境。");
        boolean key = !MapPreferences.key(this).isEmpty();
        mapState.setText(key ? (MapPreferences.consent(this) ? "Key 已填写，高德服务已同意；实际可用性以地图与搜索结果为准。" : "Key 已填写，请在高德设置中同意高德服务。") : "尚未填写 Android SDK Key，请打开高德设置。");
        if (SetupPreferences.rootSeen(this)) checkHook();
    }
    private void checkHook() {
        if (!SetupPreferences.accepted(this) || checking) return;
        if (!SetupPreferences.rootSeen(this)) { lspState.setText("请先完成 Root 授权，再检查系统 Hook。"); return; }
        checking = true; check.setEnabled(false); lspState.setText("正在检查系统 Hook…");
        worker.execute(() -> {
            String message;
            try { JSONObject status = new JSONObject(RootBridge.execute(RootCommands.status())); RuntimeGate.requireCurrent(status, System.currentTimeMillis()); message = "已检测到系统 Hook（" + status.optString("moduleVersion") + "）。"; }
            catch (Exception failure) { message = "尚未确认可用系统 Hook，请核对作用域；首次设置后需要重启手机。"; }
            String result = message;
            runOnUiThread(() -> { if (isDestroyed()) return; checking = false; check.setEnabled(true); lspState.setText(result); });
        });
    }
    private void showScopes() {
        new AlertDialog.Builder(this).setTitle("LSPosed 系统作用域")
                .setMessage("启用拟境 并选择：\n• Android 系统／系统框架\n• 电话服务\n• 蓝牙服务\n• Wi-Fi 服务（列表存在时）\n\n首次设置后重启手机。系统模式无需勾选目标 App。")
                .setPositiveButton("打开 LSPosed", (dialog, which) -> {
                    Intent manager = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
                    if (manager != null) startActivity(manager);
                    else android.widget.Toast.makeText(this, "请手动打开 LSPosed 管理器并设置作用域", android.widget.Toast.LENGTH_LONG).show();
                }).setNegativeButton("知道了", null).show();
    }
    private TextView add(LinearLayout body, String text, int size, boolean bold) { TextView view = UiKit.text(this, text, size, bold ? UiKit.INK : UiKit.MUTED, bold); view.setPadding(0, dp(10), 0, dp(10)); body.addView(view); return view; }
    private Button button(LinearLayout body, String text, Runnable action) { Button view = UiKit.button(this, text, false); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50)); p.topMargin = dp(8); body.addView(view, p); view.setOnClickListener(v -> action.run()); return view; }
    private int dp(int value) { return UiKit.dp(this, value); }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
