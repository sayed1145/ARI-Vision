package com.ari.recog;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Export object gallery as zip: config.json + img/*.png */
public final class ObjectZip {
    private ObjectZip() {}

    public static File export(Context ctx, ObjectStore store) {
        try {
            File dir = new File(ctx.getCacheDir(), "export");
            if (!dir.exists()) dir.mkdirs();
            File zip = new File(dir, "ari_objects.zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            byte[] buf = new byte[8192];
            for (ObjectStore.Item it : store.all()) {
                String path = "img/" + it.file;
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("label", it.label);
                o.put("file", path);
                arr.put(o);
                File src = store.fileOf(it);
                if (!src.exists()) continue;
                zos.putNextEntry(new ZipEntry(path));
                FileInputStream fis = new FileInputStream(src);
                int n;
                while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                fis.close();
                zos.closeEntry();
            }
            root.put("objects", arr);
            zos.putNextEntry(new ZipEntry("config.json"));
            zos.write(root.toString(2).getBytes("UTF-8"));
            zos.closeEntry();
            zos.close();
            DebugLog.d("Exported objects zip " + zip.getAbsolutePath());
            return zip;
        } catch (Exception e) {
            DebugLog.e("ObjectZip.export", e);
            return null;
        }
    }
}
