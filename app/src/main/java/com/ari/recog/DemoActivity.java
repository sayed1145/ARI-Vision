package com.ari.recog;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/** DemoActivity — built-in MNIST samples, restyled. */
public class DemoActivity extends AppCompatActivity {

    private SampleImageProvider provider;
    private ARIClassifier classifier;
    private TextView resultView;
    private ImageView big;
    private int currentIndex = 0;
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.d("DemoActivity onCreate");
        provider = new SampleImageProvider(this);
        classifier = ImageEngine.getClassifier(this);

        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        LinearLayout col = box[0];
        UiKit.appBar(this, col,
                zh() ? "内置示例" : "Built-in demo",
                zh() ? "开箱即用 · 点缩略图即可识别" : "Tap a thumbnail to classify");

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout thumbRow = new LinearLayout(this);
        thumbRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < provider.size(); i++) {
            final int idx = i;
            SampleImageProvider.SampleImage s = provider.get(i);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(s.toBitmap());
            int p = UiKit.dp(this, 6);
            iv.setPadding(p, p, p, p);
            iv.setOnClickListener(v -> showSample(idx));
            thumbRow.addView(iv);
        }
        hsv.addView(thumbRow);
        col.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 120)));

        big = new ImageView(this);
        big.setAdjustViewBounds(true);
        big.setBackground(UiKit.round(UiKit.CARD, 16, this));
        col.addView(big, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 200)));

        resultView = UiKit.label(this, "", UiKit.TEXT, 20);
        resultView.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 8));
        col.addView(resultView);

        col.addView(UiKit.primaryBtn(this, zh() ? "识别当前示例" : "Recognize current", v -> recognizeCurrent()));
        col.addView(UiKit.ghostBtn(this, zh() ? "导出 10 张示例 zip" : "Export 10-sample zip", v -> saveCurrent()));

        if (provider.size() > 0) showSample(0);
    }

    private void showSample(int idx) {
        currentIndex = idx;
        SampleImageProvider.SampleImage s = provider.get(idx);
        currentBitmap = s.toBitmap();
        big.setImageBitmap(currentBitmap);
        float[] input = s.toInput784();
        ARIClassifier.Result r = classifier.predictFull(input);
        resultView.setText((zh() ? "示例 " : "Sample ") + s.label
                + "  →  " + r.digit
                + "   " + String.format("%.0f%%", r.conf * 100));
        resultView.setTextColor(r.digit == s.label ? UiKit.PRIMARY : UiKit.DANGER);
        DebugLog.d("Sample " + idx + " true=" + s.label + " pred=" + r.digit
                + " conf=" + String.format("%.3f", r.conf));
    }

    private void recognizeCurrent() {
        if (currentBitmap == null) {
            Toast.makeText(this, zh() ? "无示例" : "No sample", Toast.LENGTH_SHORT).show();
            return;
        }
        showSample(currentIndex);
    }

    private void saveCurrent() {
        java.util.List<ARITrainer.Sample> samples = new java.util.ArrayList<>();
        for (SampleImageProvider.SampleImage s : provider.all())
            samples.add(new ARITrainer.Sample(s.pixels, s.label));
        File zip = ZipDataIO.exportZip(this, samples, "ari_builtin_samples.zip");
        if (zip != null) {
            Uri u = DownloadSaver.saveFileToDownload(this, "ari_builtin_samples.zip", zip);
            if (u != null) {
                Toast.makeText(this, zh() ? "已导出到 下载/ARIRecog/" : "Exported to Download/ARIRecog/", Toast.LENGTH_LONG).show();
                DebugLog.d("Exported built-in samples zip");
            } else Toast.makeText(this, zh() ? "导出失败" : "Export failed", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean zh() { return !"en".equals(SettingsActivity.getLanguageCode(this)); }
}
