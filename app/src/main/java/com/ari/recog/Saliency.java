package com.ari.recog;

import android.graphics.Bitmap;

/**
 * Pull the distinctive object out of a messy screenshot (HUD / UI / borders).
 * Border-contrast × local variance × center bias, then keep the best
 * connected component. Pads ~18% so subject details survive, but never
 * inflates the crop to a quarter of the whole frame (that used to swallow
 * empty panels and teach the detector the background).
 */
public final class Saliency {
    private Saliency() {}

    /** Crop {@code src} to the salient subject. May return {@code src} itself. */
    public static Bitmap focusCrop(Bitmap src) {
        if (src == null) return null;
        int W = src.getWidth(), H = src.getHeight();
        if (W < 16 || H < 16) return src;
        int[] r = focusRect(src);
        if (r == null) return src;
        int x0 = r[0], y0 = r[1], cw = r[2], ch = r[3];
        if (cw >= W - 2 && ch >= H - 2) return src;
        if (cw < 8 || ch < 8) return src;
        DebugLog.d("Saliency crop " + W + "x" + H + " -> " + cw + "x" + ch
                + " @(" + x0 + "," + y0 + ")");
        return Bitmap.createBitmap(src, x0, y0, cw, ch);
    }

    /** {@code {x,y,w,h}} in bitmap pixels, or null. */
    public static int[] focusRect(Bitmap src) {
        if (src == null) return null;
        int W = src.getWidth(), H = src.getHeight();
        int sw = 64, sh = Math.max(16, H * 64 / Math.max(1, W));
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (small != src) small.recycle();
        int[] r = focusRectOnMap(px, sw, sh);
        if (r == null) return null;
        return mapRect(r, sw, sh, W, H);
    }

    /**
     * Work-canvas entry: {@code rgb} is ARGB, size {@code w*h}.
     * Returns {@code {x,y,bw,bh}} in the same coordinate system, or null.
     */
    public static int[] focusRect(int[] rgb, int w, int h) {
        if (rgb == null || w < 16 || h < 16) return null;
        int sw = 64, sh = Math.max(16, h * 64 / Math.max(1, w));
        int[] small = downsample(rgb, w, h, sw, sh);
        int[] r = focusRectOnMap(small, sw, sh);
        if (r == null) return null;
        return mapRect(r, sw, sh, w, h);
    }

