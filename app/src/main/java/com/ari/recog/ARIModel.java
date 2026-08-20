package com.ari.recog;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ARIModel — 从 assets 二进制文件加载 ARI 模型权重。
 * 布局 (float32 LE)：
 *   header: 'ARIMODEL1' + [int 20, int 784, int 784]
 *   comp   (20*784)
 *   mean   (784)
 *   [int 231, int 231]
 *   mu (231), sd (231)
 *   [int 10, int 231, int 0]
 *   classW (10*231)
 */
public final class ARIModel {
    public float[][] pcaComp;
    public float[] pcaMean;
    public float[] pyMu, pySd;
    public float[][] classW;
    public boolean loaded = false;

    public boolean load(Context ctx) {
        try {
            AssetManager am = ctx.getAssets();
            InputStream is = am.open("arimodel.bin");
            byte[] data = readAll(is);
            return parse(data);
        } catch (Exception e) {
            loaded = false;
            return false;
        }
    }

    /** 从文件路径加载（训练模型）。 */
    public boolean loadFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            byte[] data = readAll(fis);
            fis.close();
            return parse(data);
        } catch (Exception e) {
            loaded = false;
            return false;
        }
    }

    private static byte[] readAll(java.io.InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private boolean parse(byte[] data) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[9];
            bb.get(magic);
            String m = new String(magic);
            if (!m.startsWith("ARIMODEL1")) { DebugLog.e("bad model magic: " + m, null); return false; }
            int r20 = bb.getInt(), c784 = bb.getInt(), m784 = bb.getInt();
            // 维度校验：防止损坏文件导致 OOM
            if (r20 <= 0 || r20 > 64 || c784 != 784 || m784 != 784) {
                DebugLog.e("bad model dims r20=" + r20 + " c784=" + c784 + " m784=" + m784, null);
                return false;
            }
            pcaComp = new float[r20][c784];
            for (int i = 0; i < r20; i++)
                for (int j = 0; j < c784; j++) pcaComp[i][j] = bb.getFloat();
            pcaMean = new float[m784];
            for (int j = 0; j < m784; j++) pcaMean[j] = bb.getFloat();
            int m231 = bb.getInt(), s231 = bb.getInt();
            if (m231 != 231 || s231 != 231) {
                DebugLog.e("bad pyramid dims m231=" + m231 + " s231=" + s231, null);
                return false;
            }
            pyMu = new float[m231];
            for (int j = 0; j < m231; j++) pyMu[j] = bb.getFloat();
            pySd = new float[s231];
            for (int j = 0; j < s231; j++) pySd[j] = bb.getFloat();
            int r10 = bb.getInt(), c231 = bb.getInt(), _z = bb.getInt();
            if (r10 <= 0 || r10 > 128 || c231 != 231) {
                DebugLog.e("bad classW dims r10=" + r10 + " c231=" + c231, null);
                return false;
            }
            classW = new float[r10][c231];
            for (int i = 0; i < r10; i++)
                for (int j = 0; j < c231; j++) classW[i][j] = bb.getFloat();
            loaded = true;
            return true;
        } catch (Exception e) {
            loaded = false;
            return false;
        }
    }
}
