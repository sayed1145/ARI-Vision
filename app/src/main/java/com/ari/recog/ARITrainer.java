package com.ari.recog;

import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ARITrainer — 在线训练 + 模型管理 + 持久化。
 *
 * 防灾难性遗忘：保存所有训练样本，训练时在【全部历史样本】上重新拟合
 * 岭分类（而非只在新样本上增量，增量会遗忘旧类）。这样旧知识保留。
 *
 * 持久化：样本保存到内部存储（重启不丢），模型权重保存到文件。
 * 支持多文件批量加入训练集。
 */
public class ARITrainer {

    public static class Sample {
        public final float[] pixels;   // 784
        public int label;              // 可修改
        public Sample(float[] pixels, int label) {
            this.pixels = pixels;
            this.label = label;
        }
    }

    private final Context ctx;
    private final List<Sample> samples = new ArrayList<>();
    private final File samplesFile;
    private final File modelFile;
    private boolean dirty = false;

    public ARITrainer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        File dir = new File(this.ctx.getFilesDir(), "train");
        if (!dir.exists()) dir.mkdirs();
        samplesFile = new File(dir, "samples.bin");
        modelFile = new File(dir, "model.bin");
        loadSamples();
    }

    // ---------- 样本持久化 ----------
    private void loadSamples() {
        try {
            if (!samplesFile.exists()) { DebugLog.d("No saved samples yet"); return; }
            DataInputStream dis = new DataInputStream(new FileInputStream(samplesFile));
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                float[] px = new float[784];
                for (int j = 0; j < 784; j++) px[j] = dis.readFloat();
                int label = dis.readInt();
                samples.add(new Sample(px, label));
            }
            dis.close();
            DebugLog.d("Loaded " + samples.size() + " persisted training samples");
        } catch (Exception e) {
            DebugLog.e("loadSamples failed", e);
        }
    }

    private void saveSamples() {
        try {
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(samplesFile));
            dos.writeInt(samples.size());
            for (Sample s : samples) {
                for (int j = 0; j < 784; j++) dos.writeFloat(s.pixels[j]);
                dos.writeInt(s.label);
            }
            dos.flush();
            dos.close();
            DebugLog.d("Saved " + samples.size() + " samples to disk");
        } catch (Exception e) {
            DebugLog.e("saveSamples failed", e);
        }
    }

    // ---------- 训练集管理 ----------
    public synchronized void addSample(float[] pixels, int label) {
        samples.add(new Sample(pixels.clone(), label));
        dirty = true;
        DebugLog.d("Added sample label=" + label + " total=" + samples.size());
    }

    public synchronized void addSamples(List<float[]> pixelsList, List<Integer> labels) {
        for (int i = 0; i < pixelsList.size(); i++) {
            samples.add(new Sample(pixelsList.get(i).clone(), labels.get(i)));
        }
        dirty = true;
        DebugLog.d("Batch added " + pixelsList.size() + " samples, total=" + samples.size());
    }

    public synchronized int sampleCount() {
        return samples.size();
    }

    /** 修改某个样本的标签。 */
    public synchronized boolean setLabel(int idx, int newLabel) {
        if (idx < 0 || idx >= samples.size() || newLabel < 0 || newLabel > 9) return false;
        samples.get(idx).label = newLabel;
        dirty = true;
        saveSamples();
        DebugLog.d("setLabel idx=" + idx + " -> " + newLabel);
        return true;
    }

    /** 删除某个样本。 */
    public synchronized boolean removeSample(int idx) {
        if (idx < 0 || idx >= samples.size()) return false;
        samples.remove(idx);
        dirty = true;
        saveSamples();
        DebugLog.d("removeSample idx=" + idx + ", remaining=" + samples.size());
        return true;
    }

    public synchronized void clearSamples() {
        samples.clear();
        dirty = true;
        DebugLog.d("Cleared all training samples");
    }

    public synchronized List<Sample> getSamples() {
        return new ArrayList<>(samples);
    }

    public synchronized int labelCount() {
        java.util.Set<Integer> labels = new java.util.HashSet<>();
        for (Sample s : samples) labels.add(s.label);
        return labels.size();
    }

    // ---------- 训练 ----------
    /**
     * 在所有历史样本上重新拟合（防灾难性遗忘）。
     * 训练后把样本和模型都持久化。
     * 返回训练样本数；不足则返回 -1。
     */
    public synchronized int train() {
        if (samples.size() < 2) {
            DebugLog.d("train() abort: need >= 2 samples, have " + samples.size());
            return -1;
        }
        DebugLog.d("train() start with " + samples.size() + " samples");
        try {
            // 组装矩阵
            int n = samples.size();
            float[][] X = new float[n][784];
            int[] y = new int[n];
            for (int i = 0; i < n; i++) {
                System.arraycopy(samples.get(i).pixels, 0, X[i], 0, 784);
                y[i] = samples.get(i).label;
            }
            // 调用纯 Java 训练器（PCA + 金字塔 + 岭），写入模型
            boolean ok = javaTrain(X, y);
            if (ok) {
                saveSamples();
                saveModel();
                DebugLog.d("train() done OK");
                return n;
            }
        } catch (Exception e) {
            DebugLog.e("train() exception", e);
        }
        return -1;
    }

    private boolean javaTrain(float[][] X, int[] y) {
        // 用在线模型训练器（内嵌 PCA + ridge），生成权重供 ARIClassifier 使用
        boolean ok = OnlineModelEngine.trainAndSave(ctx, X, y, modelFile);
        return ok;
    }

    private void saveModel() {
        // 权重已由 OnlineModelEngine 写入 modelFile；复制为默认模型供识别用
        OnlineModelEngine.activateModel(ctx, modelFile);
    }

    public File getModelFile() {
        return modelFile;
    }
}
