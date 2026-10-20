package io.github.fakegeo.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;
import io.github.fakegeo.R;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.environment.EnvironmentSnapshot;
import io.github.fakegeo.root.RootProfilePublisher;
import io.github.fakegeo.ui.MainActivity;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** A large home-screen control surface; every switch publishes through the same root path as the app. */
public final class LocationWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = "io.github.fakegeo.action.WIDGET_TOGGLE";
    private static final String EXTRA_KEY = "key";
    private static final String KEY_ENABLED = "enabled", KEY_LOCATION = "location",
            KEY_WIFI = "wifi", KEY_BLUETOOTH = "bluetooth", KEY_CELL = "cell";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int INK = 0xFFE6EEEA, MUTED = 0xFF90A39C, ACCENT = 0xFF5FE0C8, ON_ACCENT = 0xFF04211C;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, build(context));
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) { refreshAll(context); return; }
        if (!ACTION_TOGGLE.equals(action)) return;
        String key = intent.getStringExtra(EXTRA_KEY);
        if (!known(key)) return;
        Context app = context.getApplicationContext();
        RemoteViews syncing = build(app);
        syncing.setTextViewText(R.id.widget_status, "正在同步…");
        syncing.setTextColor(R.id.widget_status, MUTED);
        AppWidgetManager.getInstance(app).updateAppWidget(
                new ComponentName(app, LocationWidgetProvider.class), syncing);
        PendingResult pending = goAsync();
        WORKER.execute(() -> {
            String error = null;
            try {
                synchronized (ConfigStore.class) {
                    Profile current = ConfigStore.load(app);
                    JSONObject json = current.json();
                    if (KEY_ENABLED.equals(key)) json.put("enabled", !ConfigStore.published(app).enabled);
                    else if (KEY_LOCATION.equals(key)) json.put("location", !current.location);
                    else if (KEY_WIFI.equals(key)) json.put("wifi", !current.wifi);
                    else if (KEY_BLUETOOTH.equals(key)) json.put("bluetooth", !current.bluetooth);
                    else json.put("cell", !current.cell);
                    RootProfilePublisher.publishUser(app, new Profile(json));
                }
            } catch (Exception failure) {
                error = errorText(failure);
            }
            if (error != null) {
                String message = "同步失败：" + error;
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, message, Toast.LENGTH_LONG).show());
            }
            refreshAll(app);
            pending.finish();
        });
    }

    public static void refreshAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, LocationWidgetProvider.class));
            if (ids.length > 0) manager.updateAppWidget(ids, build(context));
        } catch (RuntimeException unavailable) {
            // Widget hosts are not available during direct boot or teardown.
        }
    }

    private static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_main);
        Profile saved = ConfigStore.load(context);
        boolean enabled = ConfigStore.published(context).enabled;
        views.setTextViewText(R.id.widget_status, enabled ? "● 运行中" : "○ 已停止");
        views.setTextColor(R.id.widget_status, enabled ? ACCENT : MUTED);

        boolean hasPoint = saved.latitude != 0 || saved.longitude != 0;
        views.setTextViewText(R.id.widget_location, hasPoint
                ? String.format(Locale.US, "%.6f, %.6f", saved.latitude, saved.longitude)
                : "尚未选择位置");
        views.setTextColor(R.id.widget_location, hasPoint ? INK : MUTED);

        EnvironmentSnapshot environment = saved.environment;
        views.setTextViewText(R.id.widget_environment, environment == null
                ? "手工配置 · WGS84 · 点击打开拟境"
                : environment.name + " · " + environment.summary());

        views.setTextViewText(R.id.widget_master, enabled ? "停止虚拟定位" : "开启虚拟定位");
        views.setInt(R.id.widget_master, "setBackgroundResource",
                enabled ? R.drawable.widget_pill_on : R.drawable.widget_pill_off);
        views.setTextColor(R.id.widget_master, enabled ? ON_ACCENT : ACCENT);

        pill(views, R.id.widget_toggle_location, "定位", saved.location, enabled);
        pill(views, R.id.widget_toggle_wifi, "Wi-Fi", saved.wifi, enabled);
        pill(views, R.id.widget_toggle_bluetooth, "蓝牙", saved.bluetooth, enabled);
        pill(views, R.id.widget_toggle_cell, "基站", saved.cell, enabled);

        views.setOnClickPendingIntent(R.id.widget_master, toggle(context, KEY_ENABLED, 1));
        views.setOnClickPendingIntent(R.id.widget_toggle_location, toggle(context, KEY_LOCATION, 2));
        views.setOnClickPendingIntent(R.id.widget_toggle_wifi, toggle(context, KEY_WIFI, 3));
        views.setOnClickPendingIntent(R.id.widget_toggle_bluetooth, toggle(context, KEY_BLUETOOTH, 4));
        views.setOnClickPendingIntent(R.id.widget_toggle_cell, toggle(context, KEY_CELL, 5));
        PendingIntent open = open(context);
        views.setOnClickPendingIntent(R.id.widget_header, open);
        views.setOnClickPendingIntent(R.id.widget_location, open);
        views.setOnClickPendingIntent(R.id.widget_environment, open);
        return views;
    }

    private static void pill(RemoteViews views, int id, String label, boolean on, boolean running) {
        views.setTextViewText(id, (on ? "● " : "○ ") + label);
        views.setInt(id, "setBackgroundResource", !on ? R.drawable.widget_pill_off
                : running ? R.drawable.widget_pill_on : R.drawable.widget_pill_armed);
        views.setTextColor(id, on ? (running ? ON_ACCENT : ACCENT) : MUTED);
    }

    private static PendingIntent toggle(Context context, String key, int requestCode) {
        Intent intent = new Intent(context, LocationWidgetProvider.class)
                .setAction(ACTION_TOGGLE).putExtra(EXTRA_KEY, key);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent open(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean known(String key) {
        return KEY_ENABLED.equals(key) || KEY_LOCATION.equals(key) || KEY_WIFI.equals(key)
                || KEY_BLUETOOTH.equals(key) || KEY_CELL.equals(key);
    }

    private static String errorText(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
