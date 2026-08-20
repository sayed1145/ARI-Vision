package com.ari.recog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Full-screen non-touchable overlay: mint boxes + free-form labels. */
public class DetectionOverlayView extends View {

    public static class Box {
        public final int x, y, w, h;
        public final String label;
        public final float conf;
        public final int serial;
        public Box(int x, int y, int w, int h, String label, float conf, int serial) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.label = label == null ? "?" : label;
            this.conf = conf; this.serial = serial;
        }
        public Box(int x, int y, int w, int h, int digit, float conf, int serial) {
            this(x, y, w, h, String.valueOf(digit), conf, serial);
        }
    }

    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Box> boxes = new ArrayList<>();
    private String status = "";

    public DetectionOverlayView(Context context) {
        super(context);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(2.2f));
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(dp(13));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setBoxes(List<Box> b) { this.boxes = b != null ? b : new ArrayList<>(); }
    public void setStatus(String s) { this.status = s == null ? "" : s; }
    public void clearBoxes() { this.boxes = new ArrayList<>(); status = ""; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // ROI rectangle
        float[] roi = SettingsActivity.getRoi(getContext());
        float rw = getWidth(), rh = getHeight();
        border.setColor(0x663DDC97);
        border.setStrokeWidth(dp(1.5f));
        canvas.drawRect(roi[0] * rw, roi[1] * rh, roi[2] * rw, roi[3] * rh, border);

        boolean perf = SettingsActivity.getPerfOverlay(getContext());
        boolean dbg = SettingsActivity.getDebugOverlay(getContext());
        int lines = 1 + (perf ? 2 : 0) + (dbg ? 1 : 0);
        float top = dp(10 + 18 * lines);
        bar.setColor(0xCC070B14);
        canvas.drawRoundRect(new RectF(dp(10), dp(8), getWidth() - dp(10), top),
                dp(10), dp(10), bar);
        text.setColor(0xFF3DDC97);
        text.setTextSize(dp(12));
        String head = status.isEmpty() ? "ARI" : status;
        canvas.drawText(head, dp(20), dp(26), text);
        float y = dp(26);
        if (perf) {
            y += dp(16);
            text.setColor(0xFF8E9BB3);
            text.setTextSize(dp(11));
            canvas.drawText(String.format(java.util.Locale.US, "%.1f fps  %dms  work %dx%d  roi %dx%d",
                    DetectStats.fpsActual, DetectStats.ms, DetectStats.workW, DetectStats.workH,
                    DetectStats.roiW, DetectStats.roiH), dp(20), y, text);
            y += dp(15);
            canvas.drawText("mem " + DetectStats.memLine() + "  " + DetectStats.hwLine(),
                    dp(20), y, text);
        }
        if (dbg) {
            y += dp(15);
            text.setColor(0xFFFFB020);
            canvas.drawText(String.format(java.util.Locale.US,
                    "obj %d/%d  digit cand %d pass %d track %d",
                    DetectStats.objPass, DetectStats.objCand,
                    DetectStats.cand, DetectStats.pass, DetectStats.tracked), dp(20), y, text);
        }
        for (Box b : boxes) {
            int color = b.conf >= 0.55f ? 0xFF3DDC97 : 0xFFFFB020;
            border.setColor(color);
            canvas.drawRoundRect(new RectF(b.x, b.y, b.x + b.w, b.y + b.h), dp(4), dp(4), border);
            String label = b.label + "  " + Math.round(b.conf * 100) + "%";
            text.setTextSize(dp(12));
            float tw = text.measureText(label);
            float pad = dp(5);
            float lx = b.x;
            float ly = b.y - dp(18);
            if (ly < dp(40)) ly = b.y + dp(16);
            fill.setColor(0xE0070B14);
            canvas.drawRoundRect(new RectF(lx, ly - dp(13), lx + tw + pad * 2, ly + dp(4)),
                    dp(8), dp(8), fill);
            text.setColor(color);
            canvas.drawText(label, lx + pad, ly, text);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
