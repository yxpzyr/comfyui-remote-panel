package com.comfyremote.panel;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native full-screen streaming player, preserving the ComfyUI-generated audio track. */
public class VideoPlayerActivity extends Activity {
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_MEDIA_JSON = "media_json";
    private final ExecutorService downloadPool = Executors.newSingleThreadExecutor();
    private VideoView video;
    private ImageRef ref;
    private String server;
    private int lastPosition;

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
        catch (Exception e) { Toast.makeText(this, "视频信息无效", Toast.LENGTH_LONG).show(); finish(); return; }
        if (ref == null || !ref.isVideo()) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101014);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("← 返回");
        Button save = button("下载视频");
        TextView filename = new TextView(this);
        filename.setText(ref.filename);
        filename.setTextSize(13);
        filename.setSingleLine(true);
        filename.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        filename.setTextColor(0xffffffff);
        header.addView(back, new LinearLayout.LayoutParams(dp(76), dp(48)));
        header.addView(filename, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(save, new LinearLayout.LayoutParams(dp(104), dp(48)));
        root.addView(header);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(0xff000000);
        video = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1);
        vp.gravity = Gravity.CENTER;
        stage.addView(video, vp);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = new TextView(this);
        hint.setGravity(Gravity.CENTER);
        hint.setText("视频原声播放 · 点击画面控制暂停和进度 · 播放失败可尝试下载到手机");
        hint.setTextColor(0xffcccccc);
        hint.setTextSize(12);
        root.addView(hint, new LinearLayout.LayoutParams(-1, dp(48)));
        setContentView(root);
        back.setOnClickListener(v -> finish());
        save.setOnClickListener(v -> {
            save.setEnabled(false);
            downloadPool.execute(() -> {
                try {
                    new ComfyApiClient(server).saveMedia(getApplicationContext(), ref);
                    runOnUiThread(() -> Toast.makeText(this, "已保存到 Movies/ComfyRemote", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                } finally { runOnUiThread(() -> save.setEnabled(true)); }
            });
        });
        MediaController controls = new MediaController(this);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "无法播放该视频编码，可先下载后用其他播放器打开", Toast.LENGTH_LONG).show();
            return true;
        });
        try {
            video.setVideoURI(Uri.parse(new ComfyApiClient(server).videoUrl(ref)));
            video.requestFocus();
            video.start();
        } catch (Exception e) {
            Toast.makeText(this, "视频打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (state != null) lastPosition = state.getInt("position", 0);
        video.setOnPreparedListener(mp -> { if (lastPosition > 0) video.seekTo(lastPosition); });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (video != null) out.putInt("position", video.getCurrentPosition());
        super.onSaveInstanceState(out);
    }

    @Override protected void onPause() {
        if (video != null) { lastPosition = video.getCurrentPosition(); video.pause(); }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (video != null && lastPosition > 0) { video.seekTo(lastPosition); video.start(); }
    }

    @Override protected void onDestroy() {
        if (video != null) video.stopPlayback();
        downloadPool.shutdownNow();
        super.onDestroy();
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        return button;
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