    private static int[] downsample(int[] src, int w, int h, int sw, int sh) {
        int[] out = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            int sy = Math.min(h - 1, (int) ((y + 0.5f) * h / sh));
            for (int x = 0; x < sw; x++) {
                int sx = Math.min(w - 1, (int) ((x + 0.5f) * w / sw));
                out[y * sw + x] = src[sy * w + sx];
            }
        }
        return out;
    }

    private static int[] mapRect(int[] r, int sw, int sh, int W, int H) {
        int x0 = r[0] * W / sw;
        int y0 = r[1] * H / sh;
        int x1 = Math.min(W, (r[0] + r[2]) * W / sw);
        int y1 = Math.min(H, (r[1] + r[3]) * H / sh);
        if (x1 - x0 < 8 || y1 - y0 < 8) return null;
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /** Best CC on a small score map. Returns {minX,minY,bw,bh} in map pixels. */
    private static int[] focusRectOnMap(int[] px, int sw, int sh) {
        float br = 0, bg = 0, bb = 0;
        int bc = 0;
        for (int x = 0; x < sw; x++) {
            int p0 = px[x], p1 = px[(sh - 1) * sw + x];
            br += ((p0 >> 16) & 255) + ((p1 >> 16) & 255);
            bg += ((p0 >> 8) & 255) + ((p1 >> 8) & 255);
            bb += (p0 & 255) + (p1 & 255);
            bc += 2;
        }
        for (int y = 1; y < sh - 1; y++) {
            int p0 = px[y * sw], p1 = px[y * sw + sw - 1];
            br += ((p0 >> 16) & 255) + ((p1 >> 16) & 255);
            bg += ((p0 >> 8) & 255) + ((p1 >> 8) & 255);
            bb += (p0 & 255) + (p1 & 255);
            bc += 2;
        }
        if (bc <= 0) return null;
        br /= bc; bg /= bc; bb /= bc;

        float[] score = new float[sw * sh];
        float maxS = 0;
        for (int y = 1; y < sh - 1; y++) {
            for (int x = 1; x < sw - 1; x++) {
                int p = px[y * sw + x];
                float r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                float dist = Math.abs(r - br) + Math.abs(g - bg) + Math.abs(b - bb);
                int c = (int) (0.3f * r + 0.59f * g + 0.11f * b);
                int var = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int q = px[(y + dy) * sw + x + dx];
                        int gc = (((q >> 16) & 255) * 30 + ((q >> 8) & 255) * 59 + (q & 255) * 11) / 100;
                        int d = gc - c;
                        var += d * d;
                    }
                float s = dist * (float) Math.sqrt(var / 9f);
                float cx = (x - sw / 2f) / (sw / 2f);
                float cy = (y - sh / 2f) / (sh / 2f);
                s *= (1.15f - 0.35f * (cx * cx + cy * cy));
                score[y * sw + x] = s;
                if (s > maxS) maxS = s;
            }
        }
        if (maxS < 1e-3f) return null;
        float thr = maxS * 0.42f;

        boolean[] vis = new boolean[sw * sh];
        int[] qx = new int[sw * sh];
        int[] qy = new int[sw * sh];
        float bestVal = -1;
        int bMinX = 0, bMinY = 0, bMaxX = 0, bMaxY = 0;
        boolean found = false;
        int[] dx8 = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy8 = {0, 0, 1, -1, 1, -1, 1, -1};
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int idx = y * sw + x;
                if (score[idx] < thr || vis[idx]) continue;
                int qs = 0, qe = 0;
                qx[qe] = x; qy[qe] = y; qe++;
                vis[idx] = true;
                int minX = x, maxX = x, minY = y, maxY = y, count = 0, borderN = 0;
                float ssum = 0;
                while (qs < qe) {
                    int cx = qx[qs], cy = qy[qs];
                    qs++;
                    count++;
                    ssum += score[cy * sw + cx];
                    if (cx == 0 || cy == 0 || cx == sw - 1 || cy == sh - 1) borderN++;
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;
                    for (int d = 0; d < 8; d++) {
                        int nx = cx + dx8[d], ny = cy + dy8[d];
                        if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) continue;
                        int ni = ny * sw + nx;
                        if (score[ni] >= thr && !vis[ni]) {
                            vis[ni] = true;
                            qx[qe] = nx; qy[qe] = ny; qe++;
                        }
                    }
                }
                if (count < 8) continue;
                float ccx = 0.5f * (minX + maxX);
                float ccy = 0.5f * (minY + maxY);
                float ncx = (ccx - sw / 2f) / (sw / 2f);
                float ncy = (ccy - sh / 2f) / (sh / 2f);
                float center = 1.15f - 0.35f * (ncx * ncx + ncy * ncy);
                float bfrac = borderN / (float) Math.max(1, count);
                float val = ssum * center * (1f - 0.65f * bfrac) * (float) Math.sqrt(count);
                if (val > bestVal) {
                    bestVal = val;
                    bMinX = minX; bMinY = minY; bMaxX = maxX; bMaxY = maxY;
                    found = true;
                }
            }
        }
        if (!found) return null;
        int bw = bMaxX - bMinX + 1, bh = bMaxY - bMinY + 1;
        int pad = Math.max(2, (int) (0.18f * Math.max(bw, bh)));
        bMinX = Math.max(0, bMinX - pad);
        bMinY = Math.max(0, bMinY - pad);
        bMaxX = Math.min(sw - 1, bMaxX + pad);
        bMaxY = Math.min(sh - 1, bMaxY + pad);
        if ((bMaxX - bMinX + 1) < 10 || (bMaxY - bMinY + 1) < 10) {
            int cx = (bMinX + bMaxX) / 2, cy = (bMinY + bMaxY) / 2;
            bMinX = Math.max(0, cx - 8);
            bMaxX = Math.min(sw - 1, cx + 8);
            bMinY = Math.max(0, cy - 8);
            bMaxY = Math.min(sh - 1, cy + 8);
        }
        return new int[]{bMinX, bMinY, bMaxX - bMinX + 1, bMaxY - bMinY + 1};
    }
}
