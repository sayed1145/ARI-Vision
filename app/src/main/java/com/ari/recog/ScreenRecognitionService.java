package com.ari.recog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground capture service.
 *  · 52dp pill is draggable; tap toggles; long-press stops.
 *  · OFF: clear overlay + cancel detect callbacks (no more frames processed).
 *  · ON: resume detect + overlay.
 *  · Tracker interpolates between full scans so motion stays smooth.
 */
public class ScreenRecognitionService extends Service {

    private static final String CHANNEL_ID = "ari_recog_channel";
    private static final int NOTIF_ID = 1001;
    private static volatile boolean running = false;
    private static volatile int lastCount = 0;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private HandlerThread detectThread;
    private Handler captureHandler;
    private Handler detectHandler;
    private volatile boolean recognizing = true;

    private WindowManager windowManager;
    private WindowManager.LayoutParams ballParams;
    private TextView ballView;
    private DetectionOverlayView overlayView;
    private int screenW, screenH;
    private volatile int captureW, captureH;
    private ObjectDetector detector;
    private final BoxTracker tracker = new BoxTracker();
    private int lastLogHits = -1, lastLogTracked = -1;

    public static boolean isRunning() { return running; }
    public static int lastCount() { return lastCount; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startForegroundCompat();
        captureThread = new HandlerThread("ari-capture");
        captureThread.start();
        detectThread = new HandlerThread("ari-detect");
        detectThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        detectHandler = new Handler(detectThread.getLooper());
        detectHandler.post(() -> {
            try {
                detector = new ObjectDetector(getApplicationContext());
                DebugLog.d("ObjectDetector ready objects=" + detector.store().size());
            } catch (Exception e) {
                DebugLog.e("ObjectDetector init", e);
            }
        });
        ensureBall();
        DebugLog.d("ScreenRecognitionService onCreate (async)");
    }

