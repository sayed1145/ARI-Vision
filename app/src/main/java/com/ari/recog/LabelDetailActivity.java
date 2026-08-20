package com.ari.recog;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-label detail: multi-add, multi-delete, rename / delete label, heatmap. */
public class LabelDetailActivity extends AppCompatActivity {
    private String label;
    private ObjectStore store;
    private LabelBank bank;
    private HeatmapStore heats;
    private LinearLayout col;
    private boolean selectMode;
    private final Set<String> selected = new HashSet<>();

    private final ActivityResultLauncher<Intent> adder =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                List<Uri> uris = ModelActivity.urisOf(result.getData());
                List<Bitmap> bmps = new ArrayList<>();
                for (Uri uri : uris) {
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (is != null) is.close();
                        if (bmp != null) bmps.add(bmp);
                    } catch (Exception e) {
                        DebugLog.w("add decode: " + e.getMessage());
                    }
                }
                int n = store.addMany(bmps, label);
                bank.invalidate();
                toast((zh() ? "已加入 " : "Added ") + n + (zh() ? " 张" : ""));
                rebuild();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        label = getIntent().getStringExtra("label");
        if (label == null) label = "object";
        store = ObjectStore.get(this);
        bank = new LabelBank(this, store);
        heats = new HeatmapStore(this);
        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        col = box[0];
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bank.invalidate();
        rebuild();
    }

    private void rebuild() {
        col.removeAllViews();
        List<ObjectStore.Item> shots = store.itemsOf(label);
        int n = shots.size();
        String title = n > 1 ? label + "  ×" + n : label;
        UiKit.appBar(this, col, title,
                n == 0 ? (zh() ? "空标签仍保留 · 可继续加图或删除这个标签"
                               : "Empty label kept · add shots or delete the label")
                       : (zh() ? "同标签 = 同一物体 · 可一次加/删多张"
                               : "Same label = one object · batch add/delete"));

        LabelBank.Pack pack = bank.of(label);

        LinearLayout actions = UiKit.card(this);
        actions.addView(UiKit.primaryBtn(this,
                zh() ? "一次加入多张图" : "Add multiple shots",
                v -> pickMore()));
        actions.addView(UiKit.ghostBtn(this,
                zh() ? "改名（改整个标签）" : "Rename label",
                v -> renameLabel()));
        actions.addView(UiKit.dangerBtn(this,
                zh() ? "删除这个标签" : "Delete this label",
                v -> confirmDeleteLabel()));
        col.addView(actions);

        if (pack != null && n > 0) {
            LinearLayout meta = UiKit.card(this);
            meta.addView(UiKit.label(this,
                    zh() ? ("样本 " + n + "  ·  " + (pack.userHeat ? "用户热力图" : "自动热力图"))
                         : (n + " shots · " + (pack.userHeat ? "user heat" : "auto heat")),
                    UiKit.TEXT, 14));
            meta.addView(UiKit.label(this, LabelBank.shortFormula(pack.consensus), UiKit.MUTED, 12));
            col.addView(meta);

            UiKit.section(this, col, zh() ? "热力图（红=更关注）" : "Heatmap (red = attend)");
            HeatmapView hv = new HeatmapView(this);
            hv.setHeat(pack.heat);
            Bitmap cover = store.loadBitmap(shots.get(0));
            if (cover != null) hv.setBackgroundBitmap(cover);
            LinearLayout heatCard = UiKit.card(this);
            heatCard.addView(hv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 280)));
            col.addView(heatCard);
            col.addView(UiKit.primaryBtn(this,
                    zh() ? "编辑热力图" : "Edit heatmap",
                    v -> {
                        Intent i = new Intent(this, HeatmapEditActivity.class);
                        i.putExtra("label", label);
                        startActivity(i);
                    }));

            UiKit.section(this, col, zh() ? "模板显式表达式" : "Explicit template");
            LinearLayout fx = UiKit.card(this);
            TextView fxt = UiKit.label(this, pack.formula(true), UiKit.MUTED, 12);
            fxt.setTypeface(android.graphics.Typeface.MONOSPACE);
            fx.addView(fxt);
            col.addView(fx);
        } else {
            col.addView(UiKit.label(this,
                    zh() ? "这个标签还没有图片。点上面「一次加入多张图」。标签本身不会因为删光图片而消失。"
                         : "No shots yet. Add some. The label stays after you delete every file.",
                    UiKit.MUTED, 13));
        }

        UiKit.section(this, col, zh() ? "该标签下的图片" : "Shots");
        if (n > 0) {
            col.addView(UiKit.ghostBtn(this,
                    selectMode ? (zh() ? "取消选择" : "Cancel select")
                               : (zh() ? "选择多张删除" : "Select to delete"),
                    v -> {
                        selectMode = !selectMode;
                        selected.clear();
                        rebuild();
                    }));
            if (selectMode) {
                col.addView(UiKit.dangerBtn(this,
                        zh() ? ("删除所选（" + selected.size() + "）") : ("Delete selected (" + selected.size() + ")"),
                        v -> deleteSelected()));
            }
        }
        int cell = (getResources().getDisplayMetrics().widthPixels - UiKit.dp(this, 56)) / 3;
        LinearLayout row = null;
        for (int i = 0; i < shots.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = UiKit.dp(this, 8);
                row.setLayoutParams(rlp);
                col.addView(row);
            }
            final ObjectStore.Item it = shots.get(i);
            final int idx = i + 1;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(UiKit.round(selected.contains(it.id) ? 0xFF2A4A38 : UiKit.CARD, 12, this));
            int pad = UiKit.dp(this, 4);
            card.setPadding(pad, pad, pad, pad);
            ImageView iv = new ImageView(this);
            Bitmap bmp = store.loadBitmap(it);
            if (bmp != null) iv.setImageBitmap(Bitmap.createScaledBitmap(bmp, cell - pad * 2, cell - pad * 2, true));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(iv, new LinearLayout.LayoutParams(cell - pad * 2, cell - pad * 2));
            if (selectMode) {
                CheckBox cb = new CheckBox(this);
                cb.setText("#" + idx);
                cb.setTextColor(UiKit.TEXT);
                cb.setChecked(selected.contains(it.id));
                cb.setOnCheckedChangeListener((b, on) -> {
                    if (on) selected.add(it.id);
                    else selected.remove(it.id);
                });
                card.addView(cb);
            } else {
                TextView tv = UiKit.label(this, "#" + idx, UiKit.MUTED, 11);
                tv.setGravity(Gravity.CENTER);
                card.addView(tv);
                final LabelBank.Pack p2 = pack;
                card.setOnClickListener(v -> showShot(it, idx, p2));
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            clp.leftMargin = UiKit.dp(this, 4);
            clp.rightMargin = UiKit.dp(this, 4);
            card.setLayoutParams(clp);
            row.addView(card);
        }
    }

    private void pickMore() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        adder.launch(Intent.createChooser(i, zh() ? "选择一张或多张" : "Pick one or more"));
    }

    private void renameLabel() {
        EditText input = new EditText(this);
        input.setText(label);
        input.setSingleLine(true);
        input.setTextColor(0xFF111111);
        new AlertDialog.Builder(this)
                .setTitle(zh() ? "改标签名（下面所有图一起改）" : "Rename label (all shots)")
                .setView(input)
                .setPositiveButton(zh() ? "保存" : "Save", (d, w) -> {
                    String neu = ObjectStore.sanitizeLabel(input.getText().toString());
                    if (neu.equals(label)) return;
                    store.renameLabel(label, neu);
                    heats.rename(label, neu);
                    bank.invalidate();
                    label = neu;
                    toast(zh() ? "已改名" : "Renamed");
                    rebuild();
                })
                .setNegativeButton(zh() ? "取消" : "Cancel", null)
                .show();
    }

    private void confirmDeleteLabel() {
        new AlertDialog.Builder(this)
                .setTitle(zh() ? "删除标签「" + label + "」？" : "Delete label " + label + "?")
                .setMessage(zh() ? "会删掉这个标签下的全部图片和热力图，不能撤销。"
                                 : "All shots and the heatmap go away.")
                .setPositiveButton(zh() ? "删除标签" : "Delete", (d, w) -> {
                    store.purgeLabel(this, label);
                    bank.invalidate();
                    toast(zh() ? "标签和图片已全部删除" : "Label and shots purged");
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton(zh() ? "取消" : "Cancel", null)
                .show();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            toast(zh() ? "还没选" : "Nothing selected");
            return;
        }
        int n = store.removeMany(new ArrayList<>(selected));
        selected.clear();
        selectMode = false;
        bank.invalidate();
        toast((zh() ? "已删除 " : "Deleted ") + n);
        rebuild();
    }

    private void showShot(ObjectStore.Item it, int idx, LabelBank.Pack pack) {
        ColorTexture ft = store.subjectFeatures(it);
        String fx = (ft == null || pack == null) ? "" : ColorTexture.formulaOf(ft, pack.heat, true, 1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        box.setPadding(p, p, p, p);
        ImageView iv = new ImageView(this);
        Bitmap bmp = store.loadBitmap(it);
        if (bmp != null) iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(UiKit.dp(this, 180));
        box.addView(iv);
        TextView t = UiKit.label(this, fx, 0xFF111111, 11);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(0, p, 0, 0);
        box.addView(t);
        new AlertDialog.Builder(this)
                .setTitle((zh() ? "样本 #" : "Shot #") + idx)
                .setView(box)
                .setPositiveButton(zh() ? "关闭" : "Close", null)
                .setNegativeButton(zh() ? "删除这张" : "Delete shot", (d, w) -> {
                    store.remove(it.id);
                    bank.invalidate();
                    toast(zh() ? "已删除" : "Deleted");
                    rebuild();
                })
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private boolean zh() { return !"en".equals(SettingsActivity.getLanguageCode(this)); }
}
