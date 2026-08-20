package com.ari.recog;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Locale;

/**
 * MainActivity — redesigned home: hero status + 2-column feature cards.
 */
public class MainActivity extends AppCompatActivity {

    private LinearLayout col;
    private TextView heroStatus;
    private TextView heroModel;
    private Locale currentLocale;
    private ActivityResultLauncher<String[]> permLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyLocale(SettingsActivity.getLanguageCode(this));

        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        col = box[0];

        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (allGranted()) Toast.makeText(this, s("granted"), Toast.LENGTH_SHORT).show();
                    else Toast.makeText(this, s("permission_required"), Toast.LENGTH_LONG).show();
                });

        build();
        requestEssentialPermissions();
        DebugLog.d("MainActivity buildMenu");
    }

    private void build() {
        UiKit.appBar(this, col,
                s("app_name"),
                zh() ? "实时跟踪 · 彩色物体 · 严格数字" : "Live track · color objects · strict digits");

        LinearLayout hero = UiKit.card(this);
        heroStatus = UiKit.label(this, "", UiKit.TEXT, 16);
        heroModel = UiKit.label(this, "", UiKit.MUTED, 13);
        heroModel.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 10));
        hero.addView(heroStatus);
        hero.addView(heroModel);
        hero.addView(UiKit.primaryBtn(this,
                zh() ? "启动屏幕识别" : "Start screen recognition",
                v -> startScreenRecog()));
        col.addView(hero);
        refreshHero();

        UiKit.section(this, col, zh() ? "功能" : "Features");
        col.addView(UiKit.row2(this,
                UiKit.featureCard(this, "🖥",
                        zh() ? "屏幕识别" : "Screen",
                        zh() ? "悬浮框实时检出" : "Live overlay boxes",
                        v -> startScreenRecog()),
                UiKit.featureCard(this, "🖼",
                        zh() ? "图片识别" : "Image",
                        zh() ? "相册 / 相机 / 演示" : "Gallery / camera / demo",
                        v -> open(RecognizeActivity.class, "Open RecognizeActivity"))));
        col.addView(UiKit.row2(this,
                UiKit.featureCard(this, "🗂",
                        zh() ? "目标库" : "Gallery",
                        zh() ? "多图同标签 · 改名删标签" : "Multi-shot · rename / delete",
                        v -> open(ModelActivity.class, "Open ModelActivity")),
                UiKit.featureCard(this, "📋",
                        zh() ? "调试日志" : "Debug",
                        zh() ? "复制完整诊断" : "Copy diagnostics",
                        v -> open(DebugActivity.class, "Open DebugActivity"))));

        col.addView(UiKit.ghostBtn(this,
                zh() ? "设置 · 语言 / 灵敏度" : "Settings · language / sensitivity",
                v -> open(SettingsActivity.class, "Open SettingsActivity")));

        TextView about = UiKit.label(this,
                zh() ? "ARI v2.9  ·  删标签即从界面消失  ·  资源释放"
                     : "ARI v2.9  ·  delete label leaves the UI  ·  resources freed",
                UiKit.MUTED, 12);
        about.setPadding(0, UiKit.dp(this, 16), 0, 0);
        col.addView(about);
    }

    private void refreshHero() {
        boolean run = ScreenRecognitionService.isRunning();
        if (heroStatus != null) {
            heroStatus.setText(run
                    ? (zh() ? "●  识别服务运行中" : "●  Recognition service running")
                    : (zh() ? "○  识别服务未运行" : "○  Recognition service idle"));
            heroStatus.setTextColor(run ? UiKit.PRIMARY : UiKit.MUTED);
        }
        if (heroModel != null) {
            File active = new File(getFilesDir(), "active_model.bin");
            String model = active.exists()
                    ? (zh() ? "当前模型：用户训练" : "Model: user-trained")
                    : (zh() ? "当前模型：内置 ARI（93.1%）" : "Model: built-in ARI (93.1%)");
            heroModel.setText(model);
        }
    }

    private void startScreenRecog() {
        DebugLog.d("Open ScreenRecogActivity");
        startActivity(new Intent(this, ScreenRecogActivity.class));
    }

    private void open(Class<?> cls, String log) {
        DebugLog.d(log);
        startActivity(new Intent(this, cls));
    }

    private boolean zh() {
        return currentLocale == null || "zh".equals(currentLocale.getLanguage());
    }

    private boolean allGranted() {
        for (String p : requiredPermissions())
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33)
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.POST_NOTIFICATIONS};
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private void requestEssentialPermissions() {
        if (!allGranted()) permLauncher.launch(requiredPermissions());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHero();
        Locale loc = SettingsActivity.getLanguageLocale(this);
        if (currentLocale == null || !loc.getLanguage().equals(currentLocale.getLanguage())) {
            recreate();
        }
    }

    private void applyLocale(String code) {
        currentLocale = "en".equals(code) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Locale.setDefault(currentLocale);
        android.content.res.Configuration config = new android.content.res.Configuration();
        config.setLocale(currentLocale);
        getBaseContext().getResources().updateConfiguration(config,
                getBaseContext().getResources().getDisplayMetrics());
    }

    private String s(String key) {
        int resId = getResources().getIdentifier(key, "string", getPackageName());
        return resId == 0 ? key : getString(resId);
    }
}
