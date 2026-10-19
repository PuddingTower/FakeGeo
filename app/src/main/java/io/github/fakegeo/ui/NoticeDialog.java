package io.github.fakegeo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class NoticeDialog {
    static void show(Activity activity, boolean required, Runnable accepted) {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 8), UiKit.dp(activity, 20), UiKit.dp(activity, 12));
        ScrollView scroll = new ScrollView(activity);
        TextView text = UiKit.text(activity, AppInfo.NOTICE, 14, UiKit.INK, false);
        text.setLineSpacing(UiKit.dp(activity, 5), 1); scroll.addView(text);
        int height = Math.min(UiKit.dp(activity, 380), Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.48f));
        body.addView(scroll, new LinearLayout.LayoutParams(-1, height));
        CheckBox read = new CheckBox(activity); read.setText("我已阅读、理解并同意以上说明"); read.setTextColor(UiKit.INK);
        if (required) body.addView(read);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("免费开源 · 使用说明").setView(body);
        if (required) builder.setPositiveButton("同意并继续", null).setNegativeButton("不同意，退出", (d, w) -> activity.finishAffinity()).setCancelable(false);
        else builder.setPositiveButton("我知道了", null);
        AlertDialog dialog = builder.create();
        if (required) dialog.setOnShowListener(d -> {
            android.widget.Button yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE); yes.setEnabled(false);
            read.setOnCheckedChangeListener((box, checked) -> yes.setEnabled(checked));
            yes.setOnClickListener(v -> {
                try { SetupPreferences.accept(activity); dialog.dismiss(); accepted.run(); }
                catch (RuntimeException failure) { android.widget.Toast.makeText(activity, failure.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
            });
        });
        dialog.show();
    }
    private NoticeDialog() {}
}
