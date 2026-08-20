package com.ari.recog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * TemplateBank — printed + handwritten 28×28 templates and NCC matching.
 * Screen UI digits are printed fonts, not MNIST handwriting, so the ARI
 * model alone is not enough. NCC against rendered typefaces fills the gap.
 */
public final class TemplateBank {

    public static final class Match {
        public final int digit;
        public final float ncc;
        public final float gap;
        public Match(int d, float n, float g) { digit = d; ncc = n; gap = g; }
    }

    private final List<float[]>[] tmpls;

    @SuppressWarnings("unchecked")
    public TemplateBank(Context ctx) {
        tmpls = new List[10];
        for (int d = 0; d < 10; d++) tmpls[d] = new ArrayList<>();

        // Built-in handwritten MNIST samples
        try {
            SampleImageProvider prov = new SampleImageProvider(ctx);
            for (SampleImageProvider.SampleImage s : prov.all()) {
                tmpls[s.label].add(centered(toUnit(s.toInput784())));
            }
        } catch (Exception e) {
            DebugLog.e("TemplateBank samples", e);
        }

        // Rendered printed digits (several typefaces / sizes / weights)
        Typeface[] faces = new Typeface[]{
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
                Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Typeface.create(Typeface.SERIF, Typeface.BOLD),
                Typeface.DEFAULT_BOLD
        };
        int[] sizes = new int[]{22, 28, 36};
        for (Typeface tf : faces) {
            for (int sz : sizes) {
                for (int d = 0; d < 10; d++) {
                    float[] feat = renderDigit(d, tf, sz);
                    if (feat != null) tmpls[d].add(centered(toUnit(feat)));
                }
            }
        }
        int n = 0;
        for (List<float[]> l : tmpls) n += l.size();
        DebugLog.d("TemplateBank ready: " + n + " templates");
    }

    public Match match(float[] img784) {
        float[] f = centered(toUnit(img784));
        float fn = norm(f);
        float[] classBest = new float[10];
        for (int d = 0; d < 10; d++) classBest[d] = -1f;
        for (int d = 0; d < 10; d++) {
            for (float[] t : tmpls[d]) {
                float ncc = dot(f, t) / (fn * norm(t));
                if (ncc > classBest[d]) classBest[d] = ncc;
            }
        }
        int bestD = 0;
        for (int d = 1; d < 10; d++) if (classBest[d] > classBest[bestD]) bestD = d;
        float best = classBest[bestD];
        float second = -1f;
        for (int d = 0; d < 10; d++) if (d != bestD && classBest[d] > second) second = classBest[d];
        return new Match(bestD, best, best - second);
    }

    private static float[] centered(float[] a) {
        float[] o = a.clone();
        center(o);
        return o;
    }

    private static float[] renderDigit(int digit, Typeface tf, int textSize) {
        try {
            int box = 40;
            Bitmap bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFF000000);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(textSize);
            p.setTypeface(tf);
            p.setTextAlign(Paint.Align.LEFT);
            String s = String.valueOf(digit);
            Rect b = new Rect();
            p.getTextBounds(s, 0, s.length(), b);
            float x = (box - b.width()) / 2f - b.left;
            float y = (box - b.height()) / 2f - b.top;
            c.drawText(s, x, y, p);
            float[][] patch = new float[box][box];
            int[] px = new int[box * box];
            bmp.getPixels(px, 0, box, 0, 0, box, box);
            for (int i = 0; i < px.length; i++) {
                int v = px[i] & 0xFF;
                patch[i / box][i % box] = v;
            }
            bmp.recycle();
            return DigitPreprocessor.toMnist28(patch);
        } catch (Exception e) {
            return null;
        }
    }

    private static float[] toUnit(float[] img784) {
        float[] o = new float[784];
        float mx = 1f;
        for (float v : img784) if (v > mx) mx = v;
        float inv = mx > 1.5f ? 1f / 255f : 1f;
        for (int i = 0; i < 784; i++) o[i] = img784[i] * inv;
        return o;
    }

    private static void center(float[] a) {
        float m = 0;
        for (float v : a) m += v;
        m /= a.length;
        for (int i = 0; i < a.length; i++) a[i] -= m;
    }

    private static float norm(float[] a) {
        float s = 0;
        for (float v : a) s += v * v;
        return (float) Math.sqrt(s) + 1e-6f;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
