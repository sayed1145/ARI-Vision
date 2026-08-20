package com.ari.recog;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Gallery grouped by label. Multi-add to the same name.
 * Empty labels stay until the user deletes the label itself.
 */
public class ModelActivity extends AppCompatActivity {

    private ObjectStore store;
    private LinearLayout grid;
    private TextView statusView;
    private String pendingAction = "add";
    private String replaceId = null;
    private String prefillLabel = null;

    private final ActivityResultLauncher<Intent> picker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                List<Uri> uris = urisOf(result.getData());
                if (uris.isEmpty()) return;
                List<Bitmap> bmps = decodeAll(uris);
                if (bmps.isEmpty()) {
                    toast(zh() ? "无法解码图片" : "Cannot decode image");
                    return;
                }
                if ("replace".equals(pendingAction) && replaceId != null) {
                    store.replaceImage(replaceId, bmps.get(0));
                    toast(zh() ? "已替换图片" : "Image replaced");
                    replaceId = null;
                    pendingAction = "add";
                    refresh();
                    return;
                }
                if (prefillLabel != null && !prefillLabel.isEmpty()) {
                    int n = store.addMany(bmps, prefillLabel);
                    toast((zh() ? "已加入 " : "Added ") + n + (zh() ? " 张到「" : " to ") + prefillLabel
                            + (zh() ? "」" : ""));
                    prefillLabel = null;
                    refresh();
                    return;
                }
                askLabelAndAdd(bmps);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.d("ModelActivity onCreate");
        store = ObjectStore.get(this);
        prefillLabel = getIntent().getStringExtra("prefill_label");
        LinearLayout[] box = new LinearLayout[1];
        UiKit.screen(this, box);
        LinearLayout col = box[0];
        UiKit.appBar(this, col,
                zh() ? "目标库" : "Object gallery",
                zh() ? "可一次选多张 · 同名=同一物体 · 空标签会保留直到你删掉"
                     : "Multi-select · same name = one object · empty labels stay");

        statusView = UiKit.label(this, "", UiKit.TEXT, 14);
        LinearLayout st = UiKit.card(this);
        st.addView(statusView);
        col.addView(st);

        col.addView(UiKit.primaryBtn(this,
                zh() ? "添加图片（可一次多张）" : "Add images (multi)",
                v -> {
                    pendingAction = "add";
                    prefillLabel = null;
                    pickImages();
                }));
        col.addView(UiKit.ghostBtn(this,
                zh() ? "导出全部目标" : "Export all",
                v -> exportAll()));

