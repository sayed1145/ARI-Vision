package com.ari.recog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 32×32 attention overlay. Optional paint mode for editing. */
public class HeatmapView extends View {
    private float[] heat = HeatmapStore.ones();
    private Bitmap bg;
    private boolean editable;
    private boolean plus = true;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;

    public interface Listener { void onPainted(); }

    public HeatmapView(Context c) {
        super(c);
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(0x663DDC97);
        frame.setStrokeWidth(2f);
        setMinimumHeight(dp(220));
    }

    public void setHeat(float[] h) {
        if (h != null && h.length == HeatmapStore.LEN) heat = h.clone();
        invalidate();
    }
    public float[] getHeat() { return heat; }
    public void setBackgroundBitmap(Bitmap b) { bg = b; invalidate(); }
    public void setEditable(boolean e) { editable = e; }
    public void setPlus(boolean p) { plus = p; }
    public void setListener(Listener l) { listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        if (w <= 0) w = dp(280);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (bg != null) {
            c.drawBitmap(bg, null, new RectF(0, 0, W, H), null);
        } else {
            p.setColor(0xFF121A2A);
            c.drawRect(0, 0, W, H, p);
        }
        int n = HeatmapStore.N;
        float cw = W / (float) n, ch = H / (float) n;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float v = heat[y * n + x];
                int a = Math.round(90 + 130 * v);
                int r = Math.round(40 + 200 * v);
                int g = Math.round(220 * (1f - Math.abs(v - 0.55f)));
                p.setColor((a << 24) | (r << 16) | (g << 8) | 40);
                c.drawRect(x * cw, y * ch, (x + 1) * cw, (y + 1) * ch, p);
            }
        }
        c.drawRoundRect(new RectF(1, 1, W - 1, H - 1), 8, 8, frame);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editable) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN
                || e.getAction() == MotionEvent.ACTION_MOVE) {
            paintAt(e.getX(), e.getY());
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null) listener.onPainted();
        return true;
    }

    private void paintAt(float px, float py) {
        int n = HeatmapStore.N;
        int cx = (int) (px / getWidth() * n);
        int cy = (int) (py / getHeight() * n);
        float delta = plus ? 0.18f : -0.18f;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= n || y >= n) continue;
                float fall = 1f - 0.22f * (dx * dx + dy * dy);
                if (fall < 0) continue;
                int i = y * n + x;
                heat[i] = HeatmapStore.clamp01(heat[i] + delta * fall);
            }
        }
        invalidate();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
