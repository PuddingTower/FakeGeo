package io.github.fakegeo.motion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import io.github.fakegeo.ui.UiKit;

/** North is up; pointer capture is deliberately released on cancel, detach and multi-touch. */
final class JoystickView extends View {
    interface Listener { void changed(double magnitude,double bearing); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private float x,y;
    private int pointer=-1;
    JoystickView(Context context,Listener listener) {
        super(context); this.listener=listener; setContentDescription("移动摇杆，上北下南左西右东，松手停止");
    }
    @Override protected void onDraw(Canvas canvas) {
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)-14;
        paint.setColor(0xFFEAF3EF);canvas.drawCircle(cx,cy,r,paint);
        paint.setColor(0xFFBDCEC5);paint.setStrokeWidth(2);
        canvas.drawLine(cx-r+12,cy,cx+r-12,cy,paint);canvas.drawLine(cx,cy-r+12,cx,cy+r-12,paint);
        paint.setTextSize(UiKit.dp(getContext(),12));paint.setTextAlign(Paint.Align.CENTER);paint.setColor(UiKit.MUTED);
        canvas.drawText("北",cx,cy-r+17,paint);
        paint.setColor(UiKit.ACCENT);canvas.drawCircle(cx+x,cy+y,UiKit.dp(getContext(),24),paint);
        paint.setColor(0xFFFFFFFF);canvas.drawCircle(cx+x,cy+y,UiKit.dp(getContext(),7),paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(!isEnabled()) return false;
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer=e.getPointerId(0);getParent().requestDisallowInterceptTouchEvent(true);move(e,0);return true;
            case MotionEvent.ACTION_MOVE:
                int index=e.findPointerIndex(pointer);if(index>=0) move(e,index);else release();return true;
            case MotionEvent.ACTION_POINTER_DOWN: case MotionEvent.ACTION_POINTER_UP: case MotionEvent.ACTION_CANCEL:
                release();return true;
            case MotionEvent.ACTION_UP: release();performClick();return true;
            default:return true;
        }
    }
    private void move(MotionEvent e,int index) {
        if(pointer<0) return;
        double dx=e.getX(index)-getWidth()/2f,dy=e.getY(index)-getHeight()/2f;
        double r=Math.max(1,Math.min(getWidth(),getHeight())/2f-UiKit.dp(getContext(),28));
        double length=Math.hypot(dx,dy),scale=Math.min(1,r/Math.max(1,length));
        x=(float)(dx*scale);y=(float)(dy*scale);invalidate();
        double magnitude=Math.min(1,length/r);
        listener.changed(magnitude<0.12?0:(magnitude-0.12)/0.88,(Math.toDegrees(Math.atan2(dx,-dy))+360)%360);
    }
    void release() { pointer=-1;x=0;y=0;invalidate();listener.changed(0,0); }
    @Override public boolean performClick() { super.performClick();return true; }
    @Override protected void onDetachedFromWindow() { release();super.onDetachedFromWindow(); }
}
