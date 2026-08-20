package com.ari.recog;

import android.content.Context;

/**
 * ARIClassifier — X(784) → PCA(20) → multiplicative pyramid order-2 (231)
 * → one-vs-rest ridge. Softmax on ridge scores is poorly calibrated
 * (correct samples only reach ~0.15–0.31), so we expose a margin-based
 * confidence that actually separates digits from junk.
 */
public class ARIClassifier {
    public static final class Result {
        public final int digit;
        public final float conf;
        public final float margin;
        public final float softmax;
        public Result(int d, float c, float m, float s) {
            digit = d; conf = c; margin = m; softmax = s;
        }
    }

    private final int nPyFeat = 231;
    private final int nClasses = 10;
    private final float[][] pcaComp;
    private final float[] pcaMean;
    private final float[] pyMu, pySd;
    private final float[][] classW;
    private final boolean loaded;

    public ARIClassifier(Context ctx) {
        ARIModel m = new ARIModel();
        if (!m.load(ctx)) {
            pcaComp = new float[20][784];
            pcaMean = new float[784];
            pyMu = new float[231];
            pySd = new float[231];
            classW = new float[10][231];
            loaded = false;
            DebugLog.e("ARIClassifier builtin load failed", null);
        } else {
            pcaComp = m.pcaComp;
            pcaMean = m.pcaMean;
            pyMu = m.pyMu;
            pySd = m.pySd;
            classW = m.classW;
            loaded = true;
        }
    }

    public ARIClassifier(String filePath) {
        ARIModel m = new ARIModel();
        if (m.loadFile(filePath)) {
            pcaComp = m.pcaComp;
            pcaMean = m.pcaMean;
            pyMu = m.pyMu;
            pySd = m.pySd;
            classW = m.classW;
            loaded = true;
        } else {
            pcaComp = new float[20][784];
            pcaMean = new float[784];
            pyMu = new float[231];
            pySd = new float[231];
            classW = new float[10][231];
            loaded = false;
        }
    }

    public boolean isLoaded() { return loaded; }

    public int predict(float[] img784) {
        return predictFull(img784).digit;
    }

    public float confidence(float[] img784) {
        return predictFull(img784).conf;
    }

    public float[] probabilities(float[] img784) {
        float[] s = scores(img784);
        float max = Float.NEGATIVE_INFINITY;
        for (float v : s) if (v > max) max = v;
        float sum = 0;
        float[] p = new float[nClasses];
        for (int c = 0; c < nClasses; c++) { p[c] = (float) Math.exp(s[c] - max); sum += p[c]; }
        for (int c = 0; c < nClasses; c++) p[c] /= sum;
        return p;
    }

    public Result predictFull(float[] img784) {
        float[] s = scores(img784);
        int best = 0, second = 1;
        if (s[1] > s[0]) { best = 1; second = 0; }
        for (int c = 2; c < nClasses; c++) {
            if (s[c] > s[best]) { second = best; best = c; }
            else if (s[c] > s[second]) second = c;
        }
        float margin = s[best] - s[second];
        float max = s[best];
        float sum = 0;
        float[] p = new float[nClasses];
        for (int c = 0; c < nClasses; c++) { p[c] = (float) Math.exp(s[c] - max); sum += p[c]; }
        for (int c = 0; c < nClasses; c++) p[c] /= sum;
        float cal = 1f / (1f + (float) Math.exp(-2.2f * margin));
        float conf = 0.35f * p[best] + 0.65f * cal;
        return new Result(best, conf, margin, p[best]);
    }

    private float[] scores(float[] img784) {
        float[] x = new float[784];
        for (int i = 0; i < 784; i++) x[i] = img784[i] / 255f;
        float[] a = pca(x);
        float[] phi = pyramid(a);
        float[] sc = new float[nClasses];
        for (int c = 0; c < nClasses; c++) {
            float acc = 0;
            float[] wc = classW[c];
            for (int j = 0; j < nPyFeat; j++) {
                float sj = (phi[j] - pyMu[j]) / pySd[j];
                acc += wc[j] * sj;
            }
            sc[c] = acc;
        }
        return sc;
    }

    private float[] pca(float[] x) {
        float[] out = new float[20];
        for (int k = 0; k < 20; k++) {
            float[] comp = pcaComp[k];
            float acc = 0;
            for (int i = 0; i < 784; i++) acc += comp[i] * (x[i] - pcaMean[i]);
            out[k] = acc;
        }
        return out;
    }

    private float[] pyramid(float[] a) {
        float[] out = new float[231];
        int idx = 0;
        out[idx++] = 1f;
        for (int i = 0; i < 20; i++) out[idx++] = a[i];
        for (int i = 0; i < 20; i++)
            for (int j = i; j < 20; j++)
                out[idx++] = a[i] * a[j];
        return out;
    }
}