        UiKit.section(this, col, zh() ? "按标签（点开：热力图 / 改名 / 删标签）" : "By label (heat / rename / delete)");
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        col.addView(grid);
        refresh();
        if (prefillLabel != null) {
            pendingAction = "add";
            pickImages();
        }
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        picker.launch(Intent.createChooser(i, zh() ? "选择一张或多张" : "Pick one or more"));
    }

    static List<Uri> urisOf(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    private List<Bitmap> decodeAll(List<Uri> uris) {
        List<Bitmap> out = new ArrayList<>();
        for (Uri uri : uris) {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                if (bmp != null) out.add(bmp);
            } catch (Exception e) {
                DebugLog.w("decode skip: " + e.getMessage());
            }
        }
        return out;
    }

    private void askLabelAndAdd(List<Bitmap> bmps) {
        EditText input = new EditText(this);
        input.setHint(zh() ? "标签，同名会合到同一物体" : "Label — same name merges");
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.MUTED);
        input.setSingleLine(true);
        if (prefillLabel != null) input.setText(prefillLabel);
        new AlertDialog.Builder(this)
                .setTitle(zh() ? ("设置标签（" + bmps.size() + " 张）") : ("Label (" + bmps.size() + " shots)"))
                .setView(input)
                .setPositiveButton(zh() ? "添加" : "Add", (d, w) -> {
                    String lab = input.getText().toString();
                    int n = store.addMany(bmps, lab);
                    prefillLabel = null;
                    refresh();
                    toast((zh() ? "已添加 " : "Added ") + n + (zh() ? " 张" : ""));
                })
                .setNegativeButton(zh() ? "取消" : "Cancel", null)
                .show();
    }

    private void refresh() {
        List<String> labs = store.labels();
        statusView.setText((zh() ? "标签 " : "Labels ") + labs.size()
                + (zh() ? "  ·  图片 " : "  ·  shots ") + store.size()
                + (zh() ? "\n删光某标签的图后标签仍在，可继续加图或点进去删掉标签" : "\nEmpty labels stay until you delete the label"));
        grid.removeAllViews();
        if (labs.isEmpty()) {
            grid.addView(UiKit.label(this,
                    zh() ? "还没有标签。一次可以选多张图，填同一个名字。"
                         : "No labels yet. Multi-select shots under one name.",
                    UiKit.MUTED, 13));
            return;
        }
        int colCount = 2;
        int cell = (getResources().getDisplayMetrics().widthPixels - UiKit.dp(this, 52)) / colCount;
        LinearLayout row = null;
        for (int i = 0; i < labs.size(); i++) {
            if (i % colCount == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = UiKit.dp(this, 8);
                row.setLayoutParams(rlp);
                grid.addView(row);
            }
            final String lab = labs.get(i);
            List<ObjectStore.Item> shots = store.itemsOf(lab);
            int n = shots.size();
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(UiKit.round(UiKit.CARD, 16, this));
            int pad = UiKit.dp(this, 8);
            card.setPadding(pad, pad, pad, pad);
            ImageView iv = new ImageView(this);
            Bitmap bmp = shots.isEmpty() ? null : store.loadBitmap(shots.get(0));
            if (bmp != null) {
                iv.setImageBitmap(Bitmap.createScaledBitmap(bmp, cell - pad * 2, cell - pad * 2, true));
            } else {
                iv.setBackgroundColor(0xFF1E2A42);
            }
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(iv, new LinearLayout.LayoutParams(cell - pad * 2, cell - pad * 2));
            String title = n > 1 ? lab + "  ×" + n : (n == 0 ? lab : lab);
            TextView tv = UiKit.label(this, title, UiKit.TEXT, 14);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(tv);
            if (n == 0) {
                TextView sub = UiKit.label(this, zh() ? "空标签 · 点进去加图或删除" : "empty · add or delete",
                        UiKit.WARN, 11);
                sub.setGravity(Gravity.CENTER);
                card.addView(sub);
            } else if (n > 1) {
                TextView sub = UiKit.label(this, zh() ? "集合 · 共识模板" : "set · consensus",
                        UiKit.PRIMARY, 11);
                sub.setGravity(Gravity.CENTER);
                card.addView(sub);
            }
            card.setOnClickListener(v -> {
                Intent in = new Intent(this, LabelDetailActivity.class);
                in.putExtra("label", lab);
                startActivity(in);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            clp.leftMargin = UiKit.dp(this, 4);
            clp.rightMargin = UiKit.dp(this, 4);
            card.setLayoutParams(clp);
            row.addView(card);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) store.reload();
        if (grid != null) refresh();
    }

    private void exportAll() {
        List<ObjectStore.Item> items = store.all();
        if (items.isEmpty()) { toast(zh() ? "没有可导出的目标" : "Nothing to export"); return; }
        try {
            File zip = ObjectZip.export(this, store);
            if (zip != null) {
                Uri u = DownloadSaver.saveFileToDownload(this, zip.getName(), zip);
                toast(u != null ? (zh() ? "已导出到 下载/ARIRecog/" : "Exported") : (zh() ? "导出失败" : "Export failed"));
            }
        } catch (Exception e) {
            DebugLog.e("export objects", e);
            toast(e.getMessage());
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private boolean zh() { return !"en".equals(SettingsActivity.getLanguageCode(this)); }
}
