package com.ari.recog;

import android.content.Context;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * DigitDetector — multi-strategy screen digit finder.
 *
 *   1. Adaptive + Otsu binarization, both polarities
 *   2. Connected components with digit-shaped filters
 *   3. Vertical-projection split of wide (multi-digit) blobs
 *   4. MNIST preprocessor + ARI classifier + printed-font NCC
 *   5. Score fusion + NMS
 *
 * Replaces the old "gray in [70,250] is foreground" blobber that marked the
 * entire phone UI as one component and therefore always reported 0 targets.
 */
public final class DigitDetector {

    public static final class Hit {
        public final int x, y, w, h;
        public final int digit;
        public final float conf;
        public final float margin;
        public final String source;
        public int serial;
        public Hit(int x, int y, int w, int h, int digit, float conf, float margin, String src) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.digit = digit; this.conf = conf; this.margin = margin; this.source = src;
        }
    }

    private final ARIClassifier clf;
    private final TemplateBank bank;
    private float minConf = 0.48f;
    private float minNcc = 0.70f;
    private int minH = 10, minW = 6, maxKeep = 8;
    private int level = 1;

    public DigitDetector(Context ctx) {
        this.clf = ImageEngine.getClassifier(ctx);
        this.bank = new TemplateBank(ctx);
        applySensitivity(SettingsActivity.getSensitivity(ctx));
    }

    public void applySensitivity(int lv) {
        level = lv;
        // Size is not a hard reject (arbitrary scale). Dust only.
        if (lv <= 0) { minConf = 0.52f; minNcc = 0.74f; minH = 10; minW = 6; maxKeep = 8; }
        else if (lv >= 2) { minConf = 0.44f; minNcc = 0.66f; minH = 10; minW = 6; maxKeep = 10; }
        else { minConf = 0.48f; minNcc = 0.70f; minH = 10; minW = 6; maxKeep = 8; }
    }

    /** RGBA_8888 ImageReader buffer → detections. */
    public List<Hit> detectRgba(ByteBuffer buffer, int rowStride, int w, int h) {
        byte[] gray = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * rowStride;
            int dst = y * w;
            for (int x = 0; x < w; x++) {
                int off = row + x * 4;
                int r = buffer.get(off) & 0xFF;
                int g = buffer.get(off + 1) & 0xFF;
                int b = buffer.get(off + 2) & 0xFF;
                gray[dst + x] = (byte) ((r * 30 + g * 59 + b * 11) / 100);
            }
        }
        return detectGray(gray, w, h);
    }

    public List<Hit> detectGray(byte[] gray, int w, int h) {
        long t0 = android.os.SystemClock.uptimeMillis();
        List<int[]> cands = candidates(gray, w, h);
        List<Hit> hits = new ArrayList<>();
        int rejInk = 0, rejGate = 0, rejTiny = 0;
        for (int[] c : cands) {
            int x = c[0], y = c[1], bw = c[2], bh = c[3];
            int pad = Math.max(2, (int) (0.16f * Math.max(bw, bh)));
            int x0 = Math.max(0, x - pad);
            int y0 = Math.max(0, y - pad);
            int x1 = Math.min(w, x + bw + pad);
            int y1 = Math.min(h, y + bh + pad);
            int pw = x1 - x0, ph = y1 - y0;
            if (ph < minH || pw < minW) { rejTiny++; continue; }
            float[][] patch = extract(gray, w, h, x0, y0, pw, ph);
            float[] feat = DigitPreprocessor.toMnist28(patch);
            DigitPreprocessor.InkStats st = DigitPreprocessor.inkStats(feat);
            if (st.ratio < 0.05f || st.ratio > 0.55f || st.contrast < 0.22f) { rejInk++; continue; }
            if (st.aspect < 0.18f || st.aspect > 1.45f) { rejInk++; continue; }

            ARIClassifier.Result ari = clf.predictFull(feat);
            TemplateBank.Match ncc = bank.match(feat);
            int crowd = lineCrowding(gray, w, h, x0, y0, pw, ph);
            int digit = -1;
            float score = 0;
            String src = "";
            boolean agree = ari.digit == ncc.digit;
            float nNeed = minNcc, gNeed = (level <= 0 ? 0.10f : level >= 2 ? 0.06f : 0.08f);
            float aNeed = minConf;
            if (crowd >= 8) { nNeed += 0.12f; gNeed += 0.08f; aNeed += 0.08f; }
            else if (crowd >= 5) { nNeed += 0.06f; gNeed += 0.04f; }
            if (ph < 22) { nNeed += 0.05f; gNeed += 0.04f; }

            if (ph >= 56 && ari.conf >= (level > 0 ? 0.50f : 0.56f) && ari.margin >= 0.28f
                    && (agree || ncc.ncc >= 0.50f)) {
                digit = ari.digit;
                score = Math.max(ari.conf, ncc.ncc);
                src = "ari_big";
            } else if (agree && ncc.ncc >= nNeed && ncc.gap >= gNeed && ari.conf >= aNeed) {
                digit = ari.digit;
                score = 0.40f * ncc.ncc + 0.25f * Math.min(1f, ncc.gap / 0.25f) + 0.20f * ari.conf + 0.15f;
                src = "both";
            } else if (crowd <= 4 && ncc.ncc >= (level <= 0 ? 0.92f : 0.90f) && ncc.gap >= 0.14f && ph >= 20) {
                digit = ncc.digit;
                score = 0.55f + 0.45f * ncc.ncc;
                src = "ncc";
            }
            if (digit < 0) { rejGate++; continue; }
            Hit hit = new Hit(x0, y0, pw, ph, digit, score, ari.margin, src);
            hits.add(hit);
            DetectStats.addHit(digit, score, ncc.ncc, ncc.gap, ari.conf, ari.margin, pw, ph, crowd, src);
        }
        hits = nms(hits, 0.28f);
        if (hits.size() > maxKeep) hits = new ArrayList<>(hits.subList(0, maxKeep));
        for (int i = 0; i < hits.size(); i++) hits.get(i).serial = i + 1;
        long ms = android.os.SystemClock.uptimeMillis() - t0;
        DetectStats.finishFrame(cands.size(), hits.size(), rejInk, rejGate, rejTiny, ms, level);
        return hits;
    }

    /** Count similar-height ink runs on the same baseline — letters in a word score high. */
    static int lineCrowding(byte[] gray, int W, int H, int x, int y, int w, int h) {
        int y0 = Math.max(0, y), y1 = Math.min(H, y + h);
        if (y1 - y0 < 4) return 0;
        float[] col = new float[W];
        // median of band via histogram
        int[] hist = new int[256];
        int cnt = 0;
        for (int yy = y0; yy < y1; yy++) {
            int row = yy * W;
            for (int xx = 0; xx < W; xx++) { hist[gray[row + xx] & 0xFF]++; cnt++; }
        }
        int mid = cnt / 2, acc = 0, med = 128;
        for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc >= mid) { med = i; break; } }
        for (int yy = y0; yy < y1; yy++) {
            int row = yy * W;
            for (int xx = 0; xx < W; xx++) {
                int v = gray[row + xx] & 0xFF;
                boolean ink = med > 127 ? v < med - 22 : v > med + 22;
                if (ink) col[xx] += 1f;
            }
        }
        float inv = 1f / (y1 - y0);
        float mx = 0;
        for (int i = 0; i < W; i++) { col[i] *= inv; if (col[i] > mx) mx = col[i]; }
        float thr = Math.max(0.12f, 0.30f * mx);
        int n = 0, i = 0, minw = Math.max(3, (int) (h * 0.18f));
        while (i < W) {
            while (i < W && col[i] < thr) i++;
            int s = i;
            while (i < W && col[i] >= thr) i++;
            if (i - s >= minw) n++;
        }
        return n;
    }

    private List<int[]> candidates(byte[] gray, int W, int H) {
        int ds = 2; // keep small digits (18–28px) alive
        int sw = W / ds, sh = H / ds;
        byte[] small = new byte[sw * sh];
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++)
                small[y * sw + x] = gray[(y * ds) * W + (x * ds)];

        List<int[]> raw = new ArrayList<>();
        for (boolean inv : new boolean[]{false, true}) {
            collect(raw, adaptiveBinary(small, sw, sh, inv, 15, 9f), sw, sh, ds, W, H);
            collect(raw, otsuBinary(small, sw, sh, inv), sw, sh, ds, W, H);
        }

        List<int[]> cand = new ArrayList<>();
        for (int[] r : raw) {
            int X = r[0], Y = r[1], BW = r[2], BH = r[3], count = r[4];
            if (BW > W * 0.55f || BH > H * 0.42f) continue;
            if (BH < 10 || BW < 6) continue;
            if (BW >= (int) (BH * 0.85f) && BH >= 36) {
                cand.addAll(splitWide(gray, W, H, X, Y, BW, BH));
            } else if (isDigitLike(BW, BH, count, W, H)) {
                cand.add(new int[]{X, Y, BW, BH});
            }
        }
        // unique
        List<int[]> uniq = new ArrayList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int[] c : cand) {
            long key = ((c[0] / 4L) << 36) | ((c[1] / 4L) << 24) | ((c[2] / 6L) << 12) | (c[3] / 6L);
            if (seen.add(key)) uniq.add(c);
        }
        return uniq;
    }

    private void collect(List<int[]> raw, byte[] binary, int sw, int sh, int ds, int W, int H) {
        morphClose(binary, sw, sh, 1);
        for (int[] cc : connected(binary, sw, sh)) {
            raw.add(new int[]{cc[0] * ds, cc[1] * ds, cc[2] * ds, cc[3] * ds, cc[4] * ds * ds});
        }
    }

    private List<int[]> splitWide(byte[] gray, int imgW, int imgH, int x, int y, int bw, int bh) {
        List<int[]> out = new ArrayList<>();
        if (bh < 10 || y + bh > imgH || x + bw > imgW) {
            if (isDigitLike(bw, bh, bw * bh / 2, imgW, imgH)) out.add(new int[]{x, y, bw, bh});
            return out;
        }
        float[] proj = new float[bw];
        float border = 0;
        int bc = 0;
        for (int i = 0; i < bw; i++) {
            border += (gray[y * imgW + x + i] & 0xFF);
            border += (gray[(y + bh - 1) * imgW + x + i] & 0xFF);
            bc += 2;
        }
        for (int j = 1; j < bh - 1; j++) {
            border += (gray[(y + j) * imgW + x] & 0xFF);
            border += (gray[(y + j) * imgW + x + bw - 1] & 0xFF);
            bc += 2;
        }
        border /= Math.max(1, bc);
        float mean = 0;
        for (int j = 0; j < bh; j++)
            for (int i = 0; i < bw; i++) mean += gray[(y + j) * imgW + x + i] & 0xFF;
        mean /= (bw * bh);
        boolean darkInk = border > mean;
        int inkN = 0;
        for (int i = 0; i < bw; i++) {
            int col = 0;
            for (int j = 0; j < bh; j++) {
                int v = gray[(y + j) * imgW + x + i] & 0xFF;
                boolean ink = darkInk ? v < border - 18 : v > border + 18;
                if (ink) { col++; inkN++; }
            }
            proj[i] = col / (float) bh;
        }
        float pmax = 0;
        for (float v : proj) if (v > pmax) pmax = v;
        if (pmax < 0.08f) {
            if (isDigitLike(bw, bh, inkN, imgW, imgH)) out.add(new int[]{x, y, bw, bh});
            return out;
        }
        float thr = Math.max(0.06f, 0.28f * pmax);
        int minW = Math.max(6, (int) (bh * 0.22f));
        int maxW = (int) (bh * 1.35f);
        List<int[]> segs = new ArrayList<>();
        int i = 0;
        while (i < bw) {
            while (i < bw && proj[i] < thr) i++;
            int s = i;
            while (i < bw && proj[i] >= thr) i++;
            int e = i;
            if (e - s >= minW) segs.add(new int[]{s, e});
        }
        if (segs.size() <= 1 && bw > bh * 1.4f) {
            int est = Math.max(2, Math.round(bw / Math.max(bh * 0.62f, 1)));
            est = Math.min(est, 8);
            float sw = bw / (float) est;
            for (int k = 0; k < est; k++) {
                int sx = x + (int) (k * sw);
                int swi = (int) sw;
                if (isDigitLike(swi, bh, swi * bh / 2, imgW, imgH))
                    out.add(new int[]{sx, y, swi, bh});
            }
            return out;
        }
        for (int[] seg : segs) {
            int ww = seg[1] - seg[0];
            if (ww > maxW) {
                int est = Math.max(2, Math.round(ww / Math.max(bh * 0.62f, 1)));
                float sw = ww / (float) est;
                for (int k = 0; k < est; k++)
                    out.add(new int[]{x + seg[0] + (int) (k * sw), y, (int) sw, bh});
            } else {
                out.add(new int[]{x + seg[0], y, ww, bh});
            }
        }
        if (out.isEmpty() && isDigitLike(bw, bh, inkN, imgW, imgH))
            out.add(new int[]{x, y, bw, bh});
        return out;
    }

    private static boolean isDigitLike(int bw, int bh, int count, int imgW, int imgH) {
        if (bw < 7 || bh < 10) return false;
        if (bw > imgW * 0.42f || bh > imgH * 0.38f) return false;
        int area = bw * bh;
        if (area < 70 || area > imgW * imgH * 0.08f) return false;
        float aspect = bw / (float) Math.max(1, bh);
        if (aspect < 0.16f || aspect > 1.6f) return false;
        float fill = count / (float) Math.max(1, area);
        return fill >= 0.10f && fill <= 0.88f;
    }

    // ----- binarization -----
    private static byte[] adaptiveBinary(byte[] gray, int w, int h, boolean invert, int win, float c) {
        float[] g = new float[w * h];
        for (int i = 0; i < g.length; i++) {
            float v = gray[i] & 0xFF;
            g[i] = invert ? 255f - v : v;
        }
        float[] local = boxFilter(g, w, h, win);
        byte[] out = new byte[w * h];
        for (int i = 0; i < g.length; i++)
            out[i] = (byte) (g[i] < local[i] - c ? 1 : 0);
        return out;
    }

    private static float[] boxFilter(float[] g, int w, int h, int win) {
        int r = win / 2;
        float[] tmp = new float[(h) * (w + 1)];
        // horizontal prefix per row (w+1)
        for (int y = 0; y < h; y++) {
            int row = y * (w + 1);
            tmp[row] = 0;
            int src = y * w;
            float acc = 0;
            for (int x = 0; x < w; x++) {
                acc += g[src + x];
                tmp[row + x + 1] = acc;
            }
        }
        float[] horiz = new float[h * w];
        for (int y = 0; y < h; y++) {
            int row = y * (w + 1);
            int dst = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r);
                int x1 = Math.min(w, x + r + 1);
                horiz[dst + x] = tmp[row + x1] - tmp[row + x0];
            }
        }
        // vertical prefix
        float[] colp = new float[(h + 1) * w];
        for (int x = 0; x < w; x++) colp[x] = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++)
                colp[(y + 1) * w + x] = colp[y * w + x] + horiz[y * w + x];
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r);
            int y1 = Math.min(h, y + r + 1);
            int area = (y1 - y0); // times (x1-x0) later
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r);
                int x1 = Math.min(w, x + r + 1);
                // we already summed horizontally with variable width; approximate with stored horiz width
                // Use the vertical of horiz which already is local horizontal sum of (2r+1 or edge)
                float s = colp[y1 * w + x] - colp[y0 * w + x];
                int ww = Math.min(w, x + r + 1) - Math.max(0, x - r);
                out[y * w + x] = s / (area * ww);
            }
        }
        return out;
    }

    private static byte[] otsuBinary(byte[] gray, int w, int h, boolean invert) {
        int[] hist = new int[256];
        for (byte b : gray) hist[b & 0xFF]++;
        int total = w * h;
        float sum = 0;
        for (int i = 0; i < 256; i++) sum += i * hist[i];
        float sumB = 0;
        int wB = 0;
        float maxVar = -1;
        int thr = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += t * hist[t];
            float mB = sumB / wB;
            float mF = (sum - sumB) / wF;
            float var = wB * (float) wF * (mB - mF) * (mB - mF);
            if (var > maxVar) { maxVar = var; thr = t; }
        }
        byte[] out = new byte[total];
        for (int i = 0; i < total; i++) {
            int v = gray[i] & 0xFF;
            boolean ink = invert ? v >= thr : v < thr;
            out[i] = (byte) (ink ? 1 : 0);
        }
        return out;
    }

    private static void morphClose(byte[] b, int w, int h, int k) {
        byte[] cur = b;
        for (int n = 0; n < k; n++) cur = dilate(cur, w, h);
        for (int n = 0; n < k; n++) cur = erode(cur, w, h);
        if (cur != b) System.arraycopy(cur, 0, b, 0, b.length);
    }

    private static byte[] dilate(byte[] b, int w, int h) {
        byte[] o = new byte[b.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean on = false;
                for (int dy = -1; dy <= 1 && !on; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        if (b[ny * w + nx] != 0) { on = true; break; }
                    }
                }
                o[y * w + x] = (byte) (on ? 1 : 0);
            }
        }
        return o;
    }

    private static byte[] erode(byte[] b, int w, int h) {
        byte[] o = new byte[b.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean on = true;
                for (int dy = -1; dy <= 1 && on; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) { on = false; break; }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w || b[ny * w + nx] == 0) { on = false; break; }
                    }
                }
                o[y * w + x] = (byte) (on ? 1 : 0);
            }
        }
        return o;
    }

    private static List<int[]> connected(byte[] b, int w, int h) {
        boolean[] vis = new boolean[w * h];
        List<int[]> comps = new ArrayList<>();
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        int[] qx = new int[w * h];
        int[] qy = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (b[idx] == 0 || vis[idx]) continue;
                int qs = 0, qe = 0;
                qx[qe] = x; qy[qe] = y; qe++;
                vis[idx] = true;
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
                        if (b[ni] != 0 && !vis[ni]) {
                            vis[ni] = true;
                            qx[qe] = nx; qy[qe] = ny; qe++;
                        }
                    }
                }
                comps.add(new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1, count});
            }
        }
        return comps;
    }

    private static float[][] extract(byte[] gray, int W, int H, int x0, int y0, int bw, int bh) {
        float[][] p = new float[bh][bw];
        for (int y = 0; y < bh; y++) {
            int yy = y0 + y;
            if (yy < 0 || yy >= H) continue;
            int row = yy * W;
            for (int x = 0; x < bw; x++) {
                int xx = x0 + x;
                if (xx < 0 || xx >= W) continue;
                p[y][x] = gray[row + xx] & 0xFF;
            }
        }
        return p;
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
                if (b.x >= k.x && b.y >= k.y && b.x + b.w <= k.x + k.w && b.y + b.h <= k.y + k.h) {
                    ok = false; break;
                }
            }
            if (ok) keep.add(b);
        }
        return keep;
    }
}
