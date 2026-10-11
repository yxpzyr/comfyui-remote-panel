package com.comfyremote.panel;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class MediaSaver {
    private MediaSaver() {}

    public static Uri saveImage(Context context, byte[] data, String filename, String mime) throws Exception {
        if (data == null || data.length == 0) throw new Exception("图片内容为空");
        if (filename == null || filename.trim().isEmpty()) filename = "comfy_" + System.currentTimeMillis() + ".png";
        if (mime == null || mime.trim().isEmpty()) mime = guessMime(filename);

        String original = safeName(SubmissionFilenamePolicy.forLegacyDownload(filename, System.currentTimeMillis()));
        ContentResolver resolver = context.getContentResolver();
        Exception last = null;
        // A server may expose the same filename for several historical prompt_ids. Even
        // if the file can be read, Android MediaStore must not reuse an existing display name.
        for (int attempt = 0; attempt < 6; attempt++) {
            String candidate = attempt == 0 ? original : withUniqueSuffix(original);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, candidate);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyRemote/");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = null;
            try {
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("无法创建相册文件");
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os == null) throw new Exception("无法打开相册输出流");
                    os.write(data);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, ready, null, null);
                return uri;
            } catch (Exception e) {
                last = e;
                if (uri != null) try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            }
        }
        throw new Exception("手机相册无法保存图片（已尝试避免同名冲突）：" +
                (last == null ? "未知错误" : last.getMessage()), last);
    }

    private static String safeName(String value) {
        if (value == null) return "image.png";
        // Prevent accidental relative paths and invalid Windows/Android file characters.
        String safe = value.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return safe.isEmpty() ? "image.png" : safe;
    }

    private static String withUniqueSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : ".png";
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                .format(new Date());
        return stem + "_" + timestamp + "_" + UUID.randomUUID().toString().substring(0, 6) + ext;
    }

    public static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }
}
