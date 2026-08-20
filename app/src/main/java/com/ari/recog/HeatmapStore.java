package com.ari.recog;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * Per-label 32×32 attention map. Lives in files/heatmaps/*.hm — never
 * writes into arimodel.bin. Missing file = auto heatmap from LabelBank.
 */
public final class HeatmapStore {
    public static final int N = ColorTexture.TPL; // 32
    public static final int LEN = N * N;

    private final File dir;
    private int gen;

    public HeatmapStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "heatmaps");
        if (!dir.exists()) dir.mkdirs();
    }

    public int generation() { return gen; }

    public float[] load(String label) {
        File f = fileOf(label);
        if (!f.exists()) return null;
        try {
            DataInputStream in = new DataInputStream(new FileInputStream(f));
            int n = in.readInt();
            if (n != LEN) { in.close(); return null; }
            float[] h = new float[LEN];
            for (int i = 0; i < LEN; i++) h[i] = in.readFloat();
            in.close();
            return h;
        } catch (Exception e) {
            DebugLog.w("HeatmapStore load " + label + ": " + e.getMessage());
            return null;
        }
    }

    public boolean save(String label, float[] heat) {
        if (label == null || heat == null || heat.length != LEN) return false;
        try {
            DataOutputStream out = new DataOutputStream(new FileOutputStream(fileOf(label)));
            out.writeInt(LEN);
            for (float v : heat) out.writeFloat(clamp01(v));
            out.close();
            gen++;
            DebugLog.i("heatmap saved " + label);
            return true;
        } catch (Exception e) {
            DebugLog.e("HeatmapStore save", e);
            return false;
        }
    }

    public boolean clear(String label) {
        File f = fileOf(label);
        boolean ok = !f.exists() || f.delete();
        if (ok) gen++;
        return ok;
    }

    public boolean hasUser(String label) { return fileOf(label).exists(); }

    public boolean rename(String from, String to) {
        if (from == null || to == null || from.equals(to)) return false;
        File src = fileOf(from);
        if (!src.exists()) return true;
        float[] h = load(from);
        if (h == null) return false;
        boolean ok = save(to, h);
        if (ok) src.delete();
        return ok;
    }

    private File fileOf(String label) {
        String fn = Integer.toHexString(label.hashCode()) + ".hm";
        return new File(dir, fn);
    }

    public static float[] ones() {
        float[] h = new float[LEN];
        Arrays.fill(h, 1f);
        return h;
    }

    public static float clamp01(float v) { return v < 0 ? 0 : Math.min(1f, v); }
}
