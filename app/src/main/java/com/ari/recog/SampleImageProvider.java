package com.ari.recog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SampleImageProvider — 内置示例图片管理。
 * 从 assets/samples.bin 加载 10 张内置 MNIST 手写数字示例（0-9），
 * 供开箱即用地进行识别演示，并支持保存到应用私有目录/相册。
 */
public final class SampleImageProvider {
    private final List<SampleImage> samples = new ArrayList<>();

    public static class SampleImage {
        public final int label;
        public final float[] pixels;   // 784 灰度 0..1
        public SampleImage(int label, float[] pixels) {
            this.label = label;
            this.pixels = pixels;
        }
        public Bitmap toBitmap() {
            return pixelsToBitmap(pixels);
        }
        public float[] toInput784() {
            float[] in = new float[784];
            for (int i = 0; i < 784; i++) in[i] = pixels[i] * 255f;
            return in;
        }
    }

    public SampleImageProvider(Context ctx) {
        load(ctx);
    }

    private void load(Context ctx) {
        try {
            InputStream is = ctx.getAssets().open("samples.bin");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();
            // magic 'SMPL0001' (8) + size(1)
            if (data.length < 9) { DebugLog.d("samples.bin too small"); return; }
            String magic = new String(data, 0, 8);
            if (!magic.startsWith("SMPL")) { DebugLog.d("bad samples magic: " + magic); return; }
            int size = data[8] & 0xFF; // 28
            int pos = 9;
            while (pos + 1 + size * size <= data.length) {
                int label = data[pos] & 0xFF;
                float[] px = new float[size * size];
                for (int i = 0; i < size * size; i++) {
                    px[i] = (data[pos + 1 + i] & 0xFF) / 255f;
                }
                samples.add(new SampleImage(label, px));
                pos += 1 + size * size;
            }
            DebugLog.d("Loaded " + samples.size() + " built-in sample images");
        } catch (Exception e) {
            DebugLog.e("Failed to load samples.bin", e);
        }
    }

    public int size() {
        return samples.size();
    }

    public SampleImage get(int i) {
        return samples.get(i);
    }

    public List<SampleImage> all() {
        return samples;
    }

    /** 把 784 灰度(0..1) 转成 28x28 放大显示 Bitmap（放大到像素格）。 */
    public static Bitmap pixelsToBitmap(float[] px) {
        int size = 28;
        int scale = 4; // 显示放大倍数 -> 112x112
        Bitmap bmp = Bitmap.createBitmap(size * scale, size * scale, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float v = px[y * size + x];
                int g = (int) (v * 255f);
                int color = Color.rgb(g, g, g);
                for (int dy = 0; dy < scale; dy++)
                    for (int dx = 0; dx < scale; dx++)
                        bmp.setPixel(x * scale + dx, y * scale + dy, color);
            }
        }
        return bmp;
    }

    /** 把示例保存为 PNG 到应用私有目录，返回文件路径。 */
    public static File saveToInternal(Context ctx, String filename, Bitmap bmp) {
        try {
            File dir = new File(ctx.getFilesDir(), "samples");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            return f;
        } catch (Exception e) {
            DebugLog.e("save sample failed", e);
            return null;
        }
    }
}
