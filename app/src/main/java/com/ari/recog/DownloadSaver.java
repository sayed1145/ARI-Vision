package com.ari.recog;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * DownloadSaver — 把 Bitmap 保存到 /storage/emulated/0/Download。
 * API 29+ 用 MediaStore，旧版本写公共 Download 目录。
 */
public final class DownloadSaver {

    public static Uri saveToDownload(Context ctx, String fileName, Bitmap bmp) {
        try {
            String name = sanitize(fileName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/ARIRecog");
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    os.flush();
                    os.close();
                    DebugLog.d("Saved to Download: " + name);
                    return uri;
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File sub = new File(dir, "ARIRecog");
                if (!sub.exists()) sub.mkdirs();
                File f = new File(sub, name);
                FileOutputStream fos = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
                DebugLog.d("Saved to " + f.getAbsolutePath());
                return Uri.fromFile(f);
            }
        } catch (Exception e) {
            DebugLog.e("saveToDownload failed", e);
        }
        return null;
    }

    private static String sanitize(String name) {
        String n = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!n.endsWith(".png")) n += ".png";
        return n;
    }

    /** 保存任意文件到 Download/ARIRecog。 */
    public static Uri saveFileToDownload(Context ctx, String fileName, File src) {
        try {
            String name = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/ARIRecog");
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    FileInputStream fis = new FileInputStream(src);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                    fis.close(); os.flush(); os.close();
                    DebugLog.d("Saved file to Download: " + name);
                    return uri;
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File sub = new File(dir, "ARIRecog");
                if (!sub.exists()) sub.mkdirs();
                File f = new File(sub, name);
                FileInputStream fis = new FileInputStream(src);
                FileOutputStream fos = new FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                fis.close(); fos.flush(); fos.close();
                DebugLog.d("Saved file to " + f.getAbsolutePath());
                return Uri.fromFile(f);
            }
        } catch (Exception e) {
            DebugLog.e("saveFileToDownload failed", e);
        }
        return null;
    }
}
