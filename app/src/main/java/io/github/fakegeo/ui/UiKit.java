package io.github.fakegeo.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
    public static final int ACCENT=Color.rgb(8,127,115), INK=Color.rgb(26,44,48), MUTED=Color.rgb(113,131,135), BACKGROUND=Color.rgb(246,248,248);
    static final int SEARCH=0,SETTINGS=1,BACK=2,CLOSE=3,TARGET=4,PIN=5;
    public static int dp(Context context,float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    public static GradientDrawable rounded(int color,float radius) {
        GradientDrawable shape=new GradientDrawable(); shape.setColor(color);
        shape.setCornerRadius(radius*android.content.res.Resources.getSystem().getDisplayMetrics().density); return shape;
    }
    public static TextView text(Context context,String value,int size,int color,boolean bold) {
        TextView text=new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL)); return text;
    }
    public static Button button(Context context,String title,boolean primary) {
        Button button=new Button(context); button.setText(title); button.setTextSize(15); button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); button.setTextColor(primary?Color.WHITE:ACCENT);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20087F73),rounded(primary?ACCENT:0xFFE8F3F0,16),null));
        button.setElevation(0); button.setStateListAnimator(null); return button;
    }
    static LinearLayout toolbar(Activity activity,String title,Runnable back) {
        LinearLayout bar=new LinearLayout(activity); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(activity,16),dp(activity,8),dp(activity,20),dp(activity,8));
        View icon=icon(activity,BACK); icon.setContentDescription("返回"); icon.setOnClickListener(v->back.run()); bar.addView(icon,new LinearLayout.LayoutParams(dp(activity,44),dp(activity,44)));
        TextView text=text(activity,title,20,INK,true); LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2); params.leftMargin=dp(activity,8); bar.addView(text,params); return bar;
    }
    static void insetPage(View page) {
        page.setOnApplyWindowInsetsListener((view,insets)-> {
            if(android.os.Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        }); page.requestApplyInsets();
    }
    static View icon(Context context,int kind) { return new Icon(context,kind); }
    private static final class Icon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private final int kind;
        Icon(Context context,int kind) { super(context); this.kind=kind; setLayerType(View.LAYER_TYPE_SOFTWARE,null); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.save(); float scale=Math.min(getWidth()-getPaddingLeft()-getPaddingRight(),getHeight()-getPaddingTop()-getPaddingBottom())/24f;
            c.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2); c.scale(scale,scale);
            paint.setColor(kind==PIN?ACCENT:INK); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStyle(Paint.Style.STROKE);
            if(kind==SEARCH) { c.drawCircle(10.5f,10.5f,6,paint); c.drawLine(15,15,20,20,paint); }
            else if(kind==BACK) { c.drawLine(15,5,8,12,paint); c.drawLine(8,12,15,19,paint); }
            else if(kind==CLOSE) { c.drawLine(6,6,18,18,paint); c.drawLine(18,6,6,18,paint); }
            else if(kind==TARGET) { c.drawCircle(12,12,6,paint); c.drawCircle(12,12,2,paint); c.drawLine(12,2,12,6,paint); c.drawLine(12,18,12,22,paint); c.drawLine(2,12,6,12,paint); c.drawLine(18,12,22,12,paint); }
            else if(kind==SETTINGS) {
                for(int y:new int[]{6,12,18}) c.drawLine(4,y,20,y,paint);
                paint.setStyle(Paint.Style.FILL); c.drawCircle(9,6,2.5f,paint); c.drawCircle(15,12,2.5f,paint); c.drawCircle(9,18,2.5f,paint);
            } else {
                Path path=new Path(); path.moveTo(12,23); path.cubicTo(9,19,4,14,4,9); path.cubicTo(4,-1,20,-1,20,9); path.cubicTo(20,14,15,19,12,23); path.close();
                paint.setStyle(Paint.Style.FILL); paint.setShadowLayer(2,0,2,0x40087F73); c.drawPath(path,paint); paint.clearShadowLayer(); paint.setColor(Color.WHITE); c.drawCircle(12,8,3,paint);
            }
            c.restore();
        }
    }
}
