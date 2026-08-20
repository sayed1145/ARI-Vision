package com.ari.recog;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * OnlineModelEngine — 纯 Java 的 ARI 训练器。
 * 对训练样本执行：归一化 → PCA(20) → 乘法金字塔(order2,231) → 岭 one-vs-rest，
 * 输出与 ARIModel 相同格式的二进制模型，供 ARIClassifier 加载。
 * 全闭式、无梯度，适合移动端在线训练。
 */
public final class OnlineModelEngine {

    public static final int PCA_DIM = 20;
    public static final int N_FEAT = 231; // 1 + 20 + 210

    private OnlineModelEngine() {}

    /**
     * 训练并保存模型到 modelFile。返回是否成功。
     */
    public static boolean trainAndSave(Context ctx, float[][] X, int[] y, File modelFile) {
        int n = X.length;
        int d = 784;
        // 1) 中心化 + 归一化
        float[] mean = new float[d];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < d; j++) mean[j] += X[i][j];
        for (int j = 0; j < d; j++) mean[j] /= n;

        // 2) PCA（协方差特征分解）
        float[] pcaMean = mean.clone();
        // 数据中心化矩阵
        float[][] Xc = new float[n][d];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < d; j++) Xc[i][j] = X[i][j] - mean[j];
        // 协方差 C = Xc^T Xc (d x d)
        float[][] cov = new float[d][d];
        for (int a = 0; a < d; a++) {
            for (int b = a; b < d; b++) {
                float s = 0;
                for (int i = 0; i < n; i++) s += Xc[i][a] * Xc[i][b];
                cov[a][b] = s; cov[b][a] = s;
            }
        }
        // 幂迭代法取前 PCA_DIM 个主成分（近似）
        float[][] comp = new float[PCA_DIM][d];
        // 正交化幂迭代
        float[][] resid = cov.clone();
        for (int k = 0; k < PCA_DIM; k++) {
            float[] v = new float[d];
            for (int j = 0; j < d; j++) v[j] = (float) Math.random() + 0.01f;
            normalize(v);
            for (int it = 0; it < 40; it++) {
                float[] nv = matVec(resid, v);
                // 去除前 k-1 个已求得主成分
                for (int p = 0; p < k; p++) {
                    float dot = 0;
                    for (int j = 0; j < d; j++) dot += comp[p][j] * nv[j];
                    for (int j = 0; j < d; j++) nv[j] -= dot * comp[p][j];
                }
                normalize(nv);
                v = nv;
            }
            System.arraycopy(v, 0, comp[k], 0, d);
            // 从残差中去除该主成分
            for (int a = 0; a < d; a++) {
                for (int b = 0; b < d; b++) {
                    resid[a][b] -= v[a] * v[b]; // 特征值≈1（简化）
                }
            }
        }
        // 计算 PCA 投影（去中心化）
        float[][] proj = new float[n][PCA_DIM];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < PCA_DIM; k++) {
                float s = 0;
                for (int j = 0; j < d; j++) s += comp[k][j] * Xc[i][j];
                proj[i][k] = s;
            }

        // 3) 金字塔 order2 特征
        float[][] phi = new float[n][N_FEAT];
        for (int i = 0; i < n; i++) {
            float[] a = proj[i];
            int idx = 0;
            phi[i][idx++] = 1f;
            for (int j = 0; j < PCA_DIM; j++) phi[i][idx++] = a[j];
            for (int j = 0; j < PCA_DIM; j++)
                for (int k = j; k < PCA_DIM; k++)
                    phi[i][idx++] = a[j] * a[k];
        }
        // 标准化
        float[] pyMu = new float[N_FEAT], pySd = new float[N_FEAT];
        for (int j = 0; j < N_FEAT; j++) {
            float s = 0;
            for (int i = 0; i < n; i++) s += phi[i][j];
            pyMu[j] = s / n;
        }
        for (int j = 0; j < N_FEAT; j++) {
            float s = 0;
            for (int i = 0; i < n; i++) { float t = phi[i][j] - pyMu[j]; s += t * t; }
            pySd[j] = (float) Math.sqrt(s / n) + 1e-6f;
        }
        float[][] phis = new float[n][N_FEAT];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < N_FEAT; j++)
                phis[i][j] = (phi[i][j] - pyMu[j]) / pySd[j];

        // 4) 岭 one-vs-rest 权重
        int nClasses = 10;
        float[][] W = new float[nClasses][N_FEAT];
        float ridge = 0.1f;
        float[][] A = new float[N_FEAT][N_FEAT];
        for (int a = 0; a < N_FEAT; a++) {
            for (int b = 0; b < N_FEAT; b++) {
                float s = 0;
                for (int i = 0; i < n; i++) s += phis[i][a] * phis[i][b];
                A[a][b] = s + (a == b ? ridge : 0);
            }
        }
        // 求解 A W = B（对每类）
        for (int c = 0; c < nClasses; c++) {
            float[] B = new float[N_FEAT];
            for (int a = 0; a < N_FEAT; a++) {
                float s = 0;
                for (int i = 0; i < n; i++) {
                    float t = (y[i] == c) ? 1f : 0f;
                    s += phis[i][a] * t;
                }
                B[a] = s;
            }
            W[c] = solveLinear(A, B);
        }

        // 5) 写模型文件（小端序，与 ARIModel.parse 的 ByteBuffer.LITTLE_ENDIAN 一致）
        try {
            int magicLen = "ARIMODEL1".length(); // 9
            int nFloat = PCA_DIM * d + d + N_FEAT + N_FEAT + nClasses * N_FEAT;
            int size = magicLen + 12 + nFloat * 4 + 4 + 4 + 12;
            ByteBuffer bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("ARIMODEL1".getBytes());
            bb.putInt(PCA_DIM); bb.putInt(d); bb.putInt(d);
            for (int k = 0; k < PCA_DIM; k++)
                for (int j = 0; j < d; j++) bb.putFloat(comp[k][j]);
            for (int j = 0; j < d; j++) bb.putFloat(pcaMean[j]);
            bb.putInt(N_FEAT); bb.putInt(N_FEAT);
            for (int j = 0; j < N_FEAT; j++) bb.putFloat(pyMu[j]);
            for (int j = 0; j < N_FEAT; j++) bb.putFloat(pySd[j]);
            bb.putInt(nClasses); bb.putInt(N_FEAT); bb.putInt(0);
            for (int c = 0; c < nClasses; c++)
                for (int j = 0; j < N_FEAT; j++) bb.putFloat(W[c][j]);

            FileOutputStream fos = new FileOutputStream(modelFile);
            fos.write(bb.array());
            fos.flush();
            fos.close();
            DebugLog.d("Model trained+written (little-endian) to " + modelFile.getName());
            return true;
        } catch (Exception e) {
            DebugLog.e("write model failed", e);
            return false;
        }
    }

    /** 激活训练好的模型为当前识别模型（复制到默认位置并重载）。 */
    public static void activateModel(Context ctx, File trainedModel) {
        // 复制到 assets 同等的可读位置
        try {
            File def = new File(ctx.getFilesDir(), "active_model.bin");
            FileOutputStream fos = new FileOutputStream(def);
            java.io.FileInputStream fis = new java.io.FileInputStream(trainedModel);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            fis.close(); fos.close();
            DebugLog.d("Activated trained model -> " + def.getAbsolutePath());
            // 让 ImageEngine 重载
            ImageEngine.reload(ctx);
        } catch (Exception e) {
            DebugLog.e("activateModel failed", e);
        }
    }

    private static void normalize(float[] v) {
        float s = 0;
        for (float x : v) s += x * x;
        s = (float) Math.sqrt(s + 1e-12);
        for (int i = 0; i < v.length; i++) v[i] /= s;
    }

    private static float[] matVec(float[][] A, float[] v) {
        int d = v.length;
        float[] out = new float[d];
        for (int i = 0; i < d; i++) {
            float s = 0;
            for (int j = 0; j < d; j++) s += A[i][j] * v[j];
            out[i] = s;
        }
        return out;
    }

    private static float[] solveLinear(float[][] A, float[] B) {
        int n = B.length;
        // Gaussian elimination with partial pivot
        float[][] a = new float[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, a[i], 0, n);
            a[i][n] = B[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            float[] tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp;
            float d = a[col][col];
            if (Math.abs(d) < 1e-12f) d = 1e-12f;
            for (int j = col; j <= n; j++) a[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                float f = a[r][col];
                for (int j = col; j <= n; j++) a[r][j] -= f * a[col][j];
            }
        }
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = a[i][n];
        return x;
    }
}
