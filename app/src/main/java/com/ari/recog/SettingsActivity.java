package com.ari.recog;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** Settings — language, sensitivity, digit-detect toggle. */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "ari_settings";
    private static final String KEY_LANG = "lang";
    private static final String KEY_SENS = "sens";
    private static final String KEY_DIGITS = "detect_digits";
    private static final String KEY_DEBUG = "debug_overlay";
    private static final String KEY_FPS = "fps";
    private static final String KEY_PERF = "perf_overlay";
    private static final String KEY_ROI_L = "roi_l";
    private static final String KEY_ROI_T = "roi_t";
    private static final String KEY_ROI_R = "roi_r";
    private static final String KEY_ROI_B = "roi_b";
    private static final String KEY_ADAPT = "online_adapt";
    private static final String KEY_DENOISE = "denoise";
    private static final String KEY_DEFORM = "deform_robust";
    private static final String KEY_MOTION = "motion_predict";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        LinearLayout col = box[0];
        boolean zh = !"en".equals(getLanguageCode(this));
        UiKit.appBar(this, col,
                zh ? "设置" : "Settings",
                zh ? "语言 · 灵敏度 · 数字检测" : "Language · sensitivity · digits");

        LinearLayout lang = UiKit.card(this);
        lang.addView(UiKit.label(this, zh ? "界面语言" : "Language", UiKit.MUTED, 12));
        lang.addView(UiKit.primaryBtn(this, "中文", v -> setLang("zh")));
        lang.addView(UiKit.ghostBtn(this, "English", v -> setLang("en")));
        col.addView(lang);

        LinearLayout dig = UiKit.card(this);
        Switch sw = new Switch(this);
        sw.setText(zh ? "检测内置数字 0-9" : "Detect built-in digits 0-9");
        sw.setTextColor(UiKit.TEXT);
        sw.setChecked(getDetectDigits(this));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            getPrefs(this).edit().putBoolean(KEY_DIGITS, on).apply();
            DebugLog.d("detect_digits -> " + on);
        });
        dig.addView(sw);
        dig.addView(UiKit.label(this,
                zh ? "关掉后只识别你在「目标库」里添加的彩色/纹理物体，避免把界面文字当成数字。"
                   : "Off = only your gallery objects (color/texture). Avoids labeling UI text as digits.",
                UiKit.MUTED, 12));
        col.addView(dig);

        LinearLayout sens = UiKit.card(this);
        TextView sl = UiKit.label(this, "", UiKit.TEXT, 15);
        int cur = getSensitivity(this);
        sl.setText(sensLabel(zh, cur));
        SeekBar bar = new SeekBar(this);
        bar.setMax(2);
        bar.setProgress(cur);
        bar.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 8));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                getPrefs(SettingsActivity.this).edit().putInt(KEY_SENS, progress).apply();
                sl.setText(sensLabel(zh, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sens.addView(sl);
        sens.addView(UiKit.label(this,
                zh ? "严格：少误检　　标准：推荐　　灵敏：尽量多检出"
                   : "Strict: fewer FPs    Normal    Sensitive: more recall",
                UiKit.MUTED, 12));
        sens.addView(bar);
        col.addView(sens);

        LinearLayout dbg = UiKit.card(this);
        Switch dsw = new Switch(this);
        dsw.setText(zh ? "悬浮层显示调试条（候选/拒绝）" : "Overlay debug bar (cand/reject)");
        dsw.setTextColor(UiKit.TEXT);
        dsw.setChecked(getDebugOverlay(this));
        dsw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> setDebugOverlay(this, on));
        dbg.addView(dsw);
        dbg.addView(UiKit.label(this,
                zh ? "打开后屏幕顶栏会显示 cand / pass / tracked / rej。详细列表在「调试台」。"
                   : "Top bar shows cand / pass / tracked / rej. Full list is in Debug console.",
                UiKit.MUTED, 12));
        col.addView(dbg);

        LinearLayout fpsCard = UiKit.card(this);
        TextView fpsLab = UiKit.label(this, "", UiKit.TEXT, 15);
        int fps0 = getFps(this);
        fpsLab.setText((zh ? "刷新率：" : "FPS: ") + fps0 + " (default 10, max 60)");
        SeekBar fpsBar = new SeekBar(this);
        fpsBar.setMax(59);
        fpsBar.setProgress(fps0 - 1);
        fpsBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = p + 1;
                getPrefs(SettingsActivity.this).edit().putInt(KEY_FPS, v).apply();
                fpsLab.setText((zh ? "刷新率：" : "FPS: ") + v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        fpsCard.addView(fpsLab);
        fpsCard.addView(UiKit.label(this,
                zh ? "默认 10fps。工作画布固定 256px 宽，内存不随屏幕变。"
                   : "Default 10fps. Fixed 256px work canvas — memory does not grow with screen.",
                UiKit.MUTED, 12));
        fpsCard.addView(fpsBar);
        col.addView(fpsCard);

        LinearLayout perf = UiKit.card(this);
        Switch psw = new Switch(this);
        psw.setText(zh ? "默认显示性能条（内存 / 硬件 / FPS）" : "Show perf bar (mem / hw / FPS)");
        psw.setTextColor(UiKit.TEXT);
        psw.setChecked(getPerfOverlay(this));
        psw.setOnCheckedChangeListener((b, on) -> getPrefs(this).edit().putBoolean(KEY_PERF, on).apply());
        perf.addView(psw);
        col.addView(perf);

        LinearLayout roi = UiKit.card(this);
        roi.addView(UiKit.label(this, zh ? "识别范围（本地保存）" : "Detect ROI (persisted)", UiKit.TEXT, 15));
        TextView roiLab = UiKit.label(this, roiText(zh, getRoi(this)), UiKit.MUTED, 12);
        roi.addView(roiLab);
        String[] presets = zh
                ? new String[]{"全屏", "中央 60%", "上半", "下半"}
                : new String[]{"Full", "Center 60%", "Top", "Bottom"};
        float[][] pv = {{0,0,1,1},{0.2f,0.2f,0.8f,0.8f},{0,0,1,0.5f},{0,0.5f,1,1}};
        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final float[] boxv = pv[i];
            android.widget.Button b = new android.widget.Button(this);
            b.setText(presets[i]);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setOnClickListener(v -> {
                setRoi(this, boxv[0], boxv[1], boxv[2], boxv[3]);
                roiLab.setText(roiText(zh, getRoi(this)));
            });
            prow.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        roi.addView(prow);
        String[] names = zh ? new String[]{"左", "上", "右", "下"} : new String[]{"L", "T", "R", "B"};
        float[] curRoi = getRoi(this);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView tl = UiKit.label(this, names[i] + " " + Math.round(curRoi[i] * 100) + "%", UiKit.MUTED, 12);
            SeekBar sb = new SeekBar(this);
            sb.setMax(100);
            sb.setProgress(Math.round(curRoi[i] * 100));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                    float[] r = getRoi(SettingsActivity.this);
                    r[idx] = p / 100f;
                    setRoi(SettingsActivity.this, r[0], r[1], r[2], r[3]);
                    tl.setText(names[idx] + " " + p + "%");
                    roiLab.setText(roiText(zh, getRoi(SettingsActivity.this)));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            roi.addView(tl);
            roi.addView(sb);
        }
        col.addView(roi);

        LinearLayout rob = UiKit.card(this);
        rob.addView(UiKit.label(this, zh ? "鲁棒性（默认全开，不改基座模型）" : "Robustness (all on, base model frozen)", UiKit.TEXT, 15));
        rob.addView(sw(this, zh ? "在线学习（适应人物走动/光照）" : "Online adapt (pose / lighting)", getOnlineAdapt(this),
                (b, on) -> { getPrefs(this).edit().putBoolean(KEY_ADAPT, on).apply(); DebugLog.d("online_adapt -> " + on); }));
        rob.addView(sw(this, zh ? "去噪" : "Denoise", getDenoise(this),
                (b, on) -> getPrefs(this).edit().putBoolean(KEY_DENOISE, on).apply()));
        rob.addView(sw(this, zh ? "形变自适应（HOG/颜色为主）" : "Deform robust (HOG/color)", getDeformRobust(this),
                (b, on) -> getPrefs(this).edit().putBoolean(KEY_DEFORM, on).apply()));
        rob.addView(sw(this, zh ? "运动预测（FPS/人物跟随）" : "Motion predict (FPS follow)", getMotionPredict(this),
                (b, on) -> getPrefs(this).edit().putBoolean(KEY_MOTION, on).apply()));
        rob.addView(UiKit.dangerBtn(this, zh ? "清空在线学习（基座不动）" : "Wipe adapt (base untouched)", v -> {
            new AdaptBank(this).clear();
            android.widget.Toast.makeText(this, zh ? "已清空适应层" : "Adapt wiped", android.widget.Toast.LENGTH_SHORT).show();
        }));
        col.addView(rob);
    }

    private Switch sw(android.app.Activity a, String title, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(a);
        s.setText(title);
        s.setTextColor(UiKit.TEXT);
        s.setChecked(on);
        s.setOnCheckedChangeListener(l);
        return s;
    }

    private static String roiText(boolean zh, float[] r) {
        return String.format(java.util.Locale.US,
                zh ? "当前 ROI  L%.0f%% T%.0f%% R%.0f%% B%.0f%%" : "ROI L%.0f T%.0f R%.0f B%.0f",
                r[0] * 100, r[1] * 100, r[2] * 100, r[3] * 100);
    }

    private static String sensLabel(boolean zh, int v) {
        if (v <= 0) return zh ? "灵敏度：严格" : "Sensitivity: strict";
        if (v >= 2) return zh ? "灵敏度：灵敏" : "Sensitivity: high";
        return zh ? "灵敏度：标准" : "Sensitivity: normal";
    }

    private void setLang(String code) {
        getPrefs(this).edit().putString(KEY_LANG, code).apply();
        recreate();
    }

    public static SharedPreferences getPrefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static String getLanguageCode(Context c) {
        return getPrefs(c).getString(KEY_LANG, "zh");
    }
    public static Locale getLanguageLocale(Context c) {
        return "en".equals(getLanguageCode(c)) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
    }
    public static int getSensitivity(Context c) {
        return getPrefs(c).getInt(KEY_SENS, 1);
    }
    public static boolean getDetectDigits(Context c) {
        return getPrefs(c).getBoolean(KEY_DIGITS, true);
    }
    public static boolean getDebugOverlay(Context c) {
        return getPrefs(c).getBoolean(KEY_DEBUG, false);
    }
    public static void setDebugOverlay(Context c, boolean on) {
        getPrefs(c).edit().putBoolean(KEY_DEBUG, on).apply();
    }
    public static int getFps(Context c) {
        int v = getPrefs(c).getInt(KEY_FPS, 10);
        return v < 1 ? 1 : Math.min(60, v);
    }
    public static boolean getPerfOverlay(Context c) {
        return getPrefs(c).getBoolean(KEY_PERF, true);
    }
    public static float[] getRoi(Context c) {
        SharedPreferences p = getPrefs(c);
        float l = p.getFloat(KEY_ROI_L, 0f);
        float t = p.getFloat(KEY_ROI_T, 0f);
        float r = p.getFloat(KEY_ROI_R, 1f);
        float b = p.getFloat(KEY_ROI_B, 1f);
        if (r < l + 0.08f) r = Math.min(1f, l + 0.08f);
        if (b < t + 0.08f) b = Math.min(1f, t + 0.08f);
        return new float[]{clamp01(l), clamp01(t), clamp01(r), clamp01(b)};
    }
    public static void setRoi(Context c, float l, float t, float r, float b) {
        getPrefs(c).edit()
                .putFloat(KEY_ROI_L, clamp01(l)).putFloat(KEY_ROI_T, clamp01(t))
                .putFloat(KEY_ROI_R, clamp01(r)).putFloat(KEY_ROI_B, clamp01(b))
                .apply();
    }
    private static float clamp01(float v) { return v < 0 ? 0 : Math.min(1f, v); }
    public static boolean getOnlineAdapt(Context c) { return getPrefs(c).getBoolean(KEY_ADAPT, true); }
    public static boolean getDenoise(Context c) { return getPrefs(c).getBoolean(KEY_DENOISE, true); }
    public static boolean getDeformRobust(Context c) { return getPrefs(c).getBoolean(KEY_DEFORM, true); }
    public static boolean getMotionPredict(Context c) { return getPrefs(c).getBoolean(KEY_MOTION, true); }
}
