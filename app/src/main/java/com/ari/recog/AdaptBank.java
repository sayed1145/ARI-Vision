package com.ari.recog;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-label online appearance that NEVER writes into ObjectStore PNGs
 * or arimodel.bin. Disable the setting or wipe this folder to roll back.
 */
public final class AdaptBank {

    public static final class Slot {
        public final float[] hsv = new float[ColorTexture.HIST];
        public final float[] lbp = new float[ColorTexture.LBP];
        public final List<float[]> tpls = new ArrayList<>(); // 32x32 extras, max 3
        public int updates;
    }

    private final File dir;
    private final Map<String, Slot> map = new HashMap<>();
    private boolean enabled = true;

    public AdaptBank(Context ctx) {
        dir = new File(ctx.getFilesDir(), "adapt");
        if (!dir.exists()) dir.mkdirs();
        loadAll();
    }

    public void setEnabled(boolean on) { enabled = on; }
    public boolean enabled() { return enabled; }

    public synchronized float score(String label, ColorTexture ft) {
        if (!enabled) return 0;
        Slot s = map.get(label);
        if (s == null || s.updates == 0) return 0;
        float col = ColorTexture.intersect(s.hsv, ft.hsv);
        float tex = ColorTexture.intersect(s.lbp, ft.lbp);
        float ncc = 0;
        for (float[] t : s.tpls) {
            float v = ColorTexture.ncc32(t, ft.tpl);
            if (v > ncc) ncc = v;
        }
        if (ncc < 0) ncc = 0;
        return 0.50f * col + 0.20f * tex + 0.30f * ncc;
    }

    public synchronized void update(String label, ColorTexture ft) {
        if (!enabled || label == null || ft == null) return;
        Slot s = map.get(label);
        if (s == null) { s = new Slot(); map.put(label, s); }
        float a = s.updates == 0 ? 1f : 0.18f; // EMA — does not overwrite base
        for (int i = 0; i < s.hsv.length; i++) s.hsv[i] = (1 - a) * s.hsv[i] + a * ft.hsv[i];
        for (int i = 0; i < s.lbp.length; i++) s.lbp[i] = (1 - a) * s.lbp[i] + a * ft.lbp[i];
        if (s.tpls.size() < 3) s.tpls.add(ft.tpl.clone());
        else s.tpls.set(s.updates % 3, ft.tpl.clone());
        s.updates++;
        if (s.updates % 4 == 0) save(label, s);
        DetectStats.adaptUpdates = s.updates;
    }

    public synchronized void removeLabel(String label) {
        if (label == null) return;
        map.remove(label);
        File f = new File(dir, Integer.toHexString(label.hashCode()) + ".adp");
        if (f.exists()) f.delete();
        DebugLog.d("AdaptBank removed " + label);
    }

    public synchronized void clear() {
        map.clear();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        DebugLog.d("AdaptBank wiped (base model untouched)");
    }

    private void loadAll() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.getName().endsWith(".adp")) continue;
            try {
                DataInputStream in = new DataInputStream(new FileInputStream(f));
                String label = in.readUTF();
                Slot s = new Slot();
                s.updates = in.readInt();
                for (int i = 0; i < s.hsv.length; i++) s.hsv[i] = in.readFloat();
                for (int i = 0; i < s.lbp.length; i++) s.lbp[i] = in.readFloat();
                int n = in.readInt();
                for (int k = 0; k < n && k < 3; k++) {
                    float[] t = new float[ColorTexture.TPL * ColorTexture.TPL];
                    for (int i = 0; i < t.length; i++) t[i] = in.readFloat();
                    s.tpls.add(t);
                }
                in.close();
                map.put(label, s);
            } catch (Exception e) {
                DebugLog.e("AdaptBank load " + f.getName(), e);
            }
        }
        DebugLog.d("AdaptBank loaded " + map.size() + " slots");
    }

    private void save(String label, Slot s) {
        try {
            String fn = Integer.toHexString(label.hashCode()) + ".adp";
            DataOutputStream out = new DataOutputStream(new FileOutputStream(new File(dir, fn)));
            out.writeUTF(label);
            out.writeInt(s.updates);
            for (float v : s.hsv) out.writeFloat(v);
            for (float v : s.lbp) out.writeFloat(v);
            out.writeInt(s.tpls.size());
            for (float[] t : s.tpls) for (float v : t) out.writeFloat(v);
            out.close();
        } catch (Exception e) {
            DebugLog.e("AdaptBank save", e);
        }
    }
}
