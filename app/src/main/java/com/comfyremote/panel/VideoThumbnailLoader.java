package com.comfyremote.panel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Shared, persistent video covers with bounded range requests and one decode per video. */
final class VideoThumbnailLoader {
    interface FrameDecoder { Bitmap decode(String url, BooleanSupplier wanted) throws Exception; }
    static final FrameDecoder NATIVE_DECODER = VideoThumbnailLoader::decodeRemote;
    static volatile FrameDecoder decoder = NATIVE_DECODER;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private static final Map<String, Flight> FLIGHTS = new HashMap<>();
    private static final Object DISK = new Object();
    private static final long DISK_LIMIT = 40L * 1024 * 1024;
    private VideoThumbnailLoader() {}

    static void load(String server, ImageRef ref, ImageView target, int maxSide, BooleanSupplier visible) {
        if (ref == null || !ref.isVideo() || !visible.getAsBoolean()) return;
        Context app = target.getContext().getApplicationContext();
        String key = cacheKey(server, ref);
        Bitmap cached = MEMORY.get(key);
        if (cached != null && !cached.isRecycled()) { target.setImageBitmap(cached); return; }
        target.setImageResource(android.R.drawable.ic_media_play);
        synchronized (FLIGHTS) {
            Flight flight = FLIGHTS.get(key);
            if (flight != null) { flight.add(target, visible); return; }
            flight = new Flight(); flight.add(target, visible);
            FLIGHTS.put(key, flight);
            final Flight task = flight;
            try {
                POOL.execute(() -> {
                    Bitmap result = null;
                    try { result = loadBlocking(app, server, ref, task::wanted); }
                    catch (Exception ignored) {}
                    synchronized (FLIGHTS) { FLIGHTS.remove(key, task); }
                    if (result != null) {
                        final Bitmap cover = result;
                        MAIN.post(() -> task.deliver(cover));
                    }
                });
            } catch (RejectedExecutionException full) { FLIGHTS.remove(key, task); }
        }
    }
    private static final class Target {
        final WeakReference<ImageView> view;
        final BooleanSupplier visible;
        Target(ImageView view, BooleanSupplier visible) { this.view = new WeakReference<>(view); this.visible = visible; }
        boolean wanted() {
            try { return view.get() != null && visible.getAsBoolean(); } catch (Exception ignored) { return false; }
        }
    }
    private static final class Flight {
        final List<Target> targets = new ArrayList<>();
        synchronized void add(ImageView view, BooleanSupplier visible) {
            targets.removeIf(t -> t.view.get() == view || !t.wanted());
            while (targets.size() >= 16) targets.remove(0);
            targets.add(new Target(view, visible));
        }
        synchronized boolean wanted() { for (Target target : targets) if (target.wanted()) return true; return false; }
        synchronized void deliver(Bitmap bitmap) {
            for (Target target : targets) if (target.wanted()) {
                ImageView view = target.view.get(); if (view != null) view.setImageBitmap(bitmap);
            }
        }
    }
    static Bitmap loadBlocking(Context context, String server, ImageRef ref, BooleanSupplier wanted) throws Exception {
        if (!wanted.getAsBoolean()) return null;
        String key = cacheKey(server, ref);
        Bitmap cover = MEMORY.get(key);
        if (cover != null && !cover.isRecycled()) return cover;
        File dir = folder(context), file = new File(dir, key + ".jpg"), failure = new File(dir, key + ".failed");
        synchronized (DISK) {
            if (file.isFile() && file.length() > 0 && file.length() <= 512 * 1024) {
                cover = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (cover != null) {
                    file.setLastModified(System.currentTimeMillis());
                    MEMORY.put(key, cover); return cover;
                }
                file.delete();
            }
            if (failure.isFile() && System.currentTimeMillis() - failure.lastModified() < 30L * 60 * 1000) return null;
        }
        try {
            cover = decoder.decode(new ComfyApiClient(server).videoUrl(ref), wanted);
            if (cover == null) throw new java.io.IOException("未找到可解码的视频帧");
            MEMORY.put(key, cover);
            write(context, key, cover);
            return cover;
        } catch (Exception error) {
            if (wanted.getAsBoolean()) synchronized (DISK) {
                if (dir.isDirectory() || dir.mkdirs()) {
                    try (FileOutputStream marker = new FileOutputStream(failure)) { marker.write(0); }
                    evict(dir);
                }
            }
            throw error;
        }
    }
    private static Bitmap decodeRemote(String url, BooleanSupplier wanted) throws Exception {
        try (VideoRangeDataSource source = new VideoRangeDataSource(url, wanted)) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(source);
                if (!wanted.getAsBoolean()) return null;
                return retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512);
            } finally { retriever.release(); }
        }
    }
    /** A rendered playback frame also repairs a cover when a proxy cannot serve byte ranges. */
    static void remember(Context context, String server, ImageRef ref, Bitmap frame) {
        if (frame == null) return;
        String key = cacheKey(server, ref);
        MEMORY.put(key, frame);
        try { POOL.execute(() -> write(context.getApplicationContext(), key, frame)); }
        catch (RejectedExecutionException ignored) {}
    }
    static String cacheKey(String server, ImageRef ref) {
        // Home/queue timestamps include local observation times; remote history does not.
        // The server + prompt ID + file identity must share one cover across all views.
        String identity = ComfyApiClient.normalizeBase(server) + "|" + ref.promptId + "|" + ref.key()
                + "|" + (ref.promptId.isEmpty() ? ref.timestamp : "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static File folder(Context context) { return new File(context.getCacheDir(), "video_covers_v2810"); }
    private static void write(Context context, String key, Bitmap frame) {
        synchronized (DISK) {
            File dir = folder(context);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File temp = new File(dir, key + ".part");
            try {
                try (FileOutputStream out = new FileOutputStream(temp)) { if (!frame.compress(Bitmap.CompressFormat.JPEG, 76, out)) return; }
                if (temp.length() > 512 * 1024) return;
                Files.move(temp.toPath(), new File(dir, key + ".jpg").toPath(), StandardCopyOption.REPLACE_EXISTING);
                new File(dir, key + ".failed").delete();
                evict(dir);
            } catch (Exception ignored) {}
            finally { temp.delete(); }
        }
    }
    private static void evict(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg") || n.endsWith(".failed"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long bytes = 0; for (File file : files) bytes += file.length();
        int count = files.length;
        for (File file : files) {
            if (count <= 400 && bytes <= DISK_LIMIT) break;
            long size = file.length(); if (file.delete()) { bytes -= size; count--; }
        }
    }
    static void clearMemoryForTest() { MEMORY.evictAll(); }
}
