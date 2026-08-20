package com.ari.recog;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Ring buffer of the last 100 lines, tagged by severity.
 * UI shows this buffer; export writes a zip split by level.
 */
public final class DebugLog {
    public static final int ERROR = 0, WARN = 1, INFO = 2, DEBUG = 3;
    private static final String TAG = "ARI-DEBUG";
    private static final int MAX = 100;
    private static final String[] TAGS = {"E", "W", "I", "D"};

    public static final class Entry {
        public final long ts;
        public final int level;
        public final String msg;
        Entry(long ts, int level, String msg) {
            this.ts = ts; this.level = level; this.msg = msg;
        }
    }

    private static final ArrayDeque<Entry> buf = new ArrayDeque<>();

    private DebugLog() {}

    public static void e(String msg) { e(msg, null); }

    public static void e(String msg, Throwable t) {
        String line = msg + (t != null ? " | " + t : "");
        Log.e(TAG, line, t);
        add(ERROR, line);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        add(WARN, msg);
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        add(INFO, msg);
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
        add(DEBUG, msg);
    }

    private static void add(int level, String msg) {
        synchronized (buf) {
            buf.addLast(new Entry(System.currentTimeMillis(), level, msg));
            while (buf.size() > MAX) buf.removeFirst();
        }
    }

    public static List<Entry> snapshot() {
        synchronized (buf) { return new ArrayList<>(buf); }
    }

    /** Last 100 lines, newest at the bottom. */
    public static String dump() {
        return format(snapshot(), -1);
    }

    /** Only this level from the last 100. */
    public static String dumpLevel(int level) {
        List<Entry> all = snapshot();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (e.level == level) out.add(e);
        return format(out, level);
    }

    public static int[] counts() {
        int[] c = new int[4];
        for (Entry e : snapshot()) c[e.level]++;
        return c;
    }

    public static String header() {
        int[] c = counts();
        return String.format(Locale.US, "last %d  [E]%d  [W]%d  [I]%d  [D]%d",
                c[0] + c[1] + c[2] + c[3], c[0], c[1], c[2], c[3]);
    }

    private static String format(List<Entry> list, int only) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        StringBuilder sb = new StringBuilder();
        if (only < 0) sb.append(header()).append('\n');
        for (Entry e : list) {
            sb.append('[').append(TAGS[e.level]).append("] ")
                    .append(fmt.format(new Date(e.ts))).append(' ')
                    .append(e.msg).append('\n');
        }
        if (list.isEmpty()) sb.append("(empty)\n");
        return sb.toString();
    }

    public static void clear() {
        synchronized (buf) { buf.clear(); }
    }

    /** Zip: snapshot + per-level logs + all. Written into {@code dir}. */
    public static File writeZip(File dir) {
        if (dir == null) return null;
        if (!dir.exists() && !dir.mkdirs()) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File z = new File(dir, "ARI_logs_" + stamp + ".zip");
        try {
            ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(z));
            put(zo, "00_README.txt",
                    "ARI debug export\n"
                            + header() + "\n"
                            + "00 snapshot = last detect frame\n"
                            + "01 error / 02 warn / 03 info / 04 debug = last 100 split by severity\n"
                            + "05 all = last 100 mixed\n");
            put(zo, "00_snapshot.txt", DetectStats.dump());
            put(zo, "01_error.txt", dumpLevel(ERROR));
            put(zo, "02_warn.txt", dumpLevel(WARN));
            put(zo, "03_info.txt", dumpLevel(INFO));
            put(zo, "04_debug.txt", dumpLevel(DEBUG));
            put(zo, "05_all_last100.txt", dump());
            zo.close();
            d("log zip " + z.getName() + " " + z.length() + "B");
            return z;
        } catch (Exception e) {
            e("writeZip", e);
            return null;
        }
    }

    public static File writeZip(Context ctx) {
        if (ctx == null) return null;
        File dir = ctx.getCacheDir();
        return writeZip(dir);
    }

    private static void put(ZipOutputStream zo, String name, String text) throws Exception {
        zo.putNextEntry(new ZipEntry(name));
        zo.write((text == null ? "" : text).getBytes("UTF-8"));
        zo.closeEntry();
    }
}
