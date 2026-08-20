package com.ari.recog;

/**
 * DigitPreprocessor — convert an arbitrary gray patch into a MNIST-style
 * 28×28 white-on-black vector (0..255). This is the missing piece that made
 * live screen recognition always report 0 targets: the classifier was fed
 * raw RGB red-channel crops with the wrong polarity and no centering.
 */
public final class DigitPreprocessor {

    private DigitPreprocessor() {}

    /** Gray patch, values 0..255. Returns length-784 float 0..255. */
    public static float[] toMnist28(float[][] patch) {
        int h = patch.length;
        int w = h == 0 ? 0 : patch[0].length;
        if (h < 2 || w < 2) return new float[784];

        float[][] g = new float[h][w];
        float maxv = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float v = patch[y][x];
                g[y][x] = v;
                if (v > maxv) maxv = v;
            }
        if (maxv > 1.5f) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) g[y][x] /= 255f;
        }

        // Polarity: MNIST is bright ink on dark paper.
        float border = 0;
        int bc = 0;
        for (int x = 0; x < w; x++) { border += g[0][x] + g[h - 1][x]; bc += 2; }
        for (int y = 1; y < h - 1; y++) { border += g[y][0] + g[y][w - 1]; bc += 2; }
        border /= Math.max(1, bc);
        int y0i = h / 4, y1i = Math.max(y0i + 1, 3 * h / 4);
        int x0i = w / 4, x1i = Math.max(x0i + 1, 3 * w / 4);
        float inner = 0;
        int ic = 0;
        for (int y = y0i; y < y1i; y++)
            for (int x = x0i; x < x1i; x++) { inner += g[y][x]; ic++; }
        inner /= Math.max(1, ic);
        if (border > inner) invert(g);

        contrastStretch(g);

        boolean[][] ink = threshold(g, 0.28f);
        int inkN = count(ink);
        if (inkN < 6) {
            invert(g);
            boolean[][] ink2 = threshold(g, 0.28f);
            int n2 = count(ink2);
            if (n2 > inkN) { ink = ink2; inkN = n2; }
            else invert(g);
        }
        if (inkN < 4) return flatten(resize(g, 28, 28), 255f);

        int minX = w, minY = h, maxX = 0, maxY = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (ink[y][x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        minX = Math.max(0, minX - 1);
        minY = Math.max(0, minY - 1);
        maxX = Math.min(w - 1, maxX + 1);
        maxY = Math.min(h - 1, maxY + 1);
        int ch = maxY - minY + 1, cw = maxX - minX + 1;
        float[][] crop = new float[ch][cw];
        for (int y = 0; y < ch; y++)
            System.arraycopy(g[minY + y], minX, crop[y], 0, cw);

        float scale = 20f / Math.max(ch, cw);
        int nh = Math.max(1, Math.round(ch * scale));
        int nw = Math.max(1, Math.round(cw * scale));
        float[][] small = resize(crop, nh, nw);

        float mass = 1e-6f, cx = 0, cy = 0;
        for (int y = 0; y < nh; y++)
            for (int x = 0; x < nw; x++) {
                float v = small[y][x];
                mass += v;
                cx += v * x;
                cy += v * y;
            }
        cx /= mass;
        cy /= mass;
        int ty = clamp(Math.round(14 - cy), 0, 28 - nh);
        int tx = clamp(Math.round(14 - cx), 0, 28 - nw);
        float[][] canvas = new float[28][28];
        for (int y = 0; y < nh; y++)
            System.arraycopy(small[y], 0, canvas[ty + y], tx, nw);
        return flatten(canvas, 255f);
    }

    /** Convenience: packed gray 0..255 row-major. */
    public static float[] toMnist28(float[] packed, int w, int h) {
        float[][] p = new float[h][w];
        for (int y = 0; y < h; y++)
            System.arraycopy(packed, y * w, p[y], 0, w);
        return toMnist28(p);
    }

    public static class InkStats {
        public final float ratio, aspect, contrast;
        public InkStats(float r, float a, float c) { ratio = r; aspect = a; contrast = c; }
    }

    public static InkStats inkStats(float[] img784) {
        int n = 0, minX = 28, minY = 28, maxX = 0, maxY = 0;
        float mn = 1, mx = 0;
        for (int i = 0; i < 784; i++) {
            float v = img784[i] / 255f;
            if (v < mn) mn = v;
            if (v > mx) mx = v;
            if (v > 0.25f) {
                n++;
                int x = i % 28, y = i / 28;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        float ratio = n / 784f;
        float aspect = n == 0 ? 0 : (maxX - minX + 1) / (float) Math.max(1, maxY - minY + 1);
        return new InkStats(ratio, aspect, mx - mn);
    }

    private static void invert(float[][] g) {
        for (int y = 0; y < g.length; y++)
            for (int x = 0; x < g[0].length; x++) g[y][x] = 1f - g[y][x];
    }

    private static void contrastStretch(float[][] g) {
        int h = g.length, w = g[0].length;
        float[] flat = new float[h * w];
        int k = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) flat[k++] = g[y][x];
        java.util.Arrays.sort(flat);
        float p2 = flat[(int) (flat.length * 0.02)];
        float p98 = flat[(int) (flat.length * 0.98)];
        if (p98 - p2 < 1e-4f) return;
        float inv = 1f / (p98 - p2);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float v = (g[y][x] - p2) * inv;
                g[y][x] = v < 0 ? 0 : (v > 1 ? 1 : v);
            }
    }

    private static boolean[][] threshold(float[][] g, float t) {
        int h = g.length, w = g[0].length;
        boolean[][] out = new boolean[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) out[y][x] = g[y][x] > t;
        return out;
    }

    private static int count(boolean[][] a) {
        int n = 0;
        for (boolean[] row : a)
            for (boolean v : row) if (v) n++;
        return n;
    }

    /** Bilinear resize. Input assumed 0..1. */
    public static float[][] resize(float[][] src, int nh, int nw) {
        int h = src.length, w = src[0].length;
        float[][] out = new float[nh][nw];
        if (h == nh && w == nw) {
            for (int y = 0; y < h; y++) System.arraycopy(src[y], 0, out[y], 0, w);
            return out;
        }
        for (int y = 0; y < nh; y++) {
            float gy = (y + 0.5f) * h / nh - 0.5f;
            int y0 = (int) Math.floor(gy);
            int y1 = y0 + 1;
            float fy = gy - y0;
            if (y0 < 0) { y0 = 0; fy = 0; }
            if (y1 >= h) y1 = h - 1;
            for (int x = 0; x < nw; x++) {
                float gx = (x + 0.5f) * w / nw - 0.5f;
                int x0 = (int) Math.floor(gx);
                int x1 = x0 + 1;
                float fx = gx - x0;
                if (x0 < 0) { x0 = 0; fx = 0; }
                if (x1 >= w) x1 = w - 1;
                float v00 = src[y0][x0], v01 = src[y0][x1];
                float v10 = src[y1][x0], v11 = src[y1][x1];
                out[y][x] = (v00 * (1 - fx) + v01 * fx) * (1 - fy)
                        + (v10 * (1 - fx) + v11 * fx) * fy;
            }
        }
        return out;
    }

    private static float[] flatten(float[][] g, float scale) {
        int h = g.length, w = g[0].length;
        float[] out = new float[h * w];
        int i = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) out[i++] = g[y][x] * scale;
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
