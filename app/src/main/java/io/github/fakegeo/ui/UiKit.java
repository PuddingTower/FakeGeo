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
    public static final int ACCENT=Color.rgb(95,224,200), INK=Color.rgb(230,238,234), MUTED=Color.rgb(144,163,156), BACKGROUND=Color.rgb(11,15,13);
    public static final int SURFACE=Color.rgb(20,26,23), SURFACE_VARIANT=Color.rgb(28,36,32), OUTLINE=Color.rgb(44,56,51);
    public static final int ON_ACCENT=Color.rgb(4,33,28), DANGER=Color.rgb(255,122,122);
    static final int SEARCH=0,SETTINGS=1,BACK=2,CLOSE=3,TARGET=4,PIN=5,ENV=6;
    public static int dp(Context context,float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    public static GradientDrawable rounded(int color,float radius) {
        GradientDrawable shape=new GradientDrawable(); shape.setColor(color);
        shape.setCornerRadius(radius*android.content.res.Resources.getSystem().getDisplayMetrics().density); return shape;
    }
    public static GradientDrawable surface(Context context,float radius) {
        GradientDrawable shape=rounded(SURFACE,radius); shape.setStroke(dp(context,1),OUTLINE); return shape;
    }
    public static GradientDrawable surfaceVariant(Context context,float radius) {
        GradientDrawable shape=rounded(SURFACE_VARIANT,radius); shape.setStroke(dp(context,1),OUTLINE); return shape;
    }
    static int rippleColor() { return 0x335FE0C8; }
    public static TextView text(Context context,String value,int size,int color,boolean bold) {
        TextView text=new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL)); return text;
    }
    public static Button button(Context context,String title,boolean primary) {
        Button button=new Button(context); button.setText(title); button.setTextSize(15); button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); button.setTextColor(primary?ON_ACCENT:ACCENT);
        GradientDrawable shape=primary?rounded(ACCENT,14):surfaceVariant(context,14);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x33000000:rippleColor()),shape,null));
        button.setElevation(0); button.setStateListAnimator(null); button.setMinHeight(0); button.setMinimumHeight(0);
        return button;
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
            else if(kind==ENV) {
                c.drawArc(new android.graphics.RectF(3.5f,3.5f,20.5f,20.5f),-55,290,false,paint);
                c.drawArc(new android.graphics.RectF(7,7,17,17),-55,290,false,paint);
                c.drawArc(new android.graphics.RectF(10.5f,10.5f,13.5f,13.5f),-55,210,false,paint);
                c.drawLine(12,15,12,19,paint);
            }
            else if(kind==SETTINGS) {
                for(int y:new int[]{6,12,18}) c.drawLine(4,y,20,y,paint);
                paint.setStyle(Paint.Style.FILL); c.drawCircle(9,6,2.5f,paint); c.drawCircle(15,12,2.5f,paint); c.drawCircle(9,18,2.5f,paint);
            } else {
                Path path=new Path(); path.moveTo(12,23); path.cubicTo(9,19,4,14,4,9); path.cubicTo(4,-1,20,-1,20,9); path.cubicTo(20,14,15,19,12,23); path.close();
                paint.setStyle(Paint.Style.FILL); paint.setShadowLayer(2,0,2,0x60000000); c.drawPath(path,paint); paint.clearShadowLayer(); paint.setColor(BACKGROUND); c.drawCircle(12,8,3,paint);
            }
            c.restore();
        }
    }
}
