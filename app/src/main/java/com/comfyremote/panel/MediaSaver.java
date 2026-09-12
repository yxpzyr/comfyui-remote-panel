package com.comfyremote.panel;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;

public final class MediaSaver {
    private MediaSaver() {}

    public static Uri saveImage(Context context, byte[] data, String filename, String mime) throws Exception {
        if (filename == null || filename.trim().isEmpty()) filename = "comfy_" + System.currentTimeMillis() + ".png";
        if (mime == null || mime.trim().isEmpty()) mime = guessMime(filename);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyRemote");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("无法创建相册文件");
        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) throw new Exception("无法打开相册输出流");
            os.write(data);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    public static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }
}
