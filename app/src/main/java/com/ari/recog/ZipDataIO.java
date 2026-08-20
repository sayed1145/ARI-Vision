package com.ari.recog;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZipDataIO — 训练包 zip 导入/导出 + json 配置解析（带转义防注入）。
 *
 * zip 结构（基于压缩包工作目录）：
 *   config.json   { "samples": [ {"label": 3, "file": "img/3a.png"}, ... ] }
 *   img/*.png     图片文件（路径与 config 中 file 对应）
 *
 * 转义：所有外部字符串（标签、路径）用 JSON 序列化转义；路径解析时
 * 规范化 + 禁止跳出工作目录（防路径穿越注入）。
 */
public final class ZipDataIO {

    private ZipDataIO() {}

    /** 读取 zip 中 config.json 并加载全部样本。返回解析到的样本（不含像素，仅元数据）。 */
    public static ParseResult parseZip(InputStream zipStream) throws Exception {        ZipInputStream zis = new ZipInputStream(zipStream);
        Map<String, byte[]> files = new HashMap<>();
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            if (!e.isDirectory()) {
                files.put(e.getName(), readAll(zis));
            }
            zis.closeEntry();
        }
        zis.close();

        byte[] cfg = files.get("config.json");
        if (cfg == null) throw new Exception("zip 缺少 config.json");
        JSONObject root = new JSONObject(new String(cfg, "UTF-8"));
        JSONArray arr = root.optJSONArray("samples");
        if (arr == null) throw new Exception("config.json 缺少 samples 数组");

        ParseResult res = new ParseResult();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int label = o.optInt("label", -1);
            String path = o.optString("file", "");
            if (label < 0 || label > 9 || path.isEmpty()) continue;
            // 路径规范化 + 防穿越
            String norm = normalizeSafe(path);
            if (norm == null) continue;
            byte[] img = files.get(norm);
            if (img == null) img = files.get(path);
            if (img == null) continue;
            float[] px = decodeGray28(img);
            if (px != null) {
                res.samples.add(new ARITrainer.Sample(px, label));
                res.countPerLabel.merge(label, 1, Integer::sum);
            }
        }
        res.fileCount = files.size();
        return res;
    }

    /** 路径规范化：去 ./ .. 并确保不跳出根，非法返回 null。 */
    public static String normalizeSafe(String raw) {
        String p = raw.replace('\\', '/');
        // 去掉开头的 /
        while (p.startsWith("/")) p = p.substring(1);
        String[] parts = p.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (stack.isEmpty()) return null; // 试图跳出 → 拒绝
                stack.remove(stack.size() - 1);
            } else {
                stack.add(part);
            }
        }
        return String.join("/", stack);
    }

    /** 生成导出 zip（样本 + config.json），返回 zip 文件。 */
    public static File exportZip(Context ctx, List<ARITrainer.Sample> samples, String zipName) {
        try {
            File dir = new File(ctx.getCacheDir(), "export");
            if (!dir.exists()) dir.mkdirs();
            File zip = new File(dir, sanitize(zipName));
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
            // 收集每个样本的字节
            List<String> paths = new ArrayList<>();
            List<byte[]> datas = new ArrayList<>();
            int idx = 0;
            for (ARITrainer.Sample s : samples) {
                String p = "img/s_" + idx + ".bin"; // 灰度 28x28 原始字节
                paths.add(p);
                byte[] img = new byte[784];
                for (int j = 0; j < 784; j++) img[j] = (byte) Math.round(s.pixels[j] * 255);
                datas.add(img);
                idx++;
            }
            // config.json（JSON 转义）
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < samples.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("label", samples.get(i).label);
                o.put("file", paths.get(i));
                arr.put(o);
            }
            root.put("samples", arr);
            // 写 config.json
            zos.putNextEntry(new ZipEntry("config.json"));
            zos.write(root.toString(2).getBytes("UTF-8"));
            zos.closeEntry();
            // 写图片
            for (int i = 0; i < paths.size(); i++) {
                zos.putNextEntry(new ZipEntry(paths.get(i)));
                zos.write(datas.get(i));
                zos.closeEntry();
            }
            zos.close();
            DebugLog.d("Exported zip " + zip.getAbsolutePath() + " with " + samples.size() + " samples");
            return zip;
        } catch (Exception e) {
            DebugLog.e("exportZip failed", e);
            return null;
        }
    }

    /** 从 28x28 灰度字节(0-255 或 PNG) 解码为 784 float(0..1)。 */
    public static float[] decodeGray28(byte[] img) {
        try {
            if (img.length == 784) {
                float[] px = new float[784];
                for (int j = 0; j < 784; j++) px[j] = (img[j] & 0xFF) / 255f;
                return px;
            }
            // PNG/JPEG → Bitmap → 28x28 灰度
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(img, 0, img.length);
            if (bmp == null) return null;
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 28, 28, true);
            float[] px = new float[784];
            for (int j = 0; j < 784; j++) {
                int p = scaled.getPixel(j % 28, j / 28);
                int g = (android.graphics.Color.red(p) + android.graphics.Color.green(p)
                        + android.graphics.Color.blue(p)) / 3;
                px[j] = g / 255f;
            }
            return px;
        } catch (Exception e) {
            DebugLog.e("decodeGray28 failed", e);
            return null;
        }
    }

    public static class ParseResult {
        public List<ARITrainer.Sample> samples = new ArrayList<>();
        public Map<Integer, Integer> countPerLabel = new HashMap<>();
        public int fileCount = 0;
        public String summary() {
            StringBuilder sb = new StringBuilder("导入 ").append(samples.size()).append(" 样本（")
                    .append(fileCount).append(" 文件）\n");
            for (Map.Entry<Integer, Integer> e : countPerLabel.entrySet())
                sb.append("数字").append(e.getKey()).append(": ").append(e.getValue()).append("张\n");
            return sb.toString();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
