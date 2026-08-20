package com.ari.recog;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.util.List;

public class RecognizeActivity extends AppCompatActivity {

    private ObjectDetector detector;
    private ImageView imageView;
    private TextView resultView;
    private Bitmap currentBitmap;

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        currentBitmap = BitmapFactory.decodeStream(is);
                        imageView.setImageBitmap(currentBitmap);
                        resultView.setText(zh() ? "已加载，点「识别」" : "Loaded. Tap Recognize.");
                    } catch (Exception e) {
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Bundle extras = result.getData().getExtras();
                    if (extras != null) {
                        Bitmap photo = (Bitmap) extras.get("data");
                        if (photo != null) {
                            currentBitmap = photo;
                            imageView.setImageBitmap(photo);
                            runRecognition(photo);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        detector = new ObjectDetector(this);
        DebugLog.d("RecognizeActivity onCreate, model loaded");

        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        LinearLayout col = box[0];
        UiKit.appBar(this, col,
                zh() ? "图片识别" : "Image recognition",
                zh() ? "彩色物体 + 严格数字" : "Color objects + strict digits");

        imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackground(UiKit.round(UiKit.CARD, 16, this));
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 260));
        imgLp.bottomMargin = UiKit.dp(this, 12);
        col.addView(imageView, imgLp);

        resultView = UiKit.label(this, zh() ? "选择一张图片开始" : "Pick an image", UiKit.TEXT, 16);
        col.addView(resultView);

        col.addView(UiKit.primaryBtn(this, zh() ? "选择图片" : "Choose image",
                v -> galleryLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"))));
        col.addView(UiKit.accentBtn(this, zh() ? "相机拍摄" : "Camera", v -> openCamera()));
        col.addView(UiKit.ghostBtn(this, zh() ? "识别" : "Recognize", v -> {
            if (currentBitmap != null) runRecognition(currentBitmap);
            else Toast.makeText(this, zh() ? "未选择图片" : "No image", Toast.LENGTH_SHORT).show();
        }));
        col.addView(UiKit.ghostBtn(this, zh() ? "内置演示（10 张手写）" : "Built-in demo (10 samples)",
                v -> startActivity(new Intent(this, DemoActivity.class))));
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.permission_camera), Toast.LENGTH_LONG).show();
            return;
        }
        cameraLauncher.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
    }

    private void runRecognition(Bitmap bmp) {
        List<ObjectDetector.Hit> hits = detector.detectBitmap(bmp);
        if (hits.isEmpty()) {
            resultView.setText(zh() ? "未检出目标。可到「目标库」添加彩色样本。"
                    : "No targets. Add color samples in the gallery.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(zh() ? "检出 " : "Found ").append(hits.size()).append(zh() ? " 个: " : ": ");
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) sb.append("  ");
                ObjectDetector.Hit h = hits.get(i);
                sb.append(h.label).append("(").append(Math.round(h.conf * 100)).append("%)");
            }
            resultView.setText(sb.toString());
        }
        DebugLog.d("RecognizeActivity hits=" + hits.size());
    }

    private boolean zh() { return !"en".equals(SettingsActivity.getLanguageCode(this)); }
}
