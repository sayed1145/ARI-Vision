package com.ari.recog;

import android.graphics.Bitmap;

/**
 * Color + texture + spatial-grid features for general objects.
 * HSV histogram, folded LBP, 4×4 mean RGB, 32×32 gray template.
 */
public final class ColorTexture {
    public static final int HBIN = 8, SBIN = 6, VBIN = 4;
    public static final int HIST = HBIN * SBIN * VBIN; // 192
    public static final int LBP = 16;
    public static final int GRID = 4 * 4 * 3; // 48
    public static final int TPL = 32;
    public static final int HOG = 8;
    public final float[] hog = new float[HOG];

    public final float[] hsv = new float[HIST];
    public final float[] lbp = new float[LBP];
    public final float[] grid = new float[GRID];
    public final float[] tpl = new float[TPL * TPL];
    public final float meanR, meanG, meanB;
    public final int srcW, srcH;

    public ColorTexture(Bitmap bmp) {
        int w = Math.max(1, bmp.getWidth());
        int h = Math.max(1, bmp.getHeight());
        srcW = w;
        srcH = h;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        extract(px, w, h);
        float sr = 0, sg = 0, sb = 0;
        for (int p : px) {
            sr += (p >> 16) & 255;
            sg += (p >> 8) & 255;
            sb += p & 255;
        }
        float n = px.length;
        meanR = sr / n;
        meanG = sg / n;
        meanB = sb / n;
    }

    /** Consensus / reconstructed feature pack (LabelBank). */
    public ColorTexture(float[] hsv, float[] lbp, float[] hog, float[] grid, float[] tpl,
                        float meanR, float meanG, float meanB) {
        srcW = TPL;
        srcH = TPL;
        if (hsv != null) System.arraycopy(hsv, 0, this.hsv, 0, Math.min(HIST, hsv.length));
        if (lbp != null) System.arraycopy(lbp, 0, this.lbp, 0, Math.min(LBP, lbp.length));
        if (hog != null) System.arraycopy(hog, 0, this.hog, 0, Math.min(HOG, hog.length));
        if (grid != null) System.arraycopy(grid, 0, this.grid, 0, Math.min(GRID, grid.length));
        if (tpl != null) System.arraycopy(tpl, 0, this.tpl, 0, Math.min(this.tpl.length, tpl.length));
        this.meanR = meanR;
        this.meanG = meanG;
        this.meanB = meanB;
    }

    public ColorTexture(int[] px, int w, int h) {
        srcW = w;
        srcH = h;
        extract(px, w, h);
        float sr = 0, sg = 0, sb = 0;
        for (int p : px) {
            sr += (p >> 16) & 255;
            sg += (p >> 8) & 255;
            sb += p & 255;
        }
        float n = Math.max(1, px.length);
        meanR = sr / n;
        meanG = sg / n;
        meanB = sb / n;
    }

    private void extract(int[] px, int w, int h) {
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            gray[i] = (r * 30 + g * 59 + b * 11) / 100;
            // HSV
            float rf = r / 255f, gf = g / 255f, bf = b / 255f;
            float mx = Math.max(rf, Math.max(gf, bf));
            float mn = Math.min(rf, Math.min(gf, bf));
            float df = mx - mn + 1e-6f;
            float hh;
            if (mx == rf) hh = ((gf - bf) / df) % 6f;
            else if (mx == gf) hh = (bf - rf) / df + 2f;
            else hh = (rf - gf) / df + 4f;
            if (hh < 0) hh += 6f;
            hh /= 6f;
            float s = df / (mx + 1e-6f);
            float v = mx;
            int hi = clamp((int) (hh * HBIN), 0, HBIN - 1);
            int si = clamp((int) (s * SBIN), 0, SBIN - 1);
            int vi = clamp((int) (v * VBIN), 0, VBIN - 1);
            hsv[(hi * SBIN + si) * VBIN + vi] += 1f;
        }
        float hs = 0;
        for (float x : hsv) hs += x;
        if (hs > 0) for (int i = 0; i < HIST; i++) hsv[i] /= hs;

