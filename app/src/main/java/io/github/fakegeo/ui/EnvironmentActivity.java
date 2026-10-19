package io.github.fakegeo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.environment.EnvironmentCapture;
import io.github.fakegeo.environment.EnvironmentSnapshot;
import io.github.fakegeo.environment.EnvironmentStore;
import io.github.fakegeo.root.RootProfilePublisher;
import org.json.JSONObject;

/** Captures a place once, then applies the filtered anchors without typing a single MAC. */
public final class EnvironmentActivity extends Activity {
    private static final int REQUEST_CAPTURE = 71;
    private LinearLayout list;
    private TextView status, active;
    private Button captureButton, importButton, exportButton, stopButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EnvironmentCapture session;
    private boolean pendingCapture, busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiKit.BACKGROUND);
        page.addView(UiKit.toolbar(this, "环境指纹", this::finish));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(32));
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        UiKit.insetPage(page);

        section(body, "当前环境");
        active = UiKit.text(this, "", 14, UiKit.INK, false);
        active.setLineSpacing(dp(4), 1);
        body.addView(active);
        status = UiKit.text(this, "", 13, UiKit.ACCENT, false);
        status.setPadding(0, dp(8), 0, 0);
        body.addView(status);
        captureButton = action(body, "采集当前环境", true, this::capture);
        stopButton = action(body, "停止虚拟环境", false, () -> stopEnvironment(false));
        text(body, "采集只在前台运行约 22 秒：Wi-Fi 采样 3 次、蓝牙 14 秒、小区 2 次，结束后自动停止，不做后台扫描。采集前请先停止虚拟环境。", 13, UiKit.MUTED);
        text(body, "BLE 多设备回放只在应用兼容模式生效；系统模式下回放 Wi-Fi 与小区，蓝牙仍保持隔离。", 13, UiKit.MUTED);

        section(body, "已保存环境");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);

        section(body, "导入与导出");
        importButton = action(body, "从剪贴板导入环境 JSON", false, this::importClipboard);
        exportButton = action(body, "复制整个环境库到剪贴板", false, this::exportClipboard);
        text(body, "环境 JSON 包含坐标、Wi-Fi、蓝牙广播和小区字段，可直接在设备之间迁移。应用环境会同时启用对应的模拟开关。", 13, UiKit.MUTED);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) refresh();
    }

    @Override protected void onDestroy() {
        if (session != null) { session.cancel(); session = null; }
        worker.shutdown();
        super.onDestroy();
    }

    private void capture() {
        if (busy) return;
        Profile published = ConfigStore.published(this);
        if (published.enabled) {
            new AlertDialog.Builder(this).setTitle("先停止虚拟环境")
                    .setMessage("采集中必须读取真实无线信号。应用会先停止当前虚拟环境，再开始采集。")
                    .setPositiveButton("停止并采集", (dialog, which) -> stopEnvironment(true))
                    .setNegativeButton("取消", null).show();
            return;
        }
        beginCapture();
    }

    private void beginCapture() {
        String missing = EnvironmentCapture.missingPermission(this);
        if (missing != null) {
            pendingCapture = true;
            status("正在申请" + missing + "权限…");
            requestPermissions(EnvironmentCapture.permissions(), REQUEST_CAPTURE);
            return;
        }
        pendingCapture = false;
        setBusy(true);
        session = new EnvironmentCapture(this, new EnvironmentCapture.Listener() {
            @Override public void progress(String message) { if (!isDestroyed()) status(message); }
            @Override public void finished(EnvironmentSnapshot snapshot) {
                session = null;
                if (isDestroyed()) return;
                setBusy(false);
                saveCaptured(snapshot);
            }
            @Override public void failed(String message) {
                session = null;
                if (isDestroyed()) return;
                setBusy(false); status(message); toast(message);
            }
        });
        session.start();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != REQUEST_CAPTURE || !pendingCapture) return;
        pendingCapture = false;
        if (EnvironmentCapture.missingPermission(this) == null) beginCapture();
        else { setBusy(false); status("采集所需的定位、蓝牙或附近的 Wi-Fi 设备权限未授予"); }
    }

    private void saveCaptured(EnvironmentSnapshot snapshot) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(snapshot.name);
        name.setTextColor(UiKit.INK);
        name.setHintTextColor(UiKit.MUTED);
        name.setBackground(UiKit.surface(this, 12));
        name.setPadding(dp(14), dp(10), dp(14), dp(10));
        name.setSelectAllOnFocus(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(4));
        body.addView(UiKit.text(this, snapshot.summary(), 14, UiKit.MUTED, false));
        body.addView(UiKit.text(this, locationText(snapshot), 14, UiKit.MUTED, false));
        body.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("采集完成")
                .setView(body)
                .setPositiveButton("保存并应用", null)
                .setNeutralButton("仅保存", null)
                .setNegativeButton("放弃", null)
                .create();
        dialog.setOnShowListener(shown -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                EnvironmentSnapshot saved = snapshot.renamed(name.getText().toString());
                EnvironmentStore.save(this, saved);
                dialog.dismiss();
                publish(saved);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                EnvironmentStore.save(this, snapshot.renamed(name.getText().toString()));
                dialog.dismiss();
                refresh();
                toast("环境已保存，可随时应用");
            });
        });
        dialog.show();
    }

    private void refresh() {
        if (list == null) return;
        Profile published = ConfigStore.published(this);
        EnvironmentSnapshot current = published.enabled ? published.environment : null;
        if (stopButton != null) stopButton.setVisibility(published.enabled ? android.view.View.VISIBLE : android.view.View.GONE);
        active.setText(current == null
                ? (published.enabled ? "正在使用手工配置，尚未应用环境指纹。" : "尚未应用环境指纹。")
                : "已应用：" + current.name + "\n" + current.summary()
                    + (current.hasLocation ? "\n坐标 " + locationText(current) : "\n未绑定坐标"));
        list.removeAllViews();
        List<EnvironmentSnapshot> items = EnvironmentStore.all(this);
        if (items.isEmpty()) {
            TextView empty = UiKit.text(this, "还没有保存的环境。先停止虚拟环境，再采集公司或目标场所的真实无线指纹。", 14, UiKit.MUTED, false);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty);
            return;
        }
        for (EnvironmentSnapshot item : items) list.addView(row(item, current != null && item.id.equals(current.id)));
    }

    private LinearLayout row(EnvironmentSnapshot item, boolean activeRow) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.surface(this, 16));
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, -2);
        cardLayout.topMargin = dp(10);
        card.setLayoutParams(cardLayout);
        card.setOnClickListener(v -> details(item));

        TextView title = UiKit.text(this, item.name + (activeRow ? "  · 当前" : ""), 16, UiKit.INK, true);
        card.addView(title);
        TextView meta = UiKit.text(this, date(item.capturedAt) + " · " + item.summary()
                + (item.hasLocation ? " · " + locationText(item) : " · 无坐标"), 12, UiKit.MUTED, false);
        meta.setPadding(0, dp(5), 0, dp(10));
        card.addView(meta);

        LinearLayout buttons = new LinearLayout(this);
        Button apply = UiKit.button(this, activeRow ? "重新应用" : "应用", true);
        apply.setOnClickListener(v -> apply(item));
        buttons.addView(apply, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button delete = UiKit.button(this, "删除", false);
        delete.setOnClickListener(v -> confirmDelete(item));
        LinearLayout.LayoutParams deleteLayout = new LinearLayout.LayoutParams(0, dp(44), 1);
        deleteLayout.leftMargin = dp(8);
        buttons.addView(delete, deleteLayout);
        card.addView(buttons);
        return card;
    }

    private void details(EnvironmentSnapshot item) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(4));
        StringBuilder text = new StringBuilder(item.summary()).append('\n').append(locationText(item)).append("\n\n");
        text.append("Wi-Fi 锚点\n");
        if (item.wifi.isEmpty()) text.append("  无\n");
        for (int i = 0; i < Math.min(6, item.wifi.size()); i++) {
            io.github.fakegeo.environment.WifiFingerprint point = item.wifi.get(i);
            text.append("  ").append(point.ssid).append("  ").append(point.bssid)
                    .append("  ").append(point.rssi).append(" dBm  ").append(point.frequency).append(" MHz")
                    .append(point.connected ? "  · 已连接" : "").append('\n');
        }
        if (item.wifi.size() > 6) text.append("  …共 ").append(item.wifi.size()).append(" 个\n");
        text.append("\n蓝牙\n");
        if (item.ble.isEmpty()) text.append("  无\n");
        for (int i = 0; i < Math.min(5, item.ble.size()); i++) {
            io.github.fakegeo.environment.BleFingerprint point = item.ble.get(i);
            text.append("  ").append(point.name.isEmpty() ? "(未命名)" : point.name).append("  ").append(point.address)
                    .append("  ").append(point.rssi).append(" dBm")
                    .append(point.beaconLike ? "  · 信标" : "").append('\n');
        }
        if (item.ble.size() > 5) text.append("  …共 ").append(item.ble.size()).append(" 个\n");
        text.append("\n小区\n");
        if (item.cells.isEmpty()) text.append("  无\n");
        for (io.github.fakegeo.environment.CellFingerprint point : item.cells)
            text.append("  ").append(point.label()).append(point.registered ? "  · 驻留" : "").append('\n');
        TextView summary = UiKit.text(this, text.toString(), 13, UiKit.INK, false);
        summary.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(summary);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(dp(420), getResources().getDisplayMetrics().heightPixels / 2)));
        LinearLayout actions = new LinearLayout(this);
        Button copy = UiKit.button(this, "复制 JSON", false);
        copy.setOnClickListener(v -> copy(item.json().toString(), "环境 JSON 已复制"));
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button rename = UiKit.button(this, "重命名", false);
        rename.setOnClickListener(v -> rename(item));
        LinearLayout.LayoutParams renameLayout = new LinearLayout.LayoutParams(0, dp(44), 1);
        renameLayout.leftMargin = dp(8);
        actions.addView(rename, renameLayout);
        body.addView(actions);
        new AlertDialog.Builder(this).setTitle(item.name).setView(body).setPositiveButton("关闭", null).show();
    }

    private void rename(EnvironmentSnapshot item) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(item.name);
        name.setTextColor(UiKit.INK);
        name.setHintTextColor(UiKit.MUTED);
        name.setBackground(UiKit.surface(this, 12));
        name.setPadding(dp(14), dp(10), dp(14), dp(10));
        name.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("重命名环境")
                .setView(name)
                .setPositiveButton("保存", (dialog, which) -> {
                    EnvironmentStore.save(this, item.renamed(name.getText().toString()));
                    refresh();
                }).setNegativeButton("取消", null).show();
    }

    private void confirmDelete(EnvironmentSnapshot item) {
        new AlertDialog.Builder(this).setTitle("删除环境")
                .setMessage("删除“" + item.name + "”？当前已发布的环境不会立即改变，但下次应用前无法再选择它。")
                .setPositiveButton("删除", (dialog, which) -> { EnvironmentStore.delete(this, item.id); refresh(); })
                .setNegativeButton("取消", null).show();
    }

    private void apply(EnvironmentSnapshot item) {
        if (busy) return;
        String warning = item.hasLocation ? "" : "\n\n该环境没有坐标，位置将保持当前设置。";
        new AlertDialog.Builder(this).setTitle("应用环境")
                .setMessage(item.name + "\n" + item.summary() + "\n" + locationText(item) + warning)
                .setPositiveButton("应用", (dialog, which) -> publish(item))
                .setNegativeButton("取消", null).show();
    }

    private void publish(EnvironmentSnapshot item) {
        setBusy(true);
        status("正在发布环境指纹…");
        worker.execute(() -> {
            String error = null;
            try {
                Profile current = ConfigStore.load(this);
                JSONObject json = current.json();
                json.put("environment", item.json());
                if (item.hasLocation) json.put("latitude", item.latitude).put("longitude", item.longitude)
                        .put("altitude", item.altitude).put("accuracy", item.accuracy);
                json.put("wifi", !item.wifi.isEmpty());
                if (!item.wifi.isEmpty()) {
                    json.put("ssid", item.wifi.get(0).ssid);
                    json.put("bssid", item.wifi.get(0).bssid);
                }
                if (item.hasConnectedWifi()) json.put("wifiConnected", true);
                json.put("bluetooth", !item.ble.isEmpty() || current.beaconEnabled());
                // A captured environment always hides or replaces real cell identity; an empty cell list
                // must not silently expose the live serving cell.
                json.put("cell", true);
                if ("02:00:00:00:00:11".equals(current.wifiMac)) json.put("wifiMac", derivedMac(item.id, 0x11));
                if ("02:00:00:00:00:12".equals(current.bluetoothMac)) json.put("bluetoothMac", derivedMac(item.id, 0x12));
                if ("FakeGeo".equals(current.bluetoothName)) json.put("bluetoothName", "EnvSim");
                json.put("enabled", true);
                json.put("revision", Math.max(System.currentTimeMillis(), current.revision + 1));
                RootProfilePublisher.publishUser(this, new Profile(json));
            } catch (Exception failure) { error = failure.getMessage(); }
            String outcome = error;
            runOnUiThread(() -> {
                setBusy(false);
                if (isDestroyed()) return;
                if (outcome == null) {
                    toast("环境已应用，停止虚拟环境后恢复真实状态");
                    status("环境已发布。系统模式首次使用或系统 Hook 更新后需要重启手机。");
                    refresh();
                } else {
                    status("应用失败：" + outcome);
                    new AlertDialog.Builder(this).setTitle("环境未应用").setMessage(outcome)
                            .setPositiveButton("知道了", null).show();
                }
            });
        });
    }

    private void stopEnvironment(boolean thenCapture) {
        setBusy(true);
        status("正在停止虚拟环境…");
        worker.execute(() -> {
            String error = null;
            try {
                Profile current = ConfigStore.load(this);
                JSONObject json = current.json();
                json.put("enabled", false);
                json.put("revision", Math.max(System.currentTimeMillis(), current.revision + 1));
                RootProfilePublisher.publishUser(this, new Profile(json));
            } catch (Exception failure) { error = failure.getMessage(); }
            String outcome = error;
            runOnUiThread(() -> {
                setBusy(false);
                if (isDestroyed()) return;
                refresh();
                if (outcome != null) {
                    status("停止失败：" + outcome);
                    return;
                }
                status("虚拟环境已停止");
                if (thenCapture) beginCapture();
            });
        });
    }

    private void importClipboard() {
        if (busy) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        String value = clip == null || clip.getItemCount() == 0 ? "" : String.valueOf(clip.getItemAt(0).coerceToText(this));
        if (value.trim().isEmpty()) { toast("剪贴板中没有环境 JSON"); return; }
        try {
            int count = EnvironmentStore.importText(this, value);
            refresh();
            toast(count == 0 ? "没有识别到有效环境" : "已导入 " + count + " 个环境");
        } catch (Exception failure) { toast("导入失败：" + failure.getMessage()); }
    }

    private void exportClipboard() {
        List<EnvironmentSnapshot> items = EnvironmentStore.all(this);
        if (items.isEmpty()) { toast("环境库为空"); return; }
        copy(EnvironmentStore.exportAll(this), "环境库 JSON 已复制（" + items.size() + " 个）");
    }

    private void copy(String value, String message) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) { toast("系统剪贴板不可用"); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("environment", value));
        toast(message);
    }

    private static String derivedMac(String seed, int tail) {
        int hash = seed == null ? 0 : seed.hashCode();
        return String.format(Locale.ROOT, "02:%02X:%02X:%02X:%02X:%02X",
                (hash >> 24) & 0xFF, (hash >> 16) & 0xFF, (hash >> 8) & 0xFF, hash & 0xFF, tail);
    }

    private static String date(long value) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(value));
    }

    private static String locationText(EnvironmentSnapshot item) {
        if (!item.hasLocation) return "未采集到坐标";
        return String.format(Locale.US, "%.6f, %.6f · 精度 %.0f 米", item.latitude, item.longitude, item.accuracy);
    }

    private void status(String value) { if (status != null) { status.setText(value); } }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void setBusy(boolean value) {
        busy = value;
        if (value) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (captureButton != null) captureButton.setEnabled(!value);
        if (importButton != null) importButton.setEnabled(!value);
        if (exportButton != null) exportButton.setEnabled(!value);
        if (stopButton != null) stopButton.setEnabled(!value);
    }
    private void section(LinearLayout body, String title) {
        TextView text = UiKit.text(this, title, 19, UiKit.INK, true);
        text.setPadding(0, dp(22), 0, dp(8));
        body.addView(text);
    }
    private void text(LinearLayout body, String value, int size, int color) {
        TextView text = UiKit.text(this, value, size, color, false);
        text.setLineSpacing(dp(4), 1);
        text.setPadding(0, dp(8), 0, dp(8));
        body.addView(text);
    }
    private Button action(LinearLayout body, String label, boolean primary, Runnable action) {
        Button button = UiKit.button(this, label, primary);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(50));
        layout.topMargin = dp(8);
        body.addView(button, layout);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private int dp(int value) { return UiKit.dp(this, value); }
}
