package com.ari.recog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * User objects. PNG on disk is always the FULL user image (never cropped).
 * Subject features are extracted in memory for matching only.
 */
public final class ObjectStore {

    public static final class Item {
        public final String id;
        public String label;
        public final String file;
        public transient Bitmap thumb;
        public transient ColorTexture feat;     // full image
        public transient ColorTexture subjFeat; // internal subject

        public Item(String id, String label, String file) {
            this.id = id;
            this.label = label;
            this.file = file;
        }
    }

    private final File dir;
    private final File index;
    private final File labelsFile;
    private final List<Item> items = new ArrayList<>();
    private final List<String> known = new ArrayList<>();
    private int gen;
    private static ObjectStore INSTANCE;

    public static synchronized ObjectStore get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new ObjectStore(ctx.getApplicationContext());
        return INSTANCE;
    }

    public ObjectStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "objects");
        if (!dir.exists()) dir.mkdirs();
        index = new File(dir, "index.json");
        labelsFile = new File(dir, "labels.json");
        load();
        loadLabels();
    }

    public synchronized List<Item> all() { return new ArrayList<>(items); }
    public synchronized int size() { return items.size(); }
    public synchronized Item get(int i) {
        return (i >= 0 && i < items.size()) ? items.get(i) : null;
    }
    public File dir() { return dir; }
    public File fileOf(Item it) { return new File(dir, it.file); }

    public Bitmap loadBitmap(Item it) {
        File f = fileOf(it);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    /** Subject features if found; otherwise full image. */
    public ColorTexture features(Item it) { return subjectFeatures(it); }

    public ColorTexture fullFeatures(Item it) {
        ensureFeats(it);
        return it == null ? null : it.feat;
    }

    public ColorTexture subjectFeatures(Item it) {
        ensureFeats(it);
        if (it == null) return null;
        return it.subjFeat != null ? it.subjFeat : it.feat;
    }

    private void ensureFeats(Item it) {
        if (it == null || it.feat != null) return;
        Bitmap bmp = loadBitmap(it);
        if (bmp == null) return;
        it.feat = new ColorTexture(bmp);
        if (it.thumb == null) it.thumb = Bitmap.createScaledBitmap(bmp, 96, 96, true);
        try {
            Bitmap sub = Saliency.focusCrop(bmp);
            if (sub != null && sub != bmp) {
                int sa = sub.getWidth() * sub.getHeight();
                int fa = Math.max(1, bmp.getWidth() * bmp.getHeight());
                float frac = sa / (float) fa;
                if (frac >= 0.004f && frac <= 0.90f && sub.getWidth() >= 16 && sub.getHeight() >= 16) {
                    it.subjFeat = new ColorTexture(sub);
                    DebugLog.d("subject feat " + sub.getWidth() + "x" + sub.getHeight()
                            + " from " + bmp.getWidth() + "x" + bmp.getHeight());
                }
                sub.recycle();
            }
        } catch (Exception e) {
            DebugLog.w("subject feat skip: " + e.getMessage());
        }
    }

    public synchronized Item add(Bitmap bmp, String label) {
        if (bmp == null) return null;
        String lab = sanitizeLabel(label);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String fn = id + ".png";
        File out = new File(dir, fn);
        try {
            FileOutputStream fos = new FileOutputStream(out);
            Bitmap scaled = limitSize(bmp, 256);
            scaled.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            if (scaled != bmp) scaled.recycle();
        } catch (Exception e) {
            DebugLog.e("ObjectStore add write", e);
            return null;
        }
        Item it = new Item(id, lab, fn);
        items.add(it);
        ensureLabel(lab);
        save();
        bump();
        DebugLog.i("ObjectStore add label=" + lab + " total=" + items.size() + " (full image)");
        return it;
    }

    public synchronized int addMany(List<Bitmap> bmps, String label) {
        if (bmps == null || bmps.isEmpty()) return 0;
        int n = 0;
        for (Bitmap b : bmps) {
            if (add(b, label) != null) n++;
        }
        return n;
    }

    public synchronized boolean replaceImage(String id, Bitmap bmp) {
        Item it = find(id);
        if (it == null || bmp == null) return false;
        try {
            FileOutputStream fos = new FileOutputStream(fileOf(it));
            Bitmap scaled = limitSize(bmp, 256);
            scaled.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            if (scaled != bmp) scaled.recycle();
            it.feat = null;
            it.subjFeat = null;
            it.thumb = null;
            save();
            bump();
            DebugLog.i("ObjectStore replaced image " + id + " (full image)");
            return true;
        } catch (Exception e) {
            DebugLog.e("replaceImage", e);
            return false;
        }
    }

    public synchronized boolean setLabel(String id, String label) {
        Item it = find(id);
        if (it == null) return false;
        it.label = sanitizeLabel(label);
        save();
        DebugLog.d("ObjectStore relabel " + id + " -> " + it.label);
        return true;
    }

    public synchronized boolean remove(String id) {
        Item it = find(id);
        if (it == null) return false;
        String lab = it.label;
        items.remove(it);
        File f = fileOf(it);
        recycleItem(it);
        if (f.exists()) f.delete();
        save();
        bump();
        DebugLog.d("ObjectStore remove " + id + " remaining=" + items.size()
                + " label=" + lab + " left=" + countOf(lab));
        return true;
    }

    public synchronized int removeMany(List<String> ids) {
        if (ids == null) return 0;
        int n = 0;
        for (String id : ids) if (remove(id)) n++;
        return n;
    }

    /** Keep the label even if every shot is gone. */
    public synchronized void ensureLabel(String label) {
        String lab = sanitizeLabel(label);
        if (!known.contains(lab)) {
            known.add(lab);
            saveLabels();
        }
    }

    public synchronized boolean renameLabel(String from, String to) {
        if (from == null) return false;
        String src = sanitizeLabel(from);
        String dst = sanitizeLabel(to);
        if (src.equals(dst)) return true;
        for (Item it : items) if (src.equals(it.label)) it.label = dst;
        known.remove(src);
        if (!known.contains(dst)) known.add(dst);
        save();
        saveLabels();
        DebugLog.i("renameLabel " + src + " -> " + dst);
        return true;
    }

    /** Wipe one label: every PNG, index row, known name. Caller also drops heat/adapt. */
    public synchronized int deleteLabel(String label) {
        if (label == null) return 0;
        String lab = sanitizeLabel(label);
        List<Item> hit = itemsOf(lab);
        for (Item it : new ArrayList<>(hit)) {
            recycleItem(it);
            items.remove(it);
            File f = fileOf(it);
            if (f.exists() && !f.delete()) DebugLog.w("png not deleted " + f.getName());
        }
        for (int i = known.size() - 1; i >= 0; i--) {
            if (lab.equals(known.get(i))) known.remove(i);
        }
        save();
        saveLabels();
        bump();
        DebugLog.i("deleteLabel " + lab + " files=" + hit.size() + " known=" + known.size());
        return hit.size();
    }

    /** Full purge: images + label + heatmap + adapt. UI must reload afterwards. */
    public int purgeLabel(Context ctx, String label) {
        String lab = sanitizeLabel(label);
        int n = deleteLabel(lab);
        try { new HeatmapStore(ctx).clear(lab); } catch (Exception e) { DebugLog.w("heat purge: " + e.getMessage()); }
        try { new AdaptBank(ctx).removeLabel(lab); } catch (Exception e) { DebugLog.w("adapt purge: " + e.getMessage()); }
        return n;
    }

    public synchronized void reload() {
        for (Item it : items) recycleItem(it);
        load();
        loadLabels();
        DebugLog.d("ObjectStore reload items=" + items.size() + " labels=" + known.size());
    }

    public int generation() { return gen; }

    private void bump() { gen++; }

    private static void recycleItem(Item it) {
        if (it == null) return;
        if (it.thumb != null && !it.thumb.isRecycled()) {
            try { it.thumb.recycle(); } catch (Exception ignored) {}
        }
        it.thumb = null;
        it.feat = null;
        it.subjFeat = null;
    }

    public synchronized void clear() {
        for (Item it : items) {
            File f = fileOf(it);
            if (f.exists()) f.delete();
        }
        items.clear();
        save();
    }

    public Item find(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    public synchronized List<String> labels() {
        List<String> out = new ArrayList<>(known);
        for (Item it : items) if (!out.contains(it.label)) out.add(it.label);
        return out;
    }

    public synchronized List<Item> itemsOf(String label) {
        List<Item> out = new ArrayList<>();
        if (label == null) return out;
        for (Item it : items) if (label.equals(it.label)) out.add(it);
        return out;
    }

    public synchronized int countOf(String label) { return itemsOf(label).size(); }

    public static String sanitizeLabel(String raw) {
        if (raw == null) return "object";
        String s = raw.trim().replace('\n', ' ').replace('\r', ' ');
        if (s.isEmpty()) return "object";
        if (s.length() > 32) s = s.substring(0, 32);
        return s;
    }

    private static Bitmap limitSize(Bitmap bmp, int maxSide) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int m = Math.max(w, h);
        if (m <= maxSide) return bmp;
        float s = maxSide / (float) m;
        return Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * s)),
                Math.max(1, Math.round(h * s)), true);
    }

    private void loadLabels() {
        known.clear();
        if (labelsFile == null || !labelsFile.exists()) {
            for (Item it : items) if (!known.contains(it.label)) known.add(it.label);
            saveLabels();
            return;
        }
        try {
            FileInputStream fis = new FileInputStream(labelsFile);
            byte[] buf = new byte[(int) labelsFile.length()];
            int n = fis.read(buf);
            fis.close();
            JSONArray arr = new JSONArray(new String(buf, 0, Math.max(0, n), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                String s = sanitizeLabel(arr.optString(i, ""));
                if (!s.isEmpty() && !known.contains(s)) known.add(s);
            }
            for (Item it : items) if (!known.contains(it.label)) known.add(it.label);
        } catch (Exception e) {
            DebugLog.e("ObjectStore labels load", e);
        }
    }

    private void saveLabels() {
        try {
            JSONArray arr = new JSONArray();
            for (String s : known) arr.put(s);
            FileOutputStream fos = new FileOutputStream(labelsFile);
            fos.write(arr.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            DebugLog.e("ObjectStore labels save", e);
        }
    }

    private void load() {
        items.clear();
        if (!index.exists()) {
            DebugLog.d("ObjectStore empty");
            return;
        }
        try {
            FileInputStream fis = new FileInputStream(index);
            byte[] buf = new byte[(int) index.length()];
            int n = fis.read(buf);
            fis.close();
            JSONArray arr = new JSONArray(new String(buf, 0, Math.max(0, n), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String label = o.optString("label", "object");
                String file = o.optString("file", "");
                if (id.isEmpty() || file.isEmpty()) continue;
                if (!new File(dir, file).exists()) continue;
                items.add(new Item(id, label, file));
            }
            DebugLog.d("ObjectStore loaded " + items.size());
        } catch (Exception e) {
            DebugLog.e("ObjectStore load", e);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("label", it.label);
                o.put("file", it.file);
                arr.put(o);
            }
            FileOutputStream fos = new FileOutputStream(index);
            fos.write(arr.toString(2).getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            DebugLog.e("ObjectStore save", e);
        }
    }
}
