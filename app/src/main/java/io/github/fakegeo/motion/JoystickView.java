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
    private boolean directionLocked;
    private double magnitude,bearing;
    JoystickView(Context context,Listener listener) {
        super(context); this.listener=listener; setContentDescription("移动摇杆，上北下南左西右东，松手停止");
    }
    @Override protected void onDraw(Canvas canvas) {
        float cx=getWidth()/2f,cy=getHeight()/2f,r=radius();
        paint.setColor(0xFFEAF3EF);canvas.drawCircle(cx,cy,r,paint);
        paint.setColor(0xFFBDCEC5);paint.setStrokeWidth(2);
        float inset=UiKit.dp(getContext(),6);
        canvas.drawLine(cx-r+inset,cy,cx+r-inset,cy,paint);canvas.drawLine(cx,cy-r+inset,cx,cy+r-inset,paint);
        paint.setTextSize(UiKit.dp(getContext(),12));paint.setTextAlign(Paint.Align.CENTER);paint.setColor(UiKit.MUTED);
        canvas.drawText("北",cx,cy-r+UiKit.dp(getContext(),12),paint);
        paint.setColor(UiKit.ACCENT);canvas.drawCircle(cx+x,cy+y,knobRadius(),paint);
        paint.setColor(0xFFFFFFFF);canvas.drawCircle(cx+x,cy+y,knobRadius()*.28f,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(!isEnabled()) return false;
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer=e.getPointerId(0);if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);move(e,0);return true;
            case MotionEvent.ACTION_MOVE:
                int index=e.findPointerIndex(pointer);if(index>=0) move(e,index);else release();return true;
            case MotionEvent.ACTION_POINTER_DOWN: case MotionEvent.ACTION_POINTER_UP: case MotionEvent.ACTION_CANCEL:
                release();return true;
            case MotionEvent.ACTION_UP:
                if(directionLocked && pointer>=0 && magnitude>0) { pointer=-1;if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false); }
                else release();
                performClick();return true;
            default:return true;
        }
    }
    private void move(MotionEvent e,int index) {
        if(pointer<0) return;
        double dx=e.getX(index)-getWidth()/2f,dy=e.getY(index)-getHeight()/2f;
        double r=Math.max(1,radius()-knobRadius());
        double length=Math.hypot(dx,dy),scale=Math.min(1,r/Math.max(1,length));
        x=(float)(dx*scale);y=(float)(dy*scale);invalidate();
        double amount=Math.min(1,length/r);
        magnitude=amount<0.12?0:(amount-0.12)/0.88;
        bearing=(Math.toDegrees(Math.atan2(dx,-dy))+360)%360;
        listener.changed(magnitude,bearing);
    }
    private float radius() { return Math.max(1,Math.min(getWidth(),getHeight())/2f-UiKit.dp(getContext(),6)); }
    private float knobRadius() { return Math.min(UiKit.dp(getContext(),16),radius()*.32f); }
    boolean isDirectionLocked() { return directionLocked; }
    void setDirectionLocked(boolean locked) {
        directionLocked=locked;
        setContentDescription(locked?"移动摇杆，上北下南左西右东，方向锁定，松手继续移动":"移动摇杆，上北下南左西右东，松手停止");
        if(!locked) release();
    }
    void repeat() { if(isEnabled() && magnitude>0) listener.changed(magnitude,bearing); }
    void release() {
        pointer=-1;x=0;y=0;magnitude=0;bearing=0;
        if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();listener.changed(0,0);
    }
    @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled);if(!enabled && listener!=null) release(); }
    @Override public boolean performClick() { super.performClick();return true; }
    @Override protected void onDetachedFromWindow() { release();super.onDetachedFromWindow(); }
}