        // LBP folded to 16 bins
        if (w >= 3 && h >= 3) {
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int c = gray[y * w + x];
                    int code = 0;
                    if (gray[y * w + x + 1] >= c) code |= 1;
                    if (gray[(y - 1) * w + x + 1] >= c) code |= 2;
                    if (gray[(y - 1) * w + x] >= c) code |= 4;
                    if (gray[(y - 1) * w + x - 1] >= c) code |= 8;
                    if (gray[y * w + x - 1] >= c) code |= 16;
                    if (gray[(y + 1) * w + x - 1] >= c) code |= 32;
                    if (gray[(y + 1) * w + x] >= c) code |= 64;
                    if (gray[(y + 1) * w + x + 1] >= c) code |= 128;
                    lbp[code >> 4] += 1f;
                }
            }
            float ls = 0;
            for (float x : lbp) ls += x;
            if (ls > 0) for (int i = 0; i < LBP; i++) lbp[i] /= ls;
        }

        // 4x4 mean RGB
        int gi = 0;
        for (int gy = 0; gy < 4; gy++) {
            for (int gx = 0; gx < 4; gx++) {
                int y0 = h * gy / 4, y1 = h * (gy + 1) / 4;
                int x0 = w * gx / 4, x1 = w * (gx + 1) / 4;
                if (y1 <= y0) y1 = y0 + 1;
                if (x1 <= x0) x1 = x0 + 1;
                long sr = 0, sg = 0, sb = 0;
                int cnt = 0;
                for (int y = y0; y < y1 && y < h; y++)
                    for (int x = x0; x < x1 && x < w; x++) {
                        int p = px[y * w + x];
                        sr += (p >> 16) & 255;
                        sg += (p >> 8) & 255;
                        sb += p & 255;
                        cnt++;
                    }
                float inv = 1f / Math.max(1, cnt) / 255f;
                grid[gi++] = sr * inv;
                grid[gi++] = sg * inv;
                grid[gi++] = sb * inv;
            }
        }

        // 32x32 gray template (bilinear-ish nearest)
        for (int y = 0; y < TPL; y++) {
            int sy = Math.min(h - 1, (int) ((y + 0.5f) * h / TPL));
            for (int x = 0; x < TPL; x++) {
                int sx = Math.min(w - 1, (int) ((x + 0.5f) * w / TPL));
                tpl[y * TPL + x] = gray[sy * w + sx];
            }
        }
        if (w >= 3 && h >= 3) {
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int gx = gray[y * w + x + 1] - gray[y * w + x - 1];
                    int gy = gray[(y + 1) * w + x] - gray[(y - 1) * w + x];
                    float mag = (float) Math.hypot(gx, gy);
                    if (mag < 8) continue;
                    int bin = (int) ((Math.atan2(gy, gx) + Math.PI) / (2 * Math.PI) * HOG);
                    if (bin < 0) bin = 0;
                    if (bin >= HOG) bin = HOG - 1;
                    hog[bin] += mag;
                }
            }
            float hs2 = 0;
            for (float v : hog) hs2 += v;
            if (hs2 > 0) for (int i = 0; i < HOG; i++) hog[i] /= hs2;
        }
    }

    public static float intersect(float[] a, float[] b) {
        float s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) s += Math.min(a[i], b[i]);
        return s;
    }

    public static float ncc32(float[] a, float[] b) {
        return ncc32w(a, b, null);
    }

    /** Weighted NCC. heat[i] in 0..1 focuses the inner product on subject pixels. */
    public static float ncc32w(float[] a, float[] b, float[] heat) {
        int n = Math.min(a.length, b.length);
        float ma = 0, mb = 0, sw = 0;
        for (int i = 0; i < n; i++) {
            float w = heat == null ? 1f : (i < heat.length ? heat[i] : 1f);
            ma += w * a[i];
            mb += w * b[i];
            sw += w;
        }
        if (sw < 1e-6f) return 0;
        ma /= sw;
        mb /= sw;
        float num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            float w = heat == null ? 1f : (i < heat.length ? heat[i] : 1f);
            float xa = a[i] - ma, xb = b[i] - mb;
            num += w * xa * xb;
            da += w * xa * xa;
            db += w * xb * xb;
        }
        return (float) (num / (Math.sqrt(da * db) + 1e-6));
    }

    /** Combined score. deform=true leans on color/HOG (pose/warp) over pixel NCC. */
    public float scoreAgainst(ColorTexture o) {
        return scoreAgainst(o, false);
    }

    public float scoreAgainst(ColorTexture o, boolean deform) {
        return scoreAgainst(o, deform, null);
    }

    public float scoreAgainst(ColorTexture o, boolean deform, float[] heat) {
        float col = intersect(hsv, o.hsv);
        float tex = intersect(lbp, o.lbp);
        float hg = intersect(hog, o.hog);
        float grd = 0;
        for (int i = 0; i < GRID; i++) grd += Math.abs(grid[i] - o.grid[i]);
        grd = 1f - grd / GRID;
        if (grd < 0) grd = 0;
        float ncc = Math.max(0f, ncc32w(tpl, o.tpl, heat));
        if (deform) return 0.40f * col + 0.16f * tex + 0.22f * hg + 0.12f * grd + 0.10f * ncc;
        return 0.38f * col + 0.16f * tex + 0.12f * hg + 0.14f * grd + 0.20f * ncc;
    }

    public static String formulaOf(ColorTexture t, float[] heat, boolean deform, int nShot) {
        StringBuilder sb = new StringBuilder();
        if (deform) sb.append("S = 0.40 I_HSV + 0.16 I_LBP + 0.22 I_HOG + 0.12 (1−‖ΔG‖₁) + 0.10 NCC_w\n");
        else sb.append("S = 0.38 I_HSV + 0.16 I_LBP + 0.12 I_HOG + 0.14 (1−‖ΔG‖₁) + 0.20 NCC_w\n");
        sb.append("I_HSV = Σ_k min(h_k, h′_k)     ∈ [0,1]\n");
        sb.append("I_LBP = Σ_k min(ℓ_k, ℓ′_k)     16-bin folded LBP\n");
        sb.append("I_HOG = Σ_k min(g_k, g′_k)     8-bin gradient\n");
        sb.append("NCC_w = ⟨H⊙(T−μ), H⊙(T′−μ)⟩ / (‖H⊙(T−μ)‖ ‖H⊙(T′−μ)‖)\n");
        sb.append(String.format(java.util.Locale.US,
                "μ_RGB = (%.1f, %.1f, %.1f)    shots n = %d\n",
                t.meanR, t.meanG, t.meanB, nShot));
        sb.append("HOG = [");
        for (int i = 0; i < HOG; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format(java.util.Locale.US, "%.2f", t.hog[i]));
        }
        sb.append("]\n");
        float hs = 0;
        if (heat != null) for (float v : heat) hs += v;
        sb.append(String.format(java.util.Locale.US,
                "H ∈ [0,1]^{32×32}   ΣH = %.1f / %d\n",
                hs, heat == null ? 0 : heat.length));
        sb.append("same-label merge: h ← min_i h^{(i)}  (common color only)");
        return sb.toString();
    }

    public float colorIntersect(ColorTexture o) {
        return intersect(hsv, o.hsv);
    }

    /** 0..1 how much edge/texture exists — flat UI chrome scores near 0. */
    public float structure() {
        float e = 0;
        for (float v : hog) e += v; // already normalized; use entropy-like spread
        float hogEnt = 0;
        for (float v : hog) if (v > 1e-6f) hogEnt -= v * (float) Math.log(v + 1e-6);
        hogEnt /= (float) Math.log(HOG);
        float var = 0, m = 0;
        for (float v : tpl) m += v;
        m /= tpl.length;
        for (float v : tpl) { float d = v - m; var += d * d; }
        var = (float) Math.sqrt(var / tpl.length) / 128f;
        if (var > 1) var = 1;
        return 0.55f * clamp01(var) + 0.45f * clamp01(hogEnt);
    }

    public float hogIntersect(ColorTexture o) { return intersect(hog, o.hog); }
    public float texIntersect(ColorTexture o) { return intersect(lbp, o.lbp); }

    private static float clamp01(float v) { return v < 0 ? 0 : Math.min(1f, v); }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
