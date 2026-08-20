package com.ari.recog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

/**
 * ScreenRecogActivity — permission gate + live status for the overlay service.
 */
public class ScreenRecogActivity extends AppCompatActivity {

    private TextView statusView;
    private TextView liveView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshLive();
            ui.postDelayed(this, 700);
        }
    };

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    DebugLog.d("Screen capture permission granted, forwarding to service");
                    Intent svc = new Intent(this, ScreenRecognitionService.class);
                    svc.putExtra("RESULT_CODE", result.getResultCode());
                    svc.putExtra("DATA", result.getData());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
                    else startService(svc);
                    statusView.setText(zh() ? "已授权，悬浮窗正在捕捉屏幕…" : "Authorized. Overlay is capturing…");
                    statusView.setTextColor(UiKit.PRIMARY);
                    Toast.makeText(this,
                            zh() ? "屏幕识别已启动。点右上角胶囊开关，长按结束。"
                                 : "Screen recognition started. Tap the pill to toggle, long-press to stop.",
                            Toast.LENGTH_LONG).show();
                } else {
                    DebugLog.d("Screen capture permission denied");
                    statusView.setText(zh() ? "未授权屏幕捕获" : "Screen capture denied");
                    statusView.setTextColor(UiKit.DANGER);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.d("ScreenRecogActivity onCreate");
        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        LinearLayout col = box[0];

        UiKit.appBar(this, col,
                zh() ? "屏幕实时识别" : "Live screen recognition",
                zh() ? "在任意界面上框出数字，不拦截触摸" : "Boxes digits on any screen, never steals touches");

        LinearLayout card = UiKit.card(this);
        statusView = UiKit.label(this,
                zh() ? "点击下方按钮开始。需要悬浮窗 + 屏幕捕获授权。"
                     : "Tap start. Overlay + screen-capture permission required.",
                UiKit.TEXT, 15);
        liveView = UiKit.label(this, "", UiKit.MUTED, 13);
        liveView.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(statusView);
        card.addView(liveView);
        col.addView(card);

        LinearLayout how = UiKit.card(this);
        how.addView(UiKit.label(this,
                zh() ? "· 绿色胶囊可拖动；点一下开关，长按结束服务\n"
                        + "· 关闭后立即清框并停止检测；再开恢复渲染\n"
                        + "· 检出框不拦截点击，跟踪移动目标\n"
                        + "· 设置里可关掉「数字 0-9」，只认目标库里的彩色物体\n"
                        + "· 目标库支持任意英文/数字标签"
                     : "· Green pill is draggable; tap toggles, long-press stops\n"
                        + "· Off clears boxes and stops detect; On resumes\n"
                        + "· Overlay never steals touches; boxes track motion\n"
                        + "· Settings can disable digits 0-9 (gallery only)\n"
                        + "· Gallery labels can be any English / numbers",
                UiKit.MUTED, 13));
        col.addView(how);

        col.addView(UiKit.primaryBtn(this,
                zh() ? "开始屏幕识别" : "Start screen recognition",
                v -> requestAllPermissionsAndStart()));
        col.addView(UiKit.dangerBtn(this,
                zh() ? "停止识别" : "Stop recognition",
                v -> stopRecog()));
    }

    private void stopRecog() {
        Intent svc = new Intent(this, ScreenRecognitionService.class);
        svc.putExtra("STOP", true);
        startService(svc);
        stopService(svc);
        statusView.setText(zh() ? "屏幕识别已停止" : "Screen recognition stopped");
        statusView.setTextColor(UiKit.MUTED);
        DebugLog.d("Screen recog stopped");
    }

    private void requestAllPermissionsAndStart() {
        DebugLog.d("requestAllPermissionsAndStart");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            statusView.setText(zh() ? "需要悬浮窗权限（用于显示识别框）" : "Overlay permission required");
            statusView.setTextColor(UiKit.WARN);
            Toast.makeText(this, zh() ? "请开启悬浮窗权限" : "Please enable overlay permission", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projectionLauncher.launch(mpm.createScreenCaptureIntent());
        statusView.setText(zh() ? "请在系统弹窗中授权屏幕捕获…" : "Authorize screen capture…");
        statusView.setTextColor(UiKit.ACCENT);
    }

    private void refreshLive() {
        if (liveView == null) return;
        boolean run = ScreenRecognitionService.isRunning();
        int n = ScreenRecognitionService.lastCount();
        liveView.setText(run
                ? (zh() ? ("服务运行中 · 最近一帧 " + n + " 个目标") : ("Service running · last frame " + n + " targets"))
                : (zh() ? "服务未运行" : "Service idle"));
        liveView.setTextColor(run ? UiKit.PRIMARY : UiKit.MUTED);
    }

    @Override protected void onResume() { super.onResume(); ui.post(ticker); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(ticker); }

    private boolean zh() {
        return !"en".equals(SettingsActivity.getLanguageCode(this));
    }
}
