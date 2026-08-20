package com.ari.recog;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/** Live detector console: last-frame gates + last 100 logs by severity. Download zip, no copy. */
public class DebugActivity extends AppCompatActivity {
    private TextView live;
    private TextView logView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (live != null) live.setText(DetectStats.dump());
            if (logView != null) logView.setText(DebugLog.dump());
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        LinearLayout col = box[0];
        UiKit.appBar(this, col,
                zh() ? "调试台" : "Debug console",
                zh() ? "最近一帧 + 最近 100 条日志（按重要程度）" : "Last frame + last 100 logs by severity");

        LinearLayout card = UiKit.card(this);
        live = UiKit.label(this, DetectStats.dump(), UiKit.MUTED, 12);
        live.setTypeface(android.graphics.Typeface.MONOSPACE);
        card.addView(live);
        col.addView(card);

        col.addView(UiKit.primaryBtn(this,
                zh() ? "下载日志压缩包（按重要程度分文件）" : "Download log zip (split by severity)",
                v -> exportZip()));

        col.addView(UiKit.ghostBtn(this,
                SettingsActivity.getDebugOverlay(this)
                        ? (zh() ? "关闭悬浮调试条" : "Hide overlay debug bar")
                        : (zh() ? "打开悬浮调试条" : "Show overlay debug bar"),
                v -> {
                    boolean on = !SettingsActivity.getDebugOverlay(this);
                    SettingsActivity.setDebugOverlay(this, on);
                    Toast.makeText(this, on ? (zh() ? "已开调试条" : "Debug bar on")
                            : (zh() ? "已关调试条" : "Debug bar off"), Toast.LENGTH_SHORT).show();
                    recreate();
                }));

        TextView hint = UiKit.label(this,
                zh() ? "日志只保留最近 100 条。[E]错误 [W]警告 [I]关键事件 [D]调试。\n"
                        + "不再复制到剪贴板，点上方按钮下载 zip：\n"
                        + "01_error / 02_warn / 03_info / 04_debug / 05_all + 帧快照。"
                     : "Last 100 lines. [E]rror [W]arn [I]nfo [D]ebug.\n"
                        + "Download zip instead of clipboard.",
                UiKit.MUTED, 12);
        hint.setPadding(0, UiKit.dp(this, 12), 0, 0);
        col.addView(hint);

        logView = UiKit.label(this, DebugLog.dump(), UiKit.MUTED, 11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout logCard = UiKit.card(this);
        logCard.addView(UiKit.label(this, zh() ? "事件日志（最近 100）" : "Event log (last 100)", UiKit.TEXT, 14));
        logCard.addView(logView);
        col.addView(logCard);
    }

    private void exportZip() {
        try {
            File z = DebugLog.writeZip(this);
            if (z == null) {
                toast(zh() ? "打包失败" : "Zip failed");
                return;
            }
            Uri u = DownloadSaver.saveFileToDownload(this, z.getName(), z);
            toast(u != null
                    ? (zh() ? "已下载到 下载/ARIRecog/" + z.getName() : "Saved to Download/ARIRecog/")
                    : (zh() ? "保存失败" : "Save failed"));
        } catch (Exception e) {
            DebugLog.e("exportZip", e);
            toast(String.valueOf(e.getMessage()));
        }
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private boolean zh() { return !"en".equals(SettingsActivity.getLanguageCode(this)); }
}
