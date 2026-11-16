package com.comfyremote.panel;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Responsive streaming UI. Every native media operation belongs to VideoPlaybackSession's worker. */
public class VideoPlayerActivity extends Activity {
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_MEDIA_JSON = "media_json";
    static volatile VideoPlaybackSession.Factory backendFactory = VideoPlaybackSession.NATIVE;
    private static final ExecutorService TEXTURES = Executors.newSingleThreadExecutor();
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextureView texture;
    private FrameLayout stage;
    private TextView status, time;
    private Button play;
    private SeekBar seek;
    private ProgressBar loading;
    private ImageRef ref;
    private String server;
    private VideoPlaybackSession session;
    private int lastPosition, videoWidth, videoHeight;
    private boolean screenVisible, stopped, closing, dragging;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            VideoPlaybackSession current = session;
            if (screenVisible && !closing) {
                if (current != null) {
                    int duration = current.duration, position = current.position;
                    if (!dragging && duration > 0) seek.setProgress((int) Math.min(1000, position * 1000L / duration));
                    time.setText(clock(position) + " / " + clock(duration));
                    play.setText(current.playing ? "暂停" : "播放");
                }
                ui.postDelayed(this, 500);
            }
        }
    };

    public static void open(Activity from, String server, ImageRef ref) {
        android.content.Intent intent = new android.content.Intent(from, VideoPlayerActivity.class);
        intent.putExtra(EXTRA_SERVER, server);
        intent.putExtra(EXTRA_MEDIA_JSON, ref.toJson().toString());
        from.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        ThemeManager.prepare(this);
        super.onCreate(state);
        ThemeManager.applyWindow(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        server = getIntent().getStringExtra(EXTRA_SERVER);
        if (server == null || server.isEmpty()) server = "8188";
        try { ref = ImageRef.fromJson(new org.json.JSONObject(getIntent().getStringExtra(EXTRA_MEDIA_JSON))); }
        catch (Exception error) { finish(); return; }
        if (ref == null || !ref.isVideo()) { finish(); return; }
        lastPosition = state == null ? 0 : state.getInt("position", 0);
        stopped = state != null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101014);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("← 返回");
        back.setId(R.id.video_back);
        back.setContentDescription("返回视频列表");
        TextView name = text(ref.filename);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Button save = button("下载视频");
        header.addView(back, new LinearLayout.LayoutParams(dp(82), dp(48)));
        header.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(save, new LinearLayout.LayoutParams(dp(104), dp(48)));
        root.addView(header);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(0xff000000);
        texture = new TextureView(this);
        texture.setId(R.id.video_texture);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        stage.addView(texture, full);
        loading = new ProgressBar(this);
        stage.addView(loading, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text(stopped ? "点击播放继续观看" : "正在加载视频… 可随时返回");
        status.setId(R.id.video_status);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        play = button("播放");
        controls.addView(play, new LinearLayout.LayoutParams(dp(82), dp(48)));
        seek = new SeekBar(this); seek.setMax(1000);
        controls.addView(seek, new LinearLayout.LayoutParams(0, dp(48), 1));
        time = text("00:00 / 00:00");
        controls.addView(time, new LinearLayout.LayoutParams(dp(112), dp(48)));
        root.addView(controls);
        setContentView(root);
        back.setOnClickListener(v -> exitImmediately());
        if (android.os.Build.VERSION.SDK_INT >= 33) Api33.register(this);
        save.setOnClickListener(v -> {
            save.setEnabled(false);
            final android.content.Context app = getApplicationContext();
            downloads.execute(() -> {
                try {
                    new ComfyApiClient(server).saveMedia(app, ref);
                    ui.post(() -> { if (!isDestroyed()) Toast.makeText(this, "已保存到 Movies/ComfyRemote", Toast.LENGTH_LONG).show(); });
                } catch (Exception error) {
                    ui.post(() -> { if (!isDestroyed()) Toast.makeText(this, "下载失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); });
                } finally { ui.post(() -> { if (!isDestroyed()) save.setEnabled(true); }); }
            });
        });
        play.setOnClickListener(v -> {
            if (session == null || session.isClosed()) {
                stopped = false;
                if (texture.isAvailable()) openStream(texture.getSurfaceTexture());
            } else if (session.playing) session.pause();
            else session.play();
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {}
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (session != null && session.duration > 0) session.seek((int) (session.duration * (long) bar.getProgress() / 1000));
            }
        });
        stage.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> fitVideo());
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (!stopped && !closing && screenVisible) openStream(surface);
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { fitVideo(); }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stopSession();
                TEXTURES.execute(surface::release);
                return false; // Even surface destruction must not wait on the UI thread.
            }
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        loading.setVisibility(stopped ? View.GONE : View.VISIBLE);
    }

    private void openStream(SurfaceTexture surface) {
        if (closing || !screenVisible || surface == null || session != null) return;
        loading.setVisibility(View.VISIBLE);
        play.setEnabled(false);
        status.setText("正在加载视频… 可随时返回");
        final VideoPlaybackSession[] owner = new VideoPlaybackSession[1];
        VideoPlaybackSession current = new VideoPlaybackSession(backendFactory, new VideoPlaybackSession.Events() {
            public void prepared(int duration, int width, int height) {
                if (session != owner[0] || closing) return;
                videoWidth = width; videoHeight = height; fitVideo();
                play.setEnabled(true); status.setText("正在缓冲… 可随时返回");
            }
            public void rendering() {
                if (session != owner[0] || closing) return;
                loading.setVisibility(View.GONE);
                status.setText("视频原声播放 · 可暂停、拖动进度或随时返回");
                ui.postDelayed(() -> captureCover(owner[0]), 300);
            }
            public void completed() {
                if (session != owner[0] || closing) return;
                loading.setVisibility(View.GONE); play.setText("播放"); status.setText("播放完成");
            }
            public void failed(String message) {
                if (session != owner[0] || closing) return;
                stopSession(); loading.setVisibility(View.GONE); play.setEnabled(true);
                status.setText(message + " · 可重试或下载视频");
            }
        });
        owner[0] = current;
        session = current;
        try { current.open(new ComfyApiClient(server).videoUrl(ref), new Surface(surface), lastPosition, null); }
        catch (Exception error) {
            stopSession(); play.setEnabled(true); loading.setVisibility(View.GONE);
            status.setText("视频打开失败：" + error.getMessage());
        }
    }
    private void captureCover(VideoPlaybackSession owner) {
        if (closing || !screenVisible || session != owner || !texture.isAvailable()) return;
        int width = Math.min(512, Math.max(1, texture.getWidth()));
        int height = Math.max(1, (int) (texture.getHeight() * (long) width / Math.max(1, texture.getWidth())));
        try {
            Bitmap frame = texture.getBitmap(width, Math.min(512, height));
            VideoThumbnailLoader.remember(getApplicationContext(), server, ref, frame);
        } catch (Exception ignored) {}
    }
    private void fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || stage.getWidth() <= 0 || stage.getHeight() <= 0) return;
        float scale = Math.min(stage.getWidth() / (float) videoWidth, stage.getHeight() / (float) videoHeight);
        int width = Math.max(1, Math.round(videoWidth * scale));
        int height = Math.max(1, Math.round(videoHeight * scale));
        if (texture.getLayoutParams().width == width && texture.getLayoutParams().height == height) return;
        texture.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
    }
    private void stopSession() {
        VideoPlaybackSession old = session;
        session = null;
        if (old != null) { lastPosition = old.position; old.close(); }
        stopped = true;
    }
    private void exitImmediately() {
        if (closing) return;
        closing = true;
        ui.removeCallbacksAndMessages(null);
        stopSession();
        finish();
    }
    @Override public void onBackPressed() { exitImmediately(); }
    @Override protected void onStart() {
        super.onStart(); screenVisible = true;
        ui.removeCallbacks(refresh); ui.post(refresh);
        if (texture != null && texture.isAvailable() && !stopped) openStream(texture.getSurfaceTexture());
    }
    @Override protected void onPause() {
        screenVisible = false;
        stopSession();
        if (status != null && !closing) {
            status.setText("点击播放继续观看"); play.setEnabled(true); play.setText("播放"); loading.setVisibility(View.GONE);
        }
        super.onPause();
    }
    @Override protected void onStop() {
        screenVisible = false; ui.removeCallbacksAndMessages(null); stopSession(); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("position", session == null ? lastPosition : session.position);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        closing = true; ui.removeCallbacksAndMessages(null); stopSession();
        downloads.shutdown(); super.onDestroy();
    }
    private static final class Api33 {
        static void register(VideoPlayerActivity activity) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, activity::exitImmediately);
        }
    }
    private TextView text(String value) { TextView view = new TextView(this); view.setText(value); view.setTextSize(12); view.setTextColor(0xffdddddd); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setTextSize(12); b.setAllCaps(false); return b; }
    private String clock(int ms) { int seconds = Math.max(0, ms / 1000); return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
