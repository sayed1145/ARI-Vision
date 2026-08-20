package com.ari.recog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Confirmation tracker.
 * A box is shown only after 2 consistent same-label hits. Unmatched tracks
 * die after 1 miss. High-conf first hits are NOT shown (that was the v2.4 leak).
 */
public final class BoxTracker {

    public static final class Track {
        int x, y, w, h;
        String label;
        float conf;
        int miss;
        int hits;
        int id;
        int vx, vy;
        Track(int id, ObjectDetector.Hit hit) {
            this.id = id;
            x = hit.x; y = hit.y; w = hit.w; h = hit.h;
            label = hit.label;
            conf = hit.conf;
            hits = 1;
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private static final int MAX_MISS = 1;
    private static final int NEED_HITS = 2;
    private static final int MAX_TRACKS = 8;
    private static final float MIN_CONF = 0.58f;

    public synchronized void clear() { tracks.clear(); }

    public synchronized List<ObjectDetector.Hit> update(List<ObjectDetector.Hit> dets) {
        boolean[] used = new boolean[dets.size()];
        for (Track t : tracks) t.miss++;

        for (int i = 0; i < dets.size(); i++) {
            ObjectDetector.Hit d = dets.get(i);
            int best = -1;
            float bestScore = 0.35f;
            for (int k = 0; k < tracks.size(); k++) {
                Track t = tracks.get(k);
                float sc = affinity(t, d);
                if (sc > bestScore) { bestScore = sc; best = k; }
            }
            if (best >= 0) {
                Track t = tracks.get(best);
                int nx = (t.x * 2 + d.x) / 3;
                int ny = (t.y * 2 + d.y) / 3;
                t.vx = (t.vx * 3 + (nx - t.x) * 2) / 5;
                t.vy = (t.vy * 3 + (ny - t.y) * 2) / 5;
                t.x = nx + t.vx;
                t.y = ny + t.vy;
                t.w = (t.w * 2 + d.w) / 3;
                t.h = (t.h * 2 + d.h) / 3;
                t.conf = d.conf;
                t.label = d.label;
                t.miss = 0;
                t.hits++;
                used[i] = true;
            }
        }
        for (int i = 0; i < dets.size(); i++) {
            if (used[i]) continue;
            if (tracks.size() >= MAX_TRACKS * 2) break;
            tracks.add(new Track(nextId++, dets.get(i)));
        }
        List<Track> keep = new ArrayList<>();
        for (Track t : tracks) if (t.miss <= MAX_MISS) keep.add(t);
        tracks.clear();
        tracks.addAll(keep);

        List<ObjectDetector.Hit> out = new ArrayList<>();
        for (Track t : tracks) {
            if (t.hits < NEED_HITS) continue;
            if (t.conf < MIN_CONF) continue;
            ObjectDetector.Hit h = new ObjectDetector.Hit(t.x, t.y, t.w, t.h, t.label, t.conf, "track");
            out.add(h);
        }
        Collections.sort(out, new Comparator<ObjectDetector.Hit>() {
            @Override public int compare(ObjectDetector.Hit a, ObjectDetector.Hit b) {
                return Float.compare(b.conf, a.conf);
            }
        });
        List<ObjectDetector.Hit> nms = new ArrayList<>();
        for (ObjectDetector.Hit b : out) {
            boolean ok = true;
            for (ObjectDetector.Hit k : nms) {
                int ix0 = Math.max(b.x, k.x), iy0 = Math.max(b.y, k.y);
                int ix1 = Math.min(b.x + b.w, k.x + k.w), iy1 = Math.min(b.y + b.h, k.y + k.h);
                int inter = Math.max(0, ix1 - ix0) * Math.max(0, iy1 - iy0);
                int union = b.w * b.h + k.w * k.h - inter;
                if (union > 0 && inter / (float) union > 0.35f) { ok = false; break; }
            }
            if (ok) nms.add(b);
            if (nms.size() >= MAX_TRACKS) break;
        }
        for (int i = 0; i < nms.size(); i++) nms.get(i).serial = i + 1;
        DetectStats.tracked = nms.size();
        return nms;
    }

    /**
     * Objects must share a label. Digits may flicker 3↔5 at the same spot.
     * Cross-class match is forbidden — that is how leftover digit tracks
     * used to be kept alive by object false positives.
     */
    private static float affinity(Track t, ObjectDetector.Hit d) {
        boolean same = t.label != null && t.label.equals(d.label);
        boolean digitFlicker = isDigitLabel(t.label) && isDigitLabel(d.label);
        if (!same && !digitFlicker) return 0f;
        float cx1 = t.x + t.w / 2f, cy1 = t.y + t.h / 2f;
        float cx2 = d.x + d.w / 2f, cy2 = d.y + d.h / 2f;
        float dist = (float) Math.hypot(cx1 - cx2, cy1 - cy2);
        float scale = Math.max(Math.max(t.h, d.h), 8);
        float center = 1f - Math.min(1f, dist / (0.75f * scale));
        int ix0 = Math.max(t.x, d.x), iy0 = Math.max(t.y, d.y);
        int ix1 = Math.min(t.x + t.w, d.x + d.w), iy1 = Math.min(t.y + t.h, d.y + d.h);
        int inter = Math.max(0, ix1 - ix0) * Math.max(0, iy1 - iy0);
        int union = t.w * t.h + d.w * d.h - inter;
        float iou = union <= 0 ? 0 : inter / (float) union;
        float lab = same ? 0.15f : 0f;
        return 0.55f * center + 0.30f * iou + lab;
    }

    static boolean isDigitLabel(String s) {
        return s != null && s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '9';
    }
}
