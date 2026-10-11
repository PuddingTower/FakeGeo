package io.github.fakegeo.motion;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import io.github.fakegeo.ui.UiKit;
import java.util.Locale;

/** Compact controls; secondary settings expand only when requested. */
final class JoystickPanel extends LinearLayout {
    static final int WIDTH_DP=180;
    private static final String[] MODES={"走路","跑步","骑车"};
    interface Listener {
        void motion(double amount,double bearing,double kmh);
        default void mode(int mode) {}
        default void sensors(boolean enabled) {}
        default void sensorSetup() {}
        default void layoutChanged() {}
        void close();
    }
    final TextView handle,status,speedLabel;
    final JoystickView joystick;
    final SeekBar speed;
    final Button sensorButton,lockButton,pauseButton,settingsButton,closeButton;
    private final LinearLayout settings;
    private final Listener listener;
    private int mode;
    private double minKmh,maxKmh,targetKmh;
    private boolean sensorEnabled,sensorAvailable,expanded;

    JoystickPanel(Context context,Listener listener) {
        super(context);this.listener=listener;setOrientation(VERTICAL);setPadding(dp(8),dp(6),dp(8),dp(8));
        setBackground(UiKit.rounded(0xF5FDFEFE,14));setElevation(dp(5));
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        handle=UiKit.text(context,"摇杆 · 拖动",11,UiKit.INK,true);handle.setSingleLine(true);handle.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(handle,new LayoutParams(0,dp(32),1));
        settingsButton=compactButton("设置");header.addView(settingsButton,new LayoutParams(dp(38),dp(32)));
        settingsButton.setContentDescription("展开移动设置");settingsButton.setOnClickListener(v->setExpanded(!expanded));
        closeButton=compactButton("×");closeButton.setTextSize(18);header.addView(closeButton,new LayoutParams(dp(28),dp(32)));
        closeButton.setContentDescription("关闭悬浮摇杆");closeButton.setOnClickListener(v->{ release();listener.close(); });addView(header);

        joystick=new JoystickView(context,(amount,bearing)->listener.motion(amount,bearing,targetKmh));
        joystick.setEnabled(false);addView(joystick,new LayoutParams(-1,dp(112)));
        status=UiKit.text(context,"正在检查 Hook…",11,UiKit.MUTED,false);status.setGravity(Gravity.CENTER);status.setMaxLines(1);status.setMinHeight(dp(22));
        addView(status,new LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(context);
        lockButton=compactButton("方向锁定");actions.addView(lockButton,new LayoutParams(0,dp(36),1));
        lockButton.setEnabled(false);lockButton.setOnClickListener(v->setDirectionLocked(!joystick.isDirectionLocked()));
        pauseButton=compactButton("暂停");LayoutParams pauseLayout=new LayoutParams(0,dp(36),1);pauseLayout.leftMargin=dp(4);
        actions.addView(pauseButton,pauseLayout);pauseButton.setOnClickListener(v->release());addView(actions);

        settings=new LinearLayout(context);settings.setOrientation(VERTICAL);settings.setPadding(0,dp(6),0,0);
        LinearLayout modes=new LinearLayout(context);
        for(int i=0;i<3;i++) {
            final int choice=i;Button button=compactButton(MODES[i]);
            LayoutParams layout=new LayoutParams(0,dp(34),1);if(i>0) layout.leftMargin=dp(3);
            modes.addView(button,layout);button.setOnClickListener(v->setMode(choice));
        }
        settings.addView(modes);
        speedLabel=UiKit.text(context,"",11,UiKit.INK,false);speedLabel.setPadding(0,dp(6),0,0);speedLabel.setSingleLine(true);settings.addView(speedLabel);
        speed=new SeekBar(context);speed.setMax(100);speed.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        speed.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));settings.addView(speed,new LayoutParams(-1,dp(32)));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                targetKmh=minKmh+(maxKmh-minKmh)*value/100.0;updateSpeedLabel();
                if(user) context.getSharedPreferences("joystick",Context.MODE_PRIVATE).edit().putInt("speed_"+mode,value).apply();
                if(joystick.isDirectionLocked()) joystick.repeat();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { if(!joystick.isDirectionLocked()) release(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sensorButton=compactButton("传感器 · 未选择应用");settings.addView(sensorButton,new LayoutParams(-1,dp(34)));
        sensorButton.setEnabled(false);sensorButton.setOnClickListener(v->{
            release();
            if(!sensorAvailable) { listener.sensorSetup();return; }
            sensorEnabled=!sensorEnabled;refreshSensors();listener.sensors(sensorEnabled);
        });
        addView(settings);settings.setVisibility(View.GONE);
        setMode(Math.max(0,Math.min(2,context.getSharedPreferences("joystick",Context.MODE_PRIVATE).getInt("mode",0))));
        refreshLock();
    }
    void setMode(int value) {
        release();mode=value;minKmh=new double[]{1,5,6}[value];maxKmh=new double[]{7,18,45}[value];
        int progress=Math.max(0,Math.min(100,getContext().getSharedPreferences("joystick",Context.MODE_PRIVATE).getInt("speed_"+value,50)));
        speed.setProgress(progress);targetKmh=minKmh+(maxKmh-minKmh)*progress/100.0;updateSpeedLabel();
        getContext().getSharedPreferences("joystick",Context.MODE_PRIVATE).edit().putInt("mode",value).apply();
        refreshSensors();listener.mode(value);
    }
    void sensors(boolean enabled,boolean available) {
        sensorEnabled=enabled;sensorAvailable=available;sensorButton.setEnabled(true);lockButton.setEnabled(true);refreshSensors();
    }
    void setExpanded(boolean value) {
        expanded=value;settings.setVisibility(value?View.VISIBLE:View.GONE);settingsButton.setText(value?"收起":"设置");
        settingsButton.setContentDescription(value?"收起移动设置":"展开移动设置");listener.layoutChanged();
    }
    void setDirectionLocked(boolean locked) { joystick.setDirectionLocked(locked);refreshLock(); }
    void showMotion(boolean moving,double kmh) {
        status.setText(String.format(Locale.CHINA,"%s%s · %.1f km/h",moving && joystick.isDirectionLocked()?"锁定":"",MODES[mode],kmh));
    }
    private void refreshLock() {
        boolean locked=joystick.isDirectionLocked();lockButton.setText(locked?"已锁定":"方向锁定");
        lockButton.setTextColor(locked?android.graphics.Color.WHITE:UiKit.ACCENT);
        lockButton.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x20087F73),UiKit.rounded(locked?UiKit.ACCENT:0xFFE8F3F0,12),null));
        lockButton.setContentDescription(locked?"关闭方向锁定并停止移动":"开启方向锁定，松手后继续移动");
    }
    private void refreshSensors() {
        sensorButton.setText(!sensorAvailable?"传感器 · 未选择应用":mode==2?"传感器 · 骑车不模拟":sensorEnabled?"运动传感器 · 开启":"运动传感器 · 关闭");
        sensorButton.setContentDescription("运动传感器模拟"+(sensorEnabled?"已开启":"已关闭"));
    }
    private void updateSpeedLabel() { speedLabel.setText(String.format(Locale.CHINA,"%s · 目标 %.1f km/h",MODES[mode],targetKmh)); }
    void release() { joystick.release(); }
    private Button compactButton(String title) {
        Button button=UiKit.button(getContext(),title,false);button.setTextSize(12);button.setMinWidth(0);button.setMinimumWidth(0);
        button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(0,0,0,0);return button;
    }
    private int dp(int value) { return UiKit.dp(getContext(),value); }
}
