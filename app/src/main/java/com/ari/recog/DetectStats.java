package com.ari.recog;

import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DetectStats {
    public static volatile int cand, pass, rejInk, rejGate, rejTiny, tracked;
    public static volatile int objCand, objPass, adaptUpdates;
    public static volatile long ms;
    public static volatile int level;
    public static volatile boolean digitsOn, recognizing;
    public static volatile int workW, workH, roiW, roiH, fpsSet;
    public static volatile float fpsActual;
    public static final List<String> hits = new ArrayList<>();
    private static final Object lock = new Object();
    private static long lastTs;
    private static float emaFps = 10f;

    private DetectStats() {}

    public static void beginFrame() {
        synchronized (lock) { hits.clear(); }
        long now = android.os.SystemClock.uptimeMillis();
        if (lastTs > 0) {
            float inst = 1000f / Math.max(1, now - lastTs);
            emaFps = 0.8f * emaFps + 0.2f * inst;
            fpsActual = emaFps;
        }
        lastTs = now;
    }

    public static void addHit(int digit, float score, float ncc, float gap,
                              float ari, float margin, int w, int h, int crowd, String src) {
        synchronized (lock) {
            if (hits.size() > 24) return;
            hits.add(String.format(Locale.US,
                    "d=%d sc=%.2f ncc=%.2f gap=%.2f ari=%.2f %dx%d crowd=%d %s",
                    digit, score, ncc, gap, ari, w, h, crowd, src));
        }
    }

    public static void finishFrame(int c, int p, int ink, int gate, int tiny, long elapsed, int lv) {
        cand = c;
        pass = p;
        rejInk = ink;
        rejGate = gate;
        rejTiny = tiny;
        ms = elapsed;
        level = lv;
    }

    public static String memLine() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long tot = rt.totalMemory() / 1024 / 1024;
        long max = rt.maxMemory() / 1024 / 1024;
        return used + "/" + tot + "MB (max " + max + ")";
    }

    public static String hwLine() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        int cores = Runtime.getRuntime().availableProcessors();
        return "CPU " + abi + " x" + cores + "  infer=CPU (no GPU)";
    }

    public static String dump() {
        String lv = level <= 0 ? "strict" : level >= 2 ? "sensitive" : "normal";
        StringBuilder sb = new StringBuilder();
        sb.append("=== last detect frame ===\n");
        sb.append("level=").append(lv)
                .append(" digitsOn=").append(digitsOn)
                .append(" recognizing=").append(recognizing).append('\n');
        sb.append(String.format(Locale.US, "fps set=%d actual=%.1f  detect=%dms\n", fpsSet, fpsActual, ms));
        sb.append("work=").append(workW).append('x').append(workH)
                .append("  roi=").append(roiW).append('x').append(roiH).append('\n');
        sb.append("mem ").append(memLine()).append('\n');
        sb.append("hw  ").append(hwLine()).append('\n');
        sb.append("digit cand=").append(cand).append(" pass=").append(pass)
                .append(" tracked=").append(tracked).append('\n');
        sb.append("obj  cand=").append(objCand).append(" pass=").append(objPass).append('\n');
        sb.append("rej ink=").append(rejInk).append(" gate=").append(rejGate)
                .append(" tiny=").append(rejTiny).append('\n');
        synchronized (lock) {
            if (hits.isEmpty()) sb.append("(no accepted hits)\n");
            else for (String h : hits) sb.append("  ").append(h).append('\n');
        }
        return sb.toString();
    }
}
