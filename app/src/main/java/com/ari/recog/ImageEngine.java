package com.ari.recog;

import android.content.Context;

import java.io.File;

/**
 * ImageEngine — 全局 ARI 分类器单例。
 * 优先加载用户训练过的模型（active_model.bin），否则用内置模型（assets）。
 */
public final class ImageEngine {
    private static ARIClassifier instance;

    public static synchronized ARIClassifier getClassifier(Context ctx) {
        if (instance == null) {
            instance = build(ctx);
        }
        return instance;
    }

    public static synchronized void reload(Context ctx) {
        instance = build(ctx);
        DebugLog.d("ImageEngine reloaded classifier");
    }

    /** 强制使用内置模型（删除训练模型标记）。 */
    public static synchronized void useBuiltin(Context ctx) {
        File active = new File(ctx.getApplicationContext().getFilesDir(), "active_model.bin");
        if (active.exists()) active.delete();
        DebugLog.d("Deleted active_model.bin, will use builtin");
    }

    private static ARIClassifier build(Context ctx) {
        Context app = ctx.getApplicationContext();
        // 优先训练模型
        File trained = new File(app.getFilesDir(), "active_model.bin");
        if (trained.exists()) {
            DebugLog.d("Loading trained model: " + trained.getAbsolutePath());
            ARIClassifier c = new ARIClassifier(trained.getAbsolutePath());
            if (c.isLoaded()) return c;
            DebugLog.d("Trained model load failed, fallback to builtin");
        }
        DebugLog.d("Loading built-in model from assets");
        return new ARIClassifier(app);
    }
}
