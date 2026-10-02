package io.github.fakegeo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.fakegeo.config.ConfigStore;
import io.github.fakegeo.config.Profile;
import io.github.fakegeo.motion.JoystickService;

public final class MovementSettingsActivity extends Activity {
    private TextView state;
    private Button start;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(UiKit.BACKGROUND);
        page.addView(UiKit.toolbar(this,"悬浮移动",this::finish));
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(20),dp(24),dp(24));scroll.addView(body);
        body.addView(UiKit.text(this,"用摇杆控制方向",23,UiKit.INK,true));
        TextView help=UiKit.text(this,"上北下南左西右东，松手即停。\n走路 1–7 km/h · 跑步 5–18 km/h · 骑车 6–45 km/h\n速度滑条设置目标速度，摇杆推得越远移动越快；加入平滑加速和速度波动。",14,UiKit.MUTED,false);
        help.setPadding(0,dp(16),0,dp(16));help.setLineSpacing(dp(5),1);body.addView(help);
        state=UiKit.text(this,"",14,UiKit.ACCENT,true);body.addView(state);
        Button grant=UiKit.button(this,"授权显示悬浮窗",false);body.addView(grant,buttonParams());
        grant.setOnClickListener(v->{ try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))); } catch(android.content.ActivityNotFoundException absent) { android.widget.Toast.makeText(this,"请在系统设置中允许 FakeGeo 显示悬浮窗",android.widget.Toast.LENGTH_LONG).show(); } });
        if(android.os.Build.VERSION.SDK_INT>=33) {
            Button notification=UiKit.button(this,"允许悬浮控制通知",false);body.addView(notification,buttonParams());
            notification.setOnClickListener(v->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},35));
        }
        start=UiKit.button(this,"打开悬浮摇杆",true);body.addView(start,buttonParams());start.setOnClickListener(v->toggle());
        TextView note=UiKit.text(this,"请先开启系统虚拟定位。拖动窗口标题可以调整位置，暂停或关闭只停止移动，保留虚拟定位。锁屏暂停；从最近任务移除软件会关闭控制窗。服务失去响应时，运动指令最多 6 秒后停止。\n\n小幅定位漂移默认开启，可在高级设置调整半径。升级系统 Hook 后需重启手机。",13,UiKit.MUTED,false);
        note.setPadding(0,dp(20),0,0);note.setLineSpacing(dp(5),1);body.addView(note);
        setContentView(page);UiKit.insetPage(page);
        if(saved==null) new AlertDialog.Builder(this).setTitle("悬浮移动兼容性提示")
                .setMessage("部分 App 不生效，发现问题请提交 Issue。")
                .setNeutralButton("提交 Issue",(dialog,which)-> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(AppInfo.REPOSITORY+"/issues/new"))); }
                    catch(android.content.ActivityNotFoundException absent) { android.widget.Toast.makeText(this,"请在 GitHub 项目页面提交 Issue",android.widget.Toast.LENGTH_LONG).show(); }
                })
                .setPositiveButton("知道了",null).show();
    }
    private void toggle() {
        if(JoystickService.isRunning()) { stopService(new Intent(this,JoystickService.class));refresh();return; }
        Profile p=ConfigStore.published(this);
        if(!p.enabled || !p.systemMode || !p.location) { new AlertDialog.Builder(this).setTitle("先开启虚拟定位").setMessage("在地图选择位置并开启系统虚拟定位，再打开悬浮摇杆。").setPositiveButton("知道了",null).show();return; }
        if(!Settings.canDrawOverlays(this)) { new AlertDialog.Builder(this).setTitle("需要悬浮窗权限").setMessage("点击“授权显示悬浮窗”，在系统设置允许后返回此页面。").setPositiveButton("知道了",null).show();return; }
        try { startForegroundService(new Intent(this,JoystickService.class));getWindow().getDecorView().postDelayed(this::refresh,700); }
        catch(RuntimeException error) { android.widget.Toast.makeText(this,"无法启动悬浮窗："+error.getClass().getSimpleName(),android.widget.Toast.LENGTH_LONG).show(); }
    }
    private void refresh() {
        boolean allowed=Settings.canDrawOverlays(this),running=JoystickService.isRunning();
        state.setText(running?"● 悬浮摇杆已打开":allowed?"悬浮窗权限已就绪":"尚未授权悬浮窗权限");start.setText(running?"关闭悬浮摇杆":"打开悬浮摇杆");
    }
    @Override protected void onResume() { super.onResume();refresh(); }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.topMargin=dp(12);return p; }
    private int dp(int value) { return UiKit.dp(this,value); }
}
