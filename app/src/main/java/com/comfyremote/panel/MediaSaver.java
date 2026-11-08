package com.comfyremote.panel;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
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

    /** Stream large video files into MediaStore without buffering the complete video in RAM. */
    public static Uri saveVideo(Context context, InputStream input, String filename, String mime) throws Exception {
        if (filename == null || filename.trim().isEmpty()) filename = "comfy_" + System.currentTimeMillis() + ".mp4";
        if (mime == null || mime.trim().isEmpty()) mime = guessMime(filename);
        String original = safeName(SubmissionFilenamePolicy.forLegacyDownload(filename, System.currentTimeMillis()));
        ContentResolver resolver = context.getContentResolver();
        Exception last = null;
        // The input stream must only be consumed once. Retry only MediaStore creation errors.
        Uri uri = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            String candidate = attempt == 0 ? original : withUniqueSuffix(original);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, candidate);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyRemote/");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            try {
                uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("无法创建手机视频文件");
                break;
            } catch (Exception e) { last = e; }
        }
        if (uri == null) throw new Exception("无法创建手机视频文件：" +
                (last == null ? "未知错误" : last.getMessage()), last);
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("无法打开视频保存流");
                byte[] buffer = new byte[64 * 1024];
                int n;
                long written = 0L;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("视频下载已取消");
                    out.write(buffer, 0, n);
                    written += n;
                }
                if (written <= 0) throw new Exception("服务器返回了空视频文件");
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            return uri;
        } catch (Exception e) {
            try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            throw e;
        }
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
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        return "image/png";
    }
}
