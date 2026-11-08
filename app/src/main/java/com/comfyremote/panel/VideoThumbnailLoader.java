package com.comfyremote.panel;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Load only the first frame of a video, never the entire video into Java memory. */
final class VideoThumbnailLoader {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    private VideoThumbnailLoader() {}

    static void load(String server, ImageRef ref, ImageView target, int maxSide,
                     BooleanSupplier stillVisible) {
        if (!ref.isVideo() || !stillVisible.getAsBoolean()) return;
        String key = ComfyApiClient.normalizeBase(server) + "|" + ref.promptId + "|"
                + ref.sourceNodeId + "|" + ref.key() + "|" + maxSide;
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            if (!stillVisible.getAsBoolean()) return;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                String url = new ComfyApiClient(server).videoUrl(ref);
                retriever.setDataSource(url, new HashMap<>());
                Bitmap bitmap = retriever.getScaledFrameAtTime(0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide);
                if (bitmap == null) return;
                CACHE.put(key, bitmap);
                MAIN.post(() -> {
                    if (stillVisible.getAsBoolean() && !bitmap.isRecycled())
                        target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // Keep the visible ▶ video placeholder, rather than fetching a full-size file.
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        });
    }
}
