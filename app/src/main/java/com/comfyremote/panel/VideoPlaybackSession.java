package com.comfyremote.panel;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicBoolean;

/** All native media calls, including source initialization and release, stay off the UI thread. */
final class VideoPlaybackSession {
    interface Events {
        void prepared(int duration, int width, int height);
        void rendering();
        void completed();
        void failed(String message);
    }
    interface Backend {
        void prepare(String url, Surface surface, Events events, AtomicBoolean cancelled) throws Exception;
        void start(); void pause(); void seek(int position);
        int position(); void release();
    }
    interface Factory { Backend create(); }
    static final Factory NATIVE = NativeBackend::new;
    private final HandlerThread thread = new HandlerThread("video-playback");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Factory factory;
    private final Events listener;
    private Backend backend;
    private Surface surface;
    private Runnable releaseTexture;
    private volatile boolean ready;
    volatile boolean playing;
    volatile int position;
    volatile int duration;
    private volatile boolean wantsPlay = true;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed.get() || !ready) return;
            try { position = backend.position(); } catch (Exception ignored) {}
            worker.postDelayed(this, 500);
        }
    };
    VideoPlaybackSession(Factory factory, Events listener) {
        this.factory = factory; this.listener = listener;
        thread.start(); worker = new Handler(thread.getLooper());
    }
    void open(String url, Surface surface, int startAt, Runnable releaseTexture) {
        this.surface = surface; this.releaseTexture = releaseTexture; position = startAt;
        worker.post(() -> {
            if (closed.get()) return;
            try {
                backend = factory.create();
                backend.prepare(url, surface, new Events() {
                    @Override public void prepared(int length, int width, int height) {
                        if (closed.get()) return;
                        ready = true; duration = length;
                        try {
                            if (position > 0) backend.seek(position);
                            if (wantsPlay) { backend.start(); playing = true; }
                            worker.post(ticker);
                            deliver(() -> listener.prepared(length, width, height));
                        } catch (Exception error) { failed(error.getMessage()); }
                    }
                    @Override public void rendering() { deliver(listener::rendering); }
                    @Override public void completed() { playing = false; deliver(listener::completed); }
                    @Override public void failed(String message) {
                        playing = false;
                        deliver(() -> listener.failed(message == null ? "播放器初始化失败" : message));
                    }
                }, closed);
            } catch (Exception error) {
                deliver(() -> listener.failed(error.getMessage() == null ? "视频打开失败" : error.getMessage()));
            }
        });
    }
    private void deliver(Runnable callback) {
        if (!closed.get()) main.post(() -> { if (!closed.get()) callback.run(); });
    }
    void play() { wantsPlay = true; command(() -> { if (ready) { backend.start(); playing = true; } }); }
    void pause() { wantsPlay = false; command(() -> { if (ready) { backend.pause(); playing = false; } }); }
    void seek(int value) { command(() -> { position = value; if (ready) backend.seek(value); }); }
    private void command(Runnable task) {
        if (!closed.get()) worker.post(() -> {
            if (closed.get()) return;
            try { task.run(); } catch (Exception error) { deliver(() -> listener.failed("视频操作失败")); }
        });
    }
    /** Does not wait for preparation, decoder locks, network responses or native release. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        playing = false;
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            try { if (backend != null) backend.release(); }
            finally {
                if (surface != null) surface.release();
                if (releaseTexture != null) releaseTexture.run();
                thread.quitSafely();
            }
        });
    }
    boolean isClosed() { return closed.get(); }

    private static final class NativeBackend implements Backend {
        private final MediaPlayer player = new MediaPlayer();
        @Override public void prepare(String url, Surface surface, Events events, AtomicBoolean cancelled) throws Exception {
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            player.setSurface(surface);
            player.setOnPreparedListener(mp -> events.prepared(mp.getDuration(), mp.getVideoWidth(), mp.getVideoHeight()));
            player.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) events.rendering();
                return false;
            });
            player.setOnCompletionListener(mp -> events.completed());
            player.setOnErrorListener((mp, what, extra) -> {
                events.failed("无法播放该视频编码，可下载后用其他播放器打开"); return true;
            });
            player.setDataSource(url);
            if (!cancelled.get()) player.prepareAsync();
        }
        @Override public void start() { player.start(); }
        @Override public void pause() { player.pause(); }
        @Override public void seek(int position) { player.seekTo(position); }
        @Override public int position() { return player.getCurrentPosition(); }
        @Override public void release() { player.release(); }
    }
}
