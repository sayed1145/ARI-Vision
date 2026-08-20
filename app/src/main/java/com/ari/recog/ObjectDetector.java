package com.ari.recog;

import android.content.Context;
import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scale-invariant object detector.
 * Always works on a fixed-size canvas (≤256px wide) so time/memory stay
 * bounded regardless of screen size.
 *
 * v2.5: structure + color + (ncc|hog|tex) accept gate, one box per label,
 * detect-time saliency crop, no more empty-region spray.
 */
public final class ObjectDetector {

    public static final int WORK_W = 256;

    static final float MIN_STRUCTURE = 0.10f;
    static final float MIN_COL = 0.32f;
    static final float MIN_NCC = 0.18f;
    static final float MIN_HOG = 0.24f;
    static final float MIN_TEX = 0.24f;
    static final float MIN_SCORE = 0.50f;
    static final float FULL_MIN_SCORE = 0.62f;
    static final float FULL_MIN_NCC = 0.40f;
    static final float FULL_MIN_COL = 0.45f;
    static final float REFINE_MIN = 0.58f;
    static final float ADAPT_MIN = 0.72f;

    public static final class Hit {
        public int x, y, w, h;
        public String label;
        public float conf;
        public int serial;
        public String source;
        public int digit = -1;
        public Hit(int x, int y, int w, int h, String label, float conf, String src) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.label = label; this.conf = conf; this.source = src;
        }
    }

    private final ObjectStore store;
    private final DigitDetector digits;
    private final AdaptBank adapt;
    private final LabelBank labels;
    private boolean detectDigits = true;
    private boolean onlineAdapt = true;
    private boolean denoise = true;
    private boolean deform = true;
    private boolean motion = true;
    private int maxBoxes = 12;
    private List<Hit> lastTracks = new ArrayList<>();

    // reused every frame — no per-frame full-res alloc
    private int[] workRgb = new int[WORK_W * 512];
    private byte[] workGray = new byte[WORK_W * 512];
    private byte[] workMask = new byte[WORK_W * 512];
    private boolean[] workVis = new boolean[WORK_W * 512];
    private int[] qx = new int[WORK_W * 512];
    private int[] qy = new int[WORK_W * 512];
    private int workCap = WORK_W * 512;
    private int lastSw, lastSh;

    public ObjectDetector(Context ctx) {
        store = ObjectStore.get(ctx);
        digits = new DigitDetector(ctx);
        adapt = new AdaptBank(ctx);
        labels = new LabelBank(ctx, store);
        reloadSettings(ctx);
    }

    public ObjectStore store() { return store; }
    public DigitDetector digits() { return digits; }
    public LabelBank labels() { return labels; }

    public void reloadSettings(Context ctx) {
        detectDigits = SettingsActivity.getDetectDigits(ctx);
        digits.applySensitivity(SettingsActivity.getSensitivity(ctx));
        onlineAdapt = SettingsActivity.getOnlineAdapt(ctx);
        denoise = SettingsActivity.getDenoise(ctx);
        deform = SettingsActivity.getDeformRobust(ctx);
        motion = SettingsActivity.getMotionPredict(ctx);
        if (adapt != null) adapt.setEnabled(onlineAdapt);
    }

    public void setLastTracks(List<Hit> t) {
        lastTracks = t != null ? t : new ArrayList<Hit>();
    }

    private void ensureWork(int sw, int sh) {
        int need = sw * sh;
        if (need <= workCap) return;
        int cap = need + need / 4;
        workRgb = new int[cap];
        workGray = new byte[cap];
        workMask = new byte[cap];
        workVis = new boolean[cap];
        qx = new int[cap];
        qy = new int[cap];
        workCap = cap;
        DebugLog.d("work buffer realloc " + cap);
    }

    /**
     * Downsample ROI from the ImageReader into the fixed work canvas.
     * Returns [sw, sh, roiX, roiY, roiW, roiH] in full-screen pixels.
     */
    public List<Hit> detectRgba(ByteBuffer buffer, int rowStride, int fullW, int fullH, Context ctx) {
        float[] roi = ctx == null ? new float[]{0, 0, 1, 1} : SettingsActivity.getRoi(ctx);
        int rx = clamp((int) (roi[0] * fullW), 0, fullW - 1);
        int ry = clamp((int) (roi[1] * fullH), 0, fullH - 1);
        int r1 = clamp((int) (roi[2] * fullW), rx + 8, fullW);
        int b1 = clamp((int) (roi[3] * fullH), ry + 8, fullH);
        int rw = r1 - rx, rh = b1 - ry;

        int sw = WORK_W;
        int sh = Math.max(8, rh * sw / Math.max(1, rw));
        if (sh > WORK_W * 3) { sh = WORK_W * 3; sw = Math.max(8, rw * sh / Math.max(1, rh)); }
        ensureWork(sw, sh);
        lastSw = sw;
        lastSh = sh;
        DetectStats.beginFrame();
        DetectStats.workW = sw;
        DetectStats.workH = sh;
        DetectStats.roiW = rw;
        DetectStats.roiH = rh;

        float stepX = rw / (float) sw;
        float stepY = rh / (float) sh;
        for (int y = 0; y < sh; y++) {
            int sy = ry + (int) ((y + 0.5f) * stepY);
            if (sy >= fullH) sy = fullH - 1;
            int row = sy * rowStride;
            int dst = y * sw;
            for (int x = 0; x < sw; x++) {
                int sx = rx + (int) ((x + 0.5f) * stepX);
                if (sx >= fullW) sx = fullW - 1;
                int off = row + sx * 4;
                int r = buffer.get(off) & 0xFF;
                int g = buffer.get(off + 1) & 0xFF;
                int b = buffer.get(off + 2) & 0xFF;
                workRgb[dst + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                workGray[dst + x] = (byte) ((r * 30 + g * 59 + b * 11) / 100);
            }
        }
        if (denoise) denoiseWork(sw, sh);
        List<Hit> hits = detectOnWork(sw, sh, rx, ry, rw, rh);
        if (detectDigits) {
            byte[] g = new byte[sw * sh];
            System.arraycopy(workGray, 0, g, 0, sw * sh);
            List<DigitDetector.Hit> dh = digits.detectGray(g, sw, sh);
            float sx = rw / (float) sw, sy = rh / (float) sh;
            for (DigitDetector.Hit d : dh) {
                Hit h = new Hit(rx + Math.round(d.x * sx), ry + Math.round(d.y * sy),
                        Math.round(d.w * sx), Math.round(d.h * sy),
                        String.valueOf(d.digit), d.conf, "digit");
                h.digit = d.digit;
                hits.add(h);
            }
        }
        return finalizeHits(hits);
    }

    /** Backward-compat (tests / gallery): treat whole image as ROI. */
    public List<Hit> detectRgba(ByteBuffer buffer, int rowStride, int w, int h) {
        return detectRgba(buffer, rowStride, w, h, null);
    }

    public List<Hit> detect(int[] rgb, byte[] gray, int w, int h) {
        int sw = Math.min(WORK_W, w);
        int sh = Math.max(8, h * sw / Math.max(1, w));
        ensureWork(sw, sh);
        float stepX = w / (float) sw, stepY = h / (float) sh;
        for (int y = 0; y < sh; y++) {
            int sy = Math.min(h - 1, (int) ((y + 0.5f) * stepY));
            for (int x = 0; x < sw; x++) {
                int sx = Math.min(w - 1, (int) ((x + 0.5f) * stepX));
                int p = rgb[sy * w + sx];
                workRgb[y * sw + x] = p;
                workGray[y * sw + x] = gray != null ? gray[sy * w + sx]
                        : (byte) ((((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100);
            }
        }
        List<Hit> hits = detectOnWork(sw, sh, 0, 0, w, h);
        if (detectDigits) {
            byte[] g = new byte[sw * sh];
            System.arraycopy(workGray, 0, g, 0, sw * sh);
            List<DigitDetector.Hit> dh = digits.detectGray(g, sw, sh);
            float sx = w / (float) sw, sy = h / (float) sh;
            for (DigitDetector.Hit d : dh) {
                Hit hh = new Hit(Math.round(d.x * sx), Math.round(d.y * sy),
                        Math.round(d.w * sx), Math.round(d.h * sy),
                        String.valueOf(d.digit), d.conf, "digit");
                hh.digit = d.digit;
                hits.add(hh);
            }
        }
        return finalizeHits(hits);
    }

    private List<Hit> detectOnWork(int sw, int sh, int rx, int ry, int rw, int rh) {
        List<Hit> hits = new ArrayList<>();
        if (store.size() == 0) return hits;
        float mapX = rw / (float) sw, mapY = rh / (float) sh;
        int objCand = 0, objPass = 0;
        Map<String, Hit> best = new LinkedHashMap<>();

        int[] focus = Saliency.focusRect(workRgb, sw, sh);

        for (String lab : store.labels()) {
            LabelBank.Pack pack = labels.of(lab);
            if (pack == null || pack.consensus == null) continue;
            ColorTexture tmpl = pack.consensus;
            ColorTexture fullT = tmpl;
            float[] heat = pack.heat;

            // 1) whole-ROI — only if it really is the same structured image
            ColorTexture whole = new ColorTexture(copyRgb(workRgb, sw, sh), sw, sh);
            float wholeScore = bestScore(lab, tmpl, whole, heat);
            float wholeCol = tmpl.colorIntersect(whole);
            float wholeNcc = Math.max(0f, ColorTexture.ncc32(tmpl.tpl, whole.tpl));
            if (acceptPatch(tmpl, whole, wholeScore, true)) {
                DetectStats.addHit(-1, wholeScore, wholeNcc, whole.structure(), wholeScore, wholeCol, rw, rh, 0,
                        "full:" + lab);
                consider(best, new Hit(rx, ry, rw, rh, lab, wholeScore, "object_full"));
                objPass++;
            }

            // 2) color blobs at any scale — paint with SUBJECT mean, not whole-screenshot mean
            float tr = tmpl.meanR, tg = tmpl.meanG, tb = tmpl.meanB;
            float thr2 = 62f * 62f;
            int n = sw * sh;
            for (int i = 0; i < n; i++) {
                int p = workRgb[i];
                float dr = ((p >> 16) & 255) - tr;
                float dg = ((p >> 8) & 255) - tg;
                float db = (p & 255) - tb;
                workMask[i] = (dr * dr + dg * dg + db * db < thr2) ? (byte) 1 : 0;
            }
            List<int[]> blobs = cc(workMask, sw, sh);
            objCand += blobs.size();
            for (int[] b : blobs) {
                int bw = b[2], bh = b[3];
                if (bw < 6 || bh < 6) continue;
                int x0 = Math.max(0, b[0] - 1);
                int y0 = Math.max(0, b[1] - 1);
                int x1 = Math.min(sw, b[0] + b[2] + 1);
                int y1 = Math.min(sh, b[1] + b[3] + 1);
                int pw = x1 - x0, ph = y1 - y0;
                int[] patch = new int[pw * ph];
                for (int y = 0; y < ph; y++)
                    System.arraycopy(workRgb, (y0 + y) * sw + x0, patch, y * pw, pw);
                ColorTexture ft = new ColorTexture(patch, pw, ph);
                float score = bestScore(lab, tmpl, ft, heat);
                if (!acceptPatch(tmpl, ft, score, false)) continue;
                Hit h = new Hit(
                        rx + Math.round(x0 * mapX), ry + Math.round(y0 * mapY),
                        Math.round(pw * mapX), Math.round(ph * mapY),
                        lab, score, "object");
                consider(best, h);
                objPass++;
            }

            // 3) detect-time saliency crop — "drop any screenshot, find the focus"
            if (focus != null) {
                int x0 = clamp(focus[0], 0, sw - 4);
                int y0 = clamp(focus[1], 0, sh - 4);
                int x1 = clamp(focus[0] + focus[2], x0 + 4, sw);
                int y1 = clamp(focus[1] + focus[3], y0 + 4, sh);
                int pw = x1 - x0, ph = y1 - y0;
                if (pw >= 8 && ph >= 8) {
                    int[] patch = new int[pw * ph];
                    for (int y = 0; y < ph; y++)
                        System.arraycopy(workRgb, (y0 + y) * sw + x0, patch, y * pw, pw);
                    ColorTexture ft = new ColorTexture(patch, pw, ph);
                    float score = bestScore(lab, tmpl, ft, heat);
                    if (acceptPatch(tmpl, ft, score, false)) {
                        Hit h = new Hit(
                                rx + Math.round(x0 * mapX), ry + Math.round(y0 * mapY),
                                Math.round(pw * mapX), Math.round(ph * mapY),
                                lab, score, "saliency");
                        consider(best, h);
                        objPass++;
                    }
                }
            }
        }
        DetectStats.objCand = objCand;
        DetectStats.objPass = best.size();
        hits.addAll(best.values());
        for (Hit h : hits) {
            DetectStats.addHit(-1, h.conf, 0, 0, h.conf, 0, h.w, h.h, 0,
                    String.format(Locale.US, "%s:%s", h.source, h.label));
        }
        if (motion && lastTracks != null && !lastTracks.isEmpty())
            hits.addAll(localRefine(sw, sh, rx, ry, rw, rh));
        if (onlineAdapt) maybeAdapt(hits, sw, sh, rx, ry, rw, rh);
        return hits;
    }

    private static void consider(Map<String, Hit> best, Hit h) {
        if (h.label == null) return;
        Hit prev = best.get(h.label);
        if (prev == null || h.conf > prev.conf) best.put(h.label, h);
    }

    /**
     * Reject empty / flat / color-only twins. Viewpoint change still passes
     * because color stays and (ncc OR hog OR tex) is an OR.
     */
    static boolean acceptPatch(ColorTexture tmpl, ColorTexture ft, float score, boolean full) {
        if (tmpl == null || ft == null) return false;
        float st = ft.structure();
        if (st < MIN_STRUCTURE) return false;
        float col = tmpl.colorIntersect(ft);
        float ncc = Math.max(0f, ColorTexture.ncc32(tmpl.tpl, ft.tpl));
        float hog = tmpl.hogIntersect(ft);
        float tex = tmpl.texIntersect(ft);
        if (full) {
            return score >= FULL_MIN_SCORE && ncc >= FULL_MIN_NCC
                    && col >= FULL_MIN_COL && st >= MIN_STRUCTURE;
        }
        if (col < MIN_COL) return false;
        if (!(ncc >= MIN_NCC || hog >= MIN_HOG || tex >= MIN_TEX)) return false;
        return score >= MIN_SCORE;
    }

    private float bestScore(String label, ColorTexture base, ColorTexture ft) {
        return bestScore(label, base, ft, null);
    }

    private float bestScore(String label, ColorTexture base, ColorTexture ft, float[] heat) {
        if (base == null || ft == null) return 0f;
        float s = base.scoreAgainst(ft, deform, heat);
        if (onlineAdapt) {
            float a = adapt.score(label, ft);
            if (a > s) s = a;
        }
        return s;
    }

    private void denoiseWork(int sw, int sh) {
        int[] tmp = copyRgb(workRgb, sw, sh);
        for (int y = 1; y < sh - 1; y++) {
            for (int x = 1; x < sw - 1; x++) {
                int sr = 0, sg = 0, sb = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int p = tmp[(y + dy) * sw + x + dx];
                        sr += (p >> 16) & 255;
                        sg += (p >> 8) & 255;
                        sb += p & 255;
                    }
                sr /= 9; sg /= 9; sb /= 9;
                workRgb[y * sw + x] = 0xFF000000 | (sr << 16) | (sg << 8) | sb;
                workGray[y * sw + x] = (byte) ((sr * 30 + sg * 59 + sb * 11) / 100);
            }
        }
    }

    private List<Hit> localRefine(int sw, int sh, int rx, int ry, int rw, int rh) {
        List<Hit> extra = new ArrayList<>();
        if (store.size() == 0) return extra;
        float mapX = rw / (float) sw, mapY = rh / (float) sh;
        for (Hit t : lastTracks) {
            if (t.label == null || "digit".equals(t.source)) continue;
            ObjectStore.Item it = null;
            for (ObjectStore.Item c : store.all()) if (t.label.equals(c.label)) { it = c; break; }
            if (it == null) continue;
            LabelBank.Pack pack = labels.of(t.label);
            if (pack == null) continue;
            ColorTexture tmpl = pack.consensus;
            float[] heat = pack.heat;
            if (tmpl == null) continue;
            int cx = (int) ((t.x + t.w / 2f - rx) / mapX);
            int cy = (int) ((t.y + t.h / 2f - ry) / mapY);
            int ww = Math.max(12, (int) (t.w / mapX));
            int hh = Math.max(12, (int) (t.h / mapY));
            float best = 0;
            ColorTexture bestFt = null;
            int bx = 0, by = 0, bw = ww, bh = hh;
            for (int sc : new int[]{80, 100, 125}) {
                int tw = Math.max(10, ww * sc / 100);
                int th = Math.max(10, hh * sc / 100);
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        int x0 = clamp(cx - tw / 2 + ox * tw / 4, 0, sw - 4);
                        int y0 = clamp(cy - th / 2 + oy * th / 4, 0, sh - 4);
                        int x1 = Math.min(sw, x0 + tw), y1 = Math.min(sh, y0 + th);
                        int pw = x1 - x0, ph = y1 - y0;
                        if (pw < 8 || ph < 8) continue;
                        int[] patch = new int[pw * ph];
                        for (int y = 0; y < ph; y++)
                            System.arraycopy(workRgb, (y0 + y) * sw + x0, patch, y * pw, pw);
                        ColorTexture ft = new ColorTexture(patch, pw, ph);
                        float s = bestScore(t.label, tmpl, ft, heat);
                        if (s > best) { best = s; bestFt = ft; bx = x0; by = y0; bw = pw; bh = ph; }
                    }
                }
            }
            if (bestFt != null && best >= REFINE_MIN && acceptPatch(tmpl, bestFt, best, false))
                extra.add(new Hit(rx + Math.round(bx * mapX), ry + Math.round(by * mapY),
                        Math.round(bw * mapX), Math.round(bh * mapY), t.label, best, "track_refine"));
        }
        return extra;
    }

    private void maybeAdapt(List<Hit> hits, int sw, int sh, int rx, int ry, int rw, int rh) {
        float mapX = rw / (float) Math.max(1, sw), mapY = rh / (float) Math.max(1, sh);
        for (Hit h : hits) {
            if (h.conf < ADAPT_MIN || h.source == null || h.source.startsWith("digit")) continue;
            int x0 = clamp((int) ((h.x - rx) / mapX), 0, sw - 4);
            int y0 = clamp((int) ((h.y - ry) / mapY), 0, sh - 4);
            int x1 = clamp((int) ((h.x + h.w - rx) / mapX), x0 + 4, sw);
            int y1 = clamp((int) ((h.y + h.h - ry) / mapY), y0 + 4, sh);
            int pw = x1 - x0, ph = y1 - y0;
            int[] patch = new int[pw * ph];
            for (int y = 0; y < ph; y++)
                System.arraycopy(workRgb, (y0 + y) * sw + x0, patch, y * pw, pw);
            ColorTexture ft = new ColorTexture(patch, pw, ph);
            if (ft.structure() < 0.14f) continue;
            adapt.update(h.label, ft);
        }
    }

    private static int[] copyRgb(int[] src, int w, int h) {
        int[] o = new int[w * h];
        System.arraycopy(src, 0, o, 0, w * h);
        return o;
    }

    private List<Hit> finalizeHits(List<Hit> out) {
        // one best object box per label (digits may keep several)
        Map<String, Hit> bestObj = new LinkedHashMap<>();
        List<Hit> digitHits = new ArrayList<>();
        for (Hit h : out) {
            if (h.source != null && h.source.startsWith("digit")) {
                digitHits.add(h);
                continue;
            }
            Hit prev = bestObj.get(h.label);
            if (prev == null || h.conf > prev.conf) bestObj.put(h.label, h);
        }
        List<Hit> merged = new ArrayList<>(bestObj.values());
        merged.addAll(digitHits);
        merged = nms(merged, 0.40f);
        Collections.sort(merged, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) { return Float.compare(b.conf, a.conf); }
        });
        if (merged.size() > maxBoxes) merged = new ArrayList<>(merged.subList(0, maxBoxes));
        for (int i = 0; i < merged.size(); i++) merged.get(i).serial = i + 1;
        return merged;
    }

    private List<int[]> cc(byte[] m, int w, int h) {
        int n = w * h;
        for (int i = 0; i < n; i++) workVis[i] = false;
        List<int[]> out = new ArrayList<>();
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy = {0, 0, 1, -1, 1, -1, 1, -1};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (m[idx] == 0 || workVis[idx]) continue;
                int qs = 0, qe = 0;
                qx[qe] = x; qy[qe] = y; qe++;
                workVis[idx] = true;
                int minX = x, maxX = x, minY = y, maxY = y, count = 0;
                while (qs < qe) {
                    int cx = qx[qs], cy = qy[qs];
                    qs++;
                    count++;
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;
                    for (int d = 0; d < 8; d++) {
                        int nx = cx + dx[d], ny = cy + dy[d];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int ni = ny * w + nx;
                        if (m[ni] != 0 && !workVis[ni]) {
                            workVis[ni] = true;
                            qx[qe] = nx; qy[qe] = ny; qe++;
                        }
                    }
                }
                if (count >= 6)
                    out.add(new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1, count});
            }
        }
        return out;
    }

    private static List<Hit> nms(List<Hit> boxes, float iouThr) {
        Collections.sort(boxes, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) { return Float.compare(b.conf, a.conf); }
        });
        List<Hit> keep = new ArrayList<>();
        for (Hit b : boxes) {
            boolean ok = true;
            for (Hit k : keep) {
                int ix0 = Math.max(b.x, k.x), iy0 = Math.max(b.y, k.y);
                int ix1 = Math.min(b.x + b.w, k.x + k.w), iy1 = Math.min(b.y + b.h, k.y + k.h);
                int inter = Math.max(0, ix1 - ix0) * Math.max(0, iy1 - iy0);
                int union = b.w * b.h + k.w * k.h - inter;
                if (union > 0 && inter / (float) union > iouThr) { ok = false; break; }
            }
            if (ok) keep.add(b);
        }
        return keep;
    }

    public List<Hit> detectBitmap(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] rgb = new int[w * h];
        bmp.getPixels(rgb, 0, w, 0, 0, w, h);
        return detect(rgb, null, w, h);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
