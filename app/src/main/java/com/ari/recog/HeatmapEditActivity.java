package com.ari.recog;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** Paint the 32×32 attention map so the detector looks where you point. */
public class HeatmapEditActivity extends AppCompatActivity {
    private String label;
    private HeatmapView view;
    private LabelBank bank;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        label = getIntent().getStringExtra("label");
        if (label == null) label = "object";
        ObjectStore store = ObjectStore.get(this);
        bank = new LabelBank(this, store);
        LabelBank.Pack pack = bank.of(label);

        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        LinearLayout col = box[0];
        UiKit.appBar(this, col,
                zh() ? "编辑热力图 · " + label : "Edit heatmap · " + label,
                zh() ? "涂红 = 更关注    涂暗 = 当噪声忽略" : "Paint hot = attend    cool = ignore as noise");

        view = new HeatmapView(this);
        view.setEditable(true);
        if (pack != null) {
            view.setHeat(pack.heat);
            List<ObjectStore.Item> shots = store.itemsOf(label);
            if (!shots.isEmpty()) {
                Bitmap cover = store.loadBitmap(shots.get(0));
                if (cover != null) view.setBackgroundBitmap(cover);
            }
        }
        LinearLayout card = UiKit.card(this);
        card.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 300)));
        col.addView(card);

        col.addView(UiKit.primaryBtn(this, zh() ? "模式：加重关注" : "Mode: emphasize", v -> {
            view.setPlus(true);
            Toast.makeText(this, zh() ? "涂抹会提高权重" : "Paint raises weight", Toast.LENGTH_SHORT).show();
        }));
        col.addView(UiKit.ghostBtn(this, zh() ? "模式：当作噪声" : "Mode: treat as noise", v -> {
            view.setPlus(false);
            Toast.makeText(this, zh() ? "涂抹会降低权重" : "Paint lowers weight", Toast.LENGTH_SHORT).show();
        }));
        col.addView(UiKit.ghostBtn(this, zh() ? "恢复自动热力图" : "Reset to auto", v -> {
            if (pack != null) view.setHeat(pack.autoHeat);
            bank.heats().clear(label);
            Toast.makeText(this, zh() ? "已恢复自动" : "Auto restored", Toast.LENGTH_SHORT).show();
        }));
        col.addView(UiKit.primaryBtn(this, zh() ? "保存热力图" : "Save heatmap", v -> {
            boolean ok = bank.heats().save(label, view.getHeat());
            bank.invalidate();
            Toast.makeText(this, ok ? (zh() ? "已保存，识别会按新热力图" : "Saved")
                    : (zh() ? "保存失败" : "Save failed"), Toast.LENGTH_SHORT).show();
            if (ok) finish();
        }));
    }

    private boolean zh() { return !"en".equals(SettingsActivity.getLanguageCode(this)); }
}