    private void startForegroundCompat() {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.screen_recog))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "ARI Screen", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("STOP", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra("RESULT_CODE")) {
            int resultCode = intent.getIntExtra("RESULT_CODE", 0);
            Intent data = intent.getParcelableExtra("DATA");
            DebugLog.d("onStartCommand projection result=" + resultCode);
            if (data != null) {
                try {
                    MediaProjectionManager mpm =
                            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    projection = mpm.getMediaProjection(resultCode, data);
                    DebugLog.d("MediaProjection created OK");
                    startCapture();
                } catch (Exception e) {
                    DebugLog.e("getMediaProjection failed", e);
                }
            }
        }
        return START_STICKY;
    }

    private void startCapture() {
        captureHandler.post(() -> {
            try {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
                captureW = screenW;
                captureH = screenH;
                int dpi = dm.densityDpi;
                imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
                virtualDisplay = projection.createVirtualDisplay("ARIScreenRecog",
                        screenW, screenH, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.getSurface(), null, captureHandler);
                new Handler(Looper.getMainLooper()).post(this::ensureDetectionOverlay);
                scheduleDetect();
                DebugLog.d("capture started " + screenW + "x" + screenH);
            } catch (Exception e) {
                DebugLog.e("startCapture failed", e);
            }
        });
    }

    private void ensureBall() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            int size = dp(52);
            ballParams = new WindowManager.LayoutParams(
                    size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            ballParams.gravity = Gravity.TOP | Gravity.START;
            int sw = getResources().getDisplayMetrics().widthPixels;
            ballParams.x = Math.max(dp(12), sw - dp(68));
            ballParams.y = dp(88);
            ballView = new TextView(this);
            styleBallOn();
            ballView.setOnTouchListener(new DragToggle());
            windowManager.addView(ballView, ballParams);
            DebugLog.d("floating ball added (52dp, draggable, default ON)");
        } catch (Exception e) {
            DebugLog.e("ensureBall failed", e);
        }
    }

    private void styleBallOn() {
        if (ballView == null) return;
        ballView.setText(lastCount > 0 ? String.valueOf(lastCount) : "ON");
        ballView.setTextSize(12);
        ballView.setGravity(android.view.Gravity.CENTER);
        ballView.setBackgroundColor(0xE03DDC97);
        ballView.setTextColor(0xFF06281C);
    }

    private void styleBallOff() {
        if (ballView == null) return;
        ballView.setText("OFF");
        ballView.setTextSize(12);
        ballView.setGravity(android.view.Gravity.CENTER);
        ballView.setBackgroundColor(0xE0FF5C7A);
        ballView.setTextColor(0xFFFFFFFF);
    }

    private final class DragToggle implements View.OnTouchListener {
        float downRawX, downRawY;
        int startX, startY;
        boolean dragged;
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = ballParams.x;
                    startY = ballParams.y;
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(e.getRawX() - downRawX);
                    int dy = Math.round(e.getRawY() - downRawY);
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    ballParams.x = startX + dx;
                    ballParams.y = startY + dy;
                    try { windowManager.updateViewLayout(ballView, ballParams); } catch (Exception ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragged) toggleRecognition();
                    return true;
            }
            return false;
        }
    }

    private void toggleRecognition() {
        recognizing = !recognizing;
        if (recognizing) {
            if (detector != null) detector.reloadSettings(getApplicationContext());
            styleBallOn();
            scheduleDetect();
            DebugLog.d("toggle -> true (resume render)");
        } else {
            stopDetectingAndClear();
            DebugLog.d("toggle -> false (clear render, stop detect)");
        }
    }

    /** OFF path: cancel work + wipe overlay. In-flight frame must not reschedule. */
    private void stopDetectingAndClear() {
        recognizing = false;
        lastCount = 0;
        tracker.clear();
        if (detectHandler != null) detectHandler.removeCallbacksAndMessages(null);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (overlayView != null) overlayView.clearBoxes();
            styleBallOff();
        });
    }

    private void ensureDetectionOverlay() {
        try {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            overlayView = new DetectionOverlayView(this);
            windowManager.addView(overlayView, p);
            DebugLog.d("detection overlay added (non-touchable)");
        } catch (Exception e) {
            DebugLog.e("ensureDetectionOverlay failed", e);
        }
    }

    private void scheduleDetect() {
        if (!recognizing || detectHandler == null) return;
        int fps = SettingsActivity.getFps(this);
        DetectStats.fpsSet = fps;
        int delay = Math.max(16, 1000 / Math.max(1, fps));
        detectHandler.postDelayed(this::detectFrame, delay);
    }

    /**
     * Recreate ImageReader + resize VirtualDisplay when the phone rotates.
     * Otherwise capture stays 1080x2342 while the overlay becomes 2342x1080
     * and every box is drawn off-screen.
     */
    private boolean syncCaptureToDisplay() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        if (windowManager != null) {
            android.graphics.Point pt = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(pt);
            if (pt.x >= 8 && pt.y >= 8) {
                // real size flips immediately on rotate; metrics in a Service can lag
                w = pt.x;
                h = pt.y;
            }
        }
        if (w < 8 || h < 8) return false;
        if (imageReader != null && w == captureW && h == captureH) return false;
        if (projection == null) return false;
        try {
            ImageReader prev = imageReader;
            int dpi = dm.densityDpi;
            ImageReader next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            if (virtualDisplay != null) {
                virtualDisplay.resize(w, h, dpi);
                virtualDisplay.setSurface(next.getSurface());
            } else {
                virtualDisplay = projection.createVirtualDisplay("ARIScreenRecog",
                        w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        next.getSurface(), null, captureHandler);
            }
            imageReader = next;
            captureW = w;
            captureH = h;
            screenW = w;
            screenH = h;
            if (prev != null) {
                try { prev.close(); } catch (Exception ignored) {}
            }
            new Handler(Looper.getMainLooper()).post(this::relayoutOverlay);
            DebugLog.d("capture resized " + w + "x" + h);
            return true;
        } catch (Exception e) {
            DebugLog.e("syncCaptureToDisplay", e);
            return false;
        }
    }

    private void relayoutOverlay() {
        if (overlayView == null || windowManager == null) return;
        try {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            p.width = WindowManager.LayoutParams.MATCH_PARENT;
            p.height = WindowManager.LayoutParams.MATCH_PARENT;
            windowManager.updateViewLayout(overlayView, p);
        } catch (Exception e) {
            DebugLog.e("relayoutOverlay", e);
        }
    }

    private void detectFrame() {
        if (!recognizing) return;
        Image image = null;
        try {
            if (detector == null) { scheduleDetect(); return; }
            if (syncCaptureToDisplay()) { scheduleDetect(); return; }
            if (imageReader == null) { scheduleDetect(); return; }
            detector.reloadSettings(getApplicationContext());
            boolean digitsOn = SettingsActivity.getDetectDigits(getApplicationContext());
            DetectStats.digitsOn = digitsOn;
            DetectStats.recognizing = recognizing;

            image = imageReader.acquireLatestImage();
            if (detector.store().size() == 0 && !digitsOn) {
                if (image != null) { try { image.close(); } catch (Exception ignored) {} image = null; }
                tracker.clear();
                lastCount = 0;
                publish(new ArrayList<ObjectDetector.Hit>());
                scheduleDetect();
                return;
            }
            if (image == null) { scheduleDetect(); return; }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer().duplicate();
            int w = image.getWidth(), h = image.getHeight();
            int rowStride = plane.getRowStride();
            List<ObjectDetector.Hit> hits = detector.detectRgba(buffer, rowStride, w, h, this);
            try { image.close(); } catch (Exception ignored) {}
            image = null;
            if (!recognizing) return;
            List<ObjectDetector.Hit> tracked = tracker.update(hits);
            detector.setLastTracks(tracked);
            lastCount = tracked.size();
            publish(tracked);
            if (hits.size() != lastLogHits || tracked.size() != lastLogTracked) {
                DebugLog.i("detect " + hits.size() + " tracked " + tracked.size());
                lastLogHits = hits.size();
                lastLogTracked = tracked.size();
            }
            scheduleDetect();
        } catch (IllegalStateException e) {
            DebugLog.d("detectFrame skip: buffer gone (service stopping)");
            if (recognizing) scheduleDetect();
        } catch (Exception e) {
            DebugLog.e("detectFrame err (recovering)", e);
            if (recognizing) scheduleDetect();
        } finally {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void publish(List<ObjectDetector.Hit> hits) {
        final List<ObjectDetector.Hit> snap = new ArrayList<>(hits);
        final int capW = Math.max(1, captureW != 0 ? captureW : screenW);
        final int capH = Math.max(1, captureH != 0 ? captureH : screenH);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!recognizing) return;
            int ow = overlayView != null ? overlayView.getWidth() : capW;
            int oh = overlayView != null ? overlayView.getHeight() : capH;
            if (ow <= 0) ow = capW;
            if (oh <= 0) oh = capH;
            boolean capLand = capW > capH;
            boolean ovLand = ow > oh;
            final List<DetectionOverlayView.Box> boxes = new ArrayList<>();
            // orientations disagree → overlay has not caught up; do not draw
            // capture-space boxes onto a rotated surface (they fly off-screen).
            if (capLand == ovLand) {
                float sx = ow / (float) capW;
                float sy = oh / (float) capH;
                for (ObjectDetector.Hit h : snap) {
                    int x = Math.round(h.x * sx);
                    int y = Math.round(h.y * sy);
                    int bw = Math.round(h.w * sx);
                    int bh = Math.round(h.h * sy);
                    boxes.add(new DetectionOverlayView.Box(x, y, bw, bh, h.label, h.conf, h.serial));
                }
            }
            final int n = boxes.size();
            if (overlayView != null) {
                overlayView.setBoxes(boxes);
                overlayView.setStatus("ARI  " + n);
                overlayView.invalidate();
            }
            if (ballView != null) {
                ballView.setText(n > 0 ? String.valueOf(n) : "ON");
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        recognizing = false;
        lastCount = 0;
        tracker.clear();
        try {
            if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
            if (detectHandler != null) detectHandler.removeCallbacksAndMessages(null);
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (projection != null) { projection.stop(); projection = null; }
            if (ballView != null && windowManager != null) windowManager.removeView(ballView);
            if (overlayView != null && windowManager != null) windowManager.removeView(overlayView);
            ballView = null; overlayView = null;
            if (captureThread != null) captureThread.quitSafely();
            if (detectThread != null) detectThread.quitSafely();
        } catch (Exception e) { DebugLog.e("onDestroy cleanup", e); }
        DebugLog.d("ScreenRecognitionService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
