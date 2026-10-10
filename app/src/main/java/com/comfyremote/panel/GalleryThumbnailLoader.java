package com.comfyremote.panel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * V2.7 shared, bounded gallery thumbnail pipeline.
 * Never downloads original PNGs for tiles; disk and RAM caches have hard budgets.
 * Stale page/activity work is ignored both before the network request and before UI delivery.
 */
final class GalleryThumbnailLoader {
    private static final int MAX_NETWORK_BYTES = 4 * 1024 * 1024;
    private static final long DISK_BUDGET_BYTES = 48L * 1024 * 1024;
    private static final int MAX_DISK_FILES = 500;
    private static final Object DISK_LOCK = new Object();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getAllocationByteCount() / 1024);
        }
    };

    private GalleryThumbnailLoader() {}

    static void load(Context context, String server, ImageRef ref, ImageView target,
                     int maxSide, BooleanSupplier stillVisible) {
        if (ref == null || !stillVisible.getAsBoolean()) return;
        Context app = context.getApplicationContext();
        String key = cacheKey(server, ref, maxSide);
        Bitmap cached = MEMORY.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            if (!stillVisible.getAsBoolean()) return;
            try {
                Bitmap bmp = MEMORY.get(key);
                if (bmp == null || bmp.isRecycled()) {
                    String networkKey = cacheKey(server, ref, 0);
                    byte[] bytes = readDisk(app, networkKey);
                    if (bytes == null) {
                        // ComfyUI's /view preview is a compressed image, not the original PNG.
                        bytes = new ComfyApiClient(server).fetchImagePreview(ref, 58, MAX_NETWORK_BYTES, stillVisible).bytes;
                        if (!stillVisible.getAsBoolean()) return;
                        writeDisk(app, networkKey, bytes);
                    }
                    bmp = decodeThumbnail(bytes, maxSide);
                    if (bmp == null) return;
                    MEMORY.put(key, bmp);
                }
                final Bitmap display = bmp;
                MAIN.post(() -> {
                    if (stillVisible.getAsBoolean() && !display.isRecycled()) target.setImageBitmap(display);
                });
            } catch (Exception ignored) {
                // A failed preview stays a placeholder. Never fall back to a huge original download.
            }
        });
    }

    static Bitmap decodeThumbnail(byte[] bytes, int maxSide) {
        if (bytes == null || bytes.length == 0 || maxSide < 32) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        // Bound decoded pixels even if a server ignores the requested preview dimensions.
        int sample = 1;
        while (Math.max(opts.outWidth / sample, opts.outHeight / sample) > maxSide * 2 && sample < 128)
            sample *= 2;
        BitmapFactory.Options actual = new BitmapFactory.Options();
        actual.inSampleSize = sample;
        actual.inPreferredConfig = Bitmap.Config.RGB_565; // gallery thumbnails do not need alpha.
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, actual);
        if (decoded == null) return null;
        int largest = Math.max(decoded.getWidth(), decoded.getHeight());
        if (largest <= maxSide) return decoded;
        float scale = (float) maxSide / largest;
        int w = Math.max(1, Math.round(decoded.getWidth() * scale));
        int h = Math.max(1, Math.round(decoded.getHeight() * scale));
        Bitmap resized = Bitmap.createScaledBitmap(decoded, w, h, true);
        if (resized != decoded) decoded.recycle();
        return resized;
    }

    private static String cacheKey(String server, ImageRef ref, int size) {
        return sha256(ComfyApiClient.normalizeBase(server) + "|" + ref.key() + "|" + size);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(64);
            for (byte x : hash) b.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) { return String.valueOf(value.hashCode()); }
    }

    private static File folder(Context context) {
        return new File(context.getCacheDir(), "gallery_previews_v27");
    }

    private static byte[] readDisk(Context context, String key) {
        synchronized (DISK_LOCK) {
            File file = new File(folder(context), key + ".jpg");
            if (!file.isFile()) return null;
            if (file.length() <= 0 || file.length() > MAX_NETWORK_BYTES) { file.delete(); return null; }
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                file.setLastModified(System.currentTimeMillis());
                return bytes;
            } catch (Exception ignored) { file.delete(); return null; }
        }
    }

    private static void writeDisk(Context context, String key, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_NETWORK_BYTES) return;
        synchronized (DISK_LOCK) {
            File dir = folder(context);
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = new File(dir, key + ".jpg");
            File temp = new File(dir, key + ".part");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
            catch (Exception ignored) { temp.delete(); return; }
            if (!temp.renameTo(file)) { temp.delete(); return; }
            evictDisk(dir);
        }
    }

    private static void evictDisk(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (files.length <= MAX_DISK_FILES && total <= DISK_BUDGET_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int count = files.length;
        for (File f : files) {
            if (count <= MAX_DISK_FILES && total <= DISK_BUDGET_BYTES) break;
            long length = f.length();
            if (f.delete()) { total -= length; count--; }
        }
    }
}
