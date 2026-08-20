package com.ari.recog;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Same label = same object. Merge every image of a label into one consensus
 * template: keep colors that appear in all shots (intersection), average
 * structure, and build a 32×32 heatmap that down-weights pixels that
 * disagree across shots (background noise).
 */
public final class LabelBank {

    public static final class Pack {
        public final String label;
        public final ColorTexture consensus;
        public final float[] autoHeat;
        public final float[] heat; // user overlay if any, else auto
        public final int n;
        public final boolean userHeat;

        Pack(String label, ColorTexture c, float[] auto, float[] used, int n, boolean user) {
            this.label = label;
            this.consensus = c;
            this.autoHeat = auto;
            this.heat = used;
            this.n = n;
            this.userHeat = user;
        }

        public String formula(boolean deform) {
            return ColorTexture.formulaOf(consensus, heat, deform, n);
        }
    }

    private final ObjectStore store;
    private final HeatmapStore heats;
    private final Map<String, Pack> cache = new HashMap<>();
    private int lastN = -1;
    private int lastHeatGen = -1;

    public LabelBank(Context ctx, ObjectStore store) {
        this.store = store;
        this.heats = new HeatmapStore(ctx);
    }

    public HeatmapStore heats() { return heats; }

    public synchronized void invalidate() { cache.clear(); lastN = -1; }

    public synchronized Pack of(String label) {
        maybeFlush();
        Pack p = cache.get(label);
        if (p != null) return p;
        p = build(label);
        if (p != null) cache.put(label, p);
        return p;
    }

    private void maybeFlush() {
        if (store.size() != lastN || heats.generation() != lastHeatGen) {
            cache.clear();
            lastN = store.size();
            lastHeatGen = heats.generation();
        }
    }

    private Pack build(String label) {
        List<ObjectStore.Item> its = store.itemsOf(label);
        if (its.isEmpty()) return null;
        List<ColorTexture> feats = new ArrayList<>();
        for (ObjectStore.Item it : its) {
            ColorTexture f = store.subjectFeatures(it);
            if (f != null) feats.add(f);
        }
        if (feats.isEmpty()) return null;
        ColorTexture cons = merge(feats);
        float[] auto = autoHeat(feats);
        float[] user = heats.load(label);
        boolean hasUser = user != null;
        float[] used = hasUser ? user : auto;
        DebugLog.d("LabelBank " + label + " n=" + feats.size() + (hasUser ? " user-heat" : " auto-heat"));
        return new Pack(label, cons, auto, used, feats.size(), hasUser);
    }

    /** Intersection HSV (common color) + averaged structure. */
    static ColorTexture merge(List<ColorTexture> feats) {
        int n = feats.size();
        ColorTexture a = feats.get(0);
        float[] hsv = a.hsv.clone();
        float[] lbp = new float[ColorTexture.LBP];
        float[] hog = new float[ColorTexture.HOG];
        float[] grid = new float[ColorTexture.GRID];
        float[] tpl = new float[ColorTexture.TPL * ColorTexture.TPL];
        float mr = 0, mg = 0, mb = 0;
        for (ColorTexture f : feats) {
            for (int i = 0; i < hsv.length; i++) hsv[i] = Math.min(hsv[i], f.hsv[i]);
            for (int i = 0; i < lbp.length; i++) lbp[i] += f.lbp[i];
            for (int i = 0; i < hog.length; i++) hog[i] += f.hog[i];
            for (int i = 0; i < grid.length; i++) grid[i] += f.grid[i];
            for (int i = 0; i < tpl.length; i++) tpl[i] += f.tpl[i];
            mr += f.meanR; mg += f.meanG; mb += f.meanB;
        }
        float hs = 0;
        for (float v : hsv) hs += v;
        if (hs > 1e-6f) for (int i = 0; i < hsv.length; i++) hsv[i] /= hs;
        float inv = 1f / n;
        for (int i = 0; i < lbp.length; i++) lbp[i] *= inv;
        for (int i = 0; i < hog.length; i++) hog[i] *= inv;
        for (int i = 0; i < grid.length; i++) grid[i] *= inv;
        for (int i = 0; i < tpl.length; i++) tpl[i] *= inv;
        return new ColorTexture(hsv, lbp, hog, grid, tpl, mr * inv, mg * inv, mb * inv);
    }

    /**
     * Pixels that stay similar across shots = subject (high heat).
     * Pixels that jump around = background noise (low heat).
     * Single image: edge magnitude of the 32×32 template.
     */
    static float[] autoHeat(List<ColorTexture> feats) {
        int len = ColorTexture.TPL * ColorTexture.TPL;
        float[] h = new float[len];
        if (feats.size() == 1) {
            float[] t = feats.get(0).tpl;
            float max = 1e-3f;
            for (int y = 1; y < ColorTexture.TPL - 1; y++) {
                for (int x = 1; x < ColorTexture.TPL - 1; x++) {
                    int i = y * ColorTexture.TPL + x;
                    float gx = t[i + 1] - t[i - 1];
                    float gy = t[i + ColorTexture.TPL] - t[i - ColorTexture.TPL];
                    float mag = (float) Math.hypot(gx, gy);
                    h[i] = mag;
                    if (mag > max) max = mag;
                }
            }
            for (int i = 0; i < len; i++) h[i] = 0.25f + 0.75f * (h[i] / max);
            return h;
        }
        float[] mean = new float[len];
        for (ColorTexture f : feats)
            for (int i = 0; i < len; i++) mean[i] += f.tpl[i];
        float inv = 1f / feats.size();
        for (int i = 0; i < len; i++) mean[i] *= inv;
        float[] var = new float[len];
        float vmax = 1e-3f;
        for (ColorTexture f : feats)
            for (int i = 0; i < len; i++) {
                float d = f.tpl[i] - mean[i];
                var[i] += d * d;
            }
        for (int i = 0; i < len; i++) {
            var[i] = (float) Math.sqrt(var[i] * inv);
            if (var[i] > vmax) vmax = var[i];
        }
        for (int i = 0; i < len; i++) {
            float stability = 1f - var[i] / vmax;
            if (stability < 0) stability = 0;
            h[i] = 0.12f + 0.88f * stability;
        }
        return h;
    }

    public static String shortFormula(ColorTexture t) {
        return String.format(Locale.US,
                "μ=(%.0f,%.0f,%.0f)  S=0.40 I_HSV+0.16 I_LBP+0.22 I_HOG+0.12 G+0.10 NCC_w",
                t.meanR, t.meanG, t.meanB);
    }
}
