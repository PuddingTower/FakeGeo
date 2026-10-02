package io.github.fakegeo.motion;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import io.github.fakegeo.ui.UiKit;
import java.util.Locale;

/** Overlay controls independent of root publication, so gestures can never run root on the UI thread. */
final class JoystickPanel extends LinearLayout {
    interface Listener { void motion(double amount,double bearing,double kmh); void close(); }
    final TextView handle,status,speedLabel;
    final JoystickView joystick;
    final SeekBar speed;
    private final Listener listener;
    private int mode;
    private double minKmh,maxKmh,targetKmh;
    JoystickPanel(Context context,Listener listener) {
        super(context);this.listener=listener;setOrientation(VERTICAL);setPadding(dp(12),dp(8),dp(12),dp(12));
        setBackground(UiKit.rounded(0xFFFDFEFE,18));setElevation(dp(8));
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        handle=UiKit.text(context,"移动摇杆 · 拖动",13,UiKit.INK,true);header.addView(handle,new LayoutParams(0,dp(36),1));
        Button close=UiKit.button(context,"×",false);header.addView(close,new LayoutParams(dp(42),dp(36)));
        close.setContentDescription("关闭悬浮摇杆");close.setOnClickListener(v->listener.close());addView(header);
        LinearLayout modes=new LinearLayout(context);String[] names={"走路","跑步","骑车"};
        for(int i=0;i<3;i++) { final int choice=i;Button button=UiKit.button(context,names[i],false);button.setTextSize(12);
            modes.addView(button,new LayoutParams(0,dp(40),1));button.setOnClickListener(v->setMode(choice)); }addView(modes);
        speedLabel=UiKit.text(context,"",12,UiKit.INK,false);speedLabel.setPadding(0,dp(8),0,0);addView(speedLabel);
        speed=new SeekBar(context);speed.setMax(100);speed.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        speed.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));addView(speed,new LayoutParams(-1,dp(36)));
        joystick=new JoystickView(context,(amount,bearing)->listener.motion(amount,bearing,targetKmh));
        int available=Math.round(context.getResources().getDisplayMetrics().heightPixels/context.getResources().getDisplayMetrics().density)-260;
        joystick.setEnabled(false);addView(joystick,new LayoutParams(-1,dp(Math.max(80,Math.min(160,available)))));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                targetKmh=minKmh+(maxKmh-minKmh)*value/100.0;updateSpeedLabel();
                if(user) context.getSharedPreferences("joystick",Context.MODE_PRIVATE).edit().putInt("speed_"+mode,value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { release(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        status=UiKit.text(context,"正在检查系统 Hook…",12,UiKit.MUTED,false);status.setGravity(Gravity.CENTER);addView(status);
        Button pause=UiKit.button(context,"暂停移动",false);addView(pause,new LayoutParams(-1,dp(40)));pause.setOnClickListener(v->release());
        setMode(Math.max(0,Math.min(2,context.getSharedPreferences("joystick",Context.MODE_PRIVATE).getInt("mode",0))));
    }
    void setMode(int value) {
        release();mode=value;minKmh=new double[]{1,5,6}[value];maxKmh=new double[]{7,18,45}[value];
        int progress=Math.max(0,Math.min(100,getContext().getSharedPreferences("joystick",Context.MODE_PRIVATE).getInt("speed_"+value,50)));
        speed.setProgress(progress);targetKmh=minKmh+(maxKmh-minKmh)*progress/100.0;updateSpeedLabel();
        getContext().getSharedPreferences("joystick",Context.MODE_PRIVATE).edit().putInt("mode",value).apply();
    }
    private void updateSpeedLabel() { speedLabel.setText(String.format(Locale.CHINA,"%s · 目标 %.1f km/h（速度有波动）",new String[]{"走路","跑步","骑车"}[mode],targetKmh)); }
    void release() { joystick.release(); }
    private int dp(int value) { return UiKit.dp(getContext(),value); }
}
