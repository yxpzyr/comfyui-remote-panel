package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class GalleryActivity extends Activity {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private GridLayout grid;
    private TextView state;
    private String server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = getSharedPreferences("comfy_remote", MODE_PRIVATE).getString("server", "8188");
        buildUi();
        refreshGallery();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(18));
        root.setBackgroundColor(Color.rgb(246, 247, 249));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("← 返回");
        back.setAllCaps(false);
        TextView title = new TextView(this);
        title.setText("生成图库");
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(25, 28, 34));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMargins(dp(10), 0, dp(10), 0);
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setAllCaps(false);
        header.addView(back, new LinearLayout.LayoutParams(dp(90), dp(48)));
        header.addView(title, titleLp);
        header.addView(refresh, new LinearLayout.LayoutParams(dp(86), dp(48)));
        root.addView(header);

        state = new TextView(this);
        state.setText("正在读取服务器历史…");
        state.setTextSize(13);
        state.setTextColor(Color.DKGRAY);
        state.setPadding(0, dp(8), 0, dp(10));
        root.addView(state);

        ScrollView scroll = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        back.setOnClickListener(v -> finish());
        refresh.setOnClickListener(v -> refreshGallery());
        setContentView(root);
    }

    private void refreshGallery() {
        grid.removeAllViews();
        state.setText("正在读取服务器历史…");
        pool.submit(() -> {
            try {
                ComfyApiClient api = new ComfyApiClient(server);
                JSONObject history = api.getAllHistory();
                List<ImageRef> refs = api.parseImagesFromAllHistory(history, 60);
                runOnUiThread(() -> {
                    state.setText(refs.isEmpty() ? "服务器历史里暂时没有图像输出" : "共发现 " + refs.size() + " 张 · 点击图片可预览/保存");
                    for (ImageRef ref : refs) addTile(api, ref);
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("图库读取失败")
                        .setMessage(e.getMessage())
                        .setPositiveButton("知道了", null)
                        .show());
            }
        });
    }

    private void addTile(ComfyApiClient api, ImageRef ref) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        tile.setBackground(bg);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(Color.rgb(232, 234, 239));
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);

        LinearLayout frame = new LinearLayout(this);
        frame.setGravity(Gravity.CENTER);
        frame.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)));
        tile.addView(frame);

        TextView filename = new TextView(this);
        filename.setText(ref.filename);
        filename.setTextSize(11);
        filename.setTextColor(Color.DKGRAY);
        filename.setMaxLines(2);
        filename.setPadding(dp(2), dp(6), dp(2), 0);
        tile.addView(filename);

        int screen = getResources().getDisplayMetrics().widthPixels;
        int itemW = (screen - dp(48)) / 2;
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = itemW;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(tile, lp);

        AtomicReference<ComfyApiClient.ImageDownload> dataRef = new AtomicReference<>();
        pool.submit(() -> {
            try {
                ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                dataRef.set(dl);
                Bitmap b = MainActivity.decodeScaled(dl.bytes, 900, 900);
                runOnUiThread(() -> image.setImageBitmap(b));
            } catch (Exception ignored) {
                runOnUiThread(() -> filename.setText(ref.filename + "\n（加载失败）"));
            }
        });

        tile.setOnClickListener(v -> {
            ComfyApiClient.ImageDownload dl = dataRef.get();
            if (dl == null) {
                Toast.makeText(this, "图片还在加载，请稍等", Toast.LENGTH_SHORT).show();
                return;
            }
            showPreview(ref, dl);
        });
    }

    private void showPreview(ImageRef ref, ComfyApiClient.ImageDownload dl) {
        ImageView large = new ImageView(this);
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        large.setPadding(dp(8), dp(8), dp(8), dp(8));
        large.setImageBitmap(MainActivity.decodeScaled(dl.bytes, 1800, 1800));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(ref.filename)
                .setView(large)
                .setNegativeButton("关闭", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            pool.submit(() -> {
                try {
                    MediaSaver.saveImage(this, dl.bytes, ref.filename, dl.mime);
                    runOnUiThread(() -> Toast.makeText(this, "已保存到 Pictures/ComfyRemote", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }));
        dialog.show();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
