package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V2.3 second-level task output browser. This screen is deliberately opt-in: only when the user
 * opens a multi-image task do we request thumbnails for all process images.
 */
public class TaskOutputActivity extends Activity {
    public static final String EXTRA_LOCAL_ID = "local_id";
    public static final String EXTRA_PROMPT_ID = "prompt_id";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_TITLE = "title";

    private final ExecutorService thumbPool = Executors.newFixedThreadPool(4);
    private final ExecutorService downloadPool = Executors.newFixedThreadPool(2);
    private final List<ImageRef> refs = new ArrayList<>();
    private final Set<String> selectedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, LinearLayout> tileByKey = new LinkedHashMap<>();

    private GridLayout grid;
    private TextView state;
    private Button selectAllButton, downloadButton, clearButton;
    private String localId = "";
    private String promptId = "";
    private String server = "8188";
    private String title = "任务全部图片";
    private int renderGeneration = 0;
    private volatile boolean loading = false;
    private volatile boolean downloading = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);
        localId = safe(getIntent().getStringExtra(EXTRA_LOCAL_ID));
        promptId = safe(getIntent().getStringExtra(EXTRA_PROMPT_ID));
        server = safe(getIntent().getStringExtra(EXTRA_SERVER));
        if (server.isEmpty()) server = getSharedPreferences("comfy_remote", MODE_PRIVATE).getString("server", "8188");
        title = safe(getIntent().getStringExtra(EXTRA_TITLE));
        if (title.isEmpty()) title = "任务全部图片";
        buildUi();
        loadOutputs();
    }

    @Override protected void onDestroy() {
        thumbPool.shutdownNow();
        downloadPool.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(16));
        root.setBackgroundColor(ThemeManager.background(this));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("← 返回");
        TextView t = text(title, 19, true);
        Button refresh = button("刷新");
        header.addView(back, new LinearLayout.LayoutParams(dp(82), dp(46)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMargins(dp(8), 0, dp(6), 0);
        header.addView(t, titleLp);
        header.addView(refresh, new LinearLayout.LayoutParams(dp(76), dp(46)));
        root.addView(header);

        state = text("正在读取该任务全部输出…", 12, false);
        state.setTextColor(ThemeManager.secondary(this));
        state.setPadding(0, dp(7), 0, dp(7));
        root.addView(state);

        LinearLayout selection = new LinearLayout(this);
        selection.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = button("全选");
        downloadButton = button("下载 0");
        clearButton = button("取消选择");
        selection.addView(selectAllButton, weightedButton());
        LinearLayout.LayoutParams middle = weightedButton(); middle.setMargins(dp(6), 0, dp(6), 0);
        selection.addView(downloadButton, middle);
        selection.addView(clearButton, weightedButton());
        root.addView(selection);

        ScrollView scroll = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        back.setOnClickListener(v -> finish());
        refresh.setOnClickListener(v -> loadOutputs());
        selectAllButton.setOnClickListener(v -> selectAll());
        clearButton.setOnClickListener(v -> clearSelection());
        downloadButton.setOnClickListener(v -> downloadSelected());
        updateSelectionControls();
        setContentView(root);
    }

    private void loadOutputs() {
        if (loading || downloading) return;
        loading = true;
        state.setText("正在读取该任务全部输出…");
        thumbPool.submit(() -> {
            List<ImageRef> found = new ArrayList<>();
            JobRecord job = localId.isEmpty() ? null : JobStore.find(this, localId);
            if (job != null) found.addAll(job.outputRefs());
            if (!promptId.isEmpty()) {
                try {
                    // Always merge remote history here. Older V2.2 local jobs could have been
                    // completed as soon as the first output appeared, so their stored list may be partial.
                    ComfyApiClient api = new ComfyApiClient(server);
                    List<ImageRef> remote = new ArrayList<>();
                    try {
                        JSONObject history = api.getHistoryForPrompt(promptId);
                        remote.addAll(api.parseAllImagesForPrompt(history, promptId));
                    } catch (Exception ignored) {}
                    if (remote.isEmpty()) {
                        try {
                            JSONObject all = api.getAllHistory(500);
                            remote.addAll(api.parseAllImagesForPrompt(all, promptId));
                        } catch (Exception ignored) {}
                    }
                    found.addAll(remote);
                } catch (Exception ignored) {}
            }
            List<ImageRef> normalized = normalize(found);
            runOnUiThread(() -> {
                loading = false;
                refs.clear();
                refs.addAll(normalized);
                selectedKeys.retainAll(keysOf(refs));
                renderGrid();
            });
        });
    }

    private List<ImageRef> normalize(List<ImageRef> input) {
        LinkedHashMap<String, ImageRef> map = new LinkedHashMap<>();
        if (input != null) for (ImageRef ref : input) {
            if (ref == null || ref.filename == null || ref.filename.isEmpty()) continue;
            ImageRef old = map.get(ref.key());
            if (old == null || compareOutput(ref, old) >= 0) map.put(ref.key(), ref);
        }
        List<ImageRef> out = new ArrayList<>(map.values());
        out.sort((a, b) -> {
            if (a.generatedAt != b.generatedAt) return Long.compare(a.generatedAt, b.generatedAt);
            if (a.outputOrder != b.outputOrder) return Integer.compare(a.outputOrder, b.outputOrder);
            if (a.timestamp != b.timestamp) return Long.compare(a.timestamp, b.timestamp);
            return a.filename.compareToIgnoreCase(b.filename);
        });
        return out;
    }

    private int compareOutput(ImageRef a, ImageRef b) {
        if (a.generatedAt != b.generatedAt) return Long.compare(a.generatedAt, b.generatedAt);
        if (a.outputOrder != b.outputOrder) return Integer.compare(a.outputOrder, b.outputOrder);
        return Long.compare(a.timestamp, b.timestamp);
    }

    private Set<String> keysOf(List<ImageRef> list) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (ImageRef ref : list) out.add(ref.key());
        return out;
    }

    private void renderGrid() {
        int generation = ++renderGeneration;
        grid.removeAllViews();
        tileByKey.clear();
        ImageRef finalRef = ImageRef.chooseFinal(refs);
        String finalKey = finalRef == null ? "" : finalRef.key();
        if (refs.isEmpty()) {
            state.setText("没有可读取的图片输出");
            updateSelectionControls();
            return;
        }
        state.setText("共 " + refs.size() + " 张 · 最后一张标记为最终图 · 长按进入多选");
        ComfyApiClient api = new ComfyApiClient(server);
        for (int i = 0; i < refs.size(); i++) addTile(api, refs.get(i), i + 1, finalKey, generation);
        updateSelectionControls();
    }

    private void addTile(ComfyApiClient api, ImageRef ref, int index, String finalKey, int generation) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        setTileBackground(tile, selectedKeys.contains(ref.key()));

        TextView label = text((ref.key().equals(finalKey) ? "★ 最终图" : "过程图 " + index) + " · " + ref.filename, 11, true);
        label.setMaxLines(2);
        tile.addView(label);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(ThemeManager.imagePlaceholder(this));
        tile.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(168)));

        if (ref.generatedAt > 0 || ref.timestamp > 0) {
            long t = ref.generatedAt > 0 ? ref.generatedAt : ref.timestamp;
            TextView time = text(formatTime(t) + (ref.outputOrder > 0 ? " · 顺序 " + ref.outputOrder : ""), 10, false);
            time.setTextColor(ThemeManager.muted(this));
            time.setPadding(dp(2), dp(4), dp(2), 0);
            tile.addView(time);
        }

        int screen = getResources().getDisplayMetrics().widthPixels;
        int itemW = (screen - dp(42)) / 2;
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = itemW;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(tile, lp);
        tileByKey.put(ref.key(), tile);

        thumbPool.submit(() -> {
            try {
                ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                Bitmap bmp = MainActivity.decodeScaled(dl.bytes, 720, 720);
                runOnUiThread(() -> {
                    if (generation == renderGeneration && bmp != null) image.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {}
        });

        tile.setOnLongClickListener(v -> {
            toggleSelected(ref);
            Toast.makeText(this, "已进入多选模式", Toast.LENGTH_SHORT).show();
            return true;
        });
        tile.setOnClickListener(v -> {
            if (!selectedKeys.isEmpty()) toggleSelected(ref);
            else showPreview(ref, ref.key().equals(finalKey));
        });
    }

    private void showPreview(ImageRef ref, boolean isFinal) {
        LinearLayout holder = new LinearLayout(this);
        holder.setGravity(Gravity.CENTER);
        holder.setPadding(dp(8), dp(8), dp(8), dp(8));
        holder.setBackgroundColor(ThemeManager.card(this));
        ImageView large = new ImageView(this);
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        large.setBackgroundColor(ThemeManager.imagePlaceholder(this));
        holder.addView(large, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(480)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle((isFinal ? "最终图 · " : "") + ref.filename)
                .setView(holder)
                .setNegativeButton("关闭", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            thumbPool.submit(() -> {
                try {
                    ComfyApiClient.ImageDownload dl = new ComfyApiClient(server).fetchImage(ref);
                    Bitmap bmp = MainActivity.decodeScaled(dl.bytes, 2200, 2200);
                    runOnUiThread(() -> {
                        large.setImageBitmap(bmp);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> saveOne(ref, dl));
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "图片加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        dialog.show();
    }

    private void toggleSelected(ImageRef ref) {
        String key = ref.key();
        if (!selectedKeys.add(key)) selectedKeys.remove(key);
        LinearLayout tile = tileByKey.get(key);
        if (tile != null) setTileBackground(tile, selectedKeys.contains(key));
        updateSelectionControls();
    }

    private void selectAll() {
        selectedKeys.clear();
        for (ImageRef ref : refs) selectedKeys.add(ref.key());
        for (Map.Entry<String, LinearLayout> e : tileByKey.entrySet()) setTileBackground(e.getValue(), true);
        updateSelectionControls();
    }

    private void clearSelection() {
        selectedKeys.clear();
        for (LinearLayout tile : tileByKey.values()) setTileBackground(tile, false);
        updateSelectionControls();
    }

    private void downloadSelected() {
        if (selectedKeys.isEmpty()) {
            Toast.makeText(this, "先长按图片进行选择", Toast.LENGTH_SHORT).show();
            return;
        }
        List<ImageRef> selected = new ArrayList<>();
        for (ImageRef ref : refs) if (selectedKeys.contains(ref.key())) selected.add(ref);
        downloading = true;
        downloadButton.setEnabled(false);
        downloadPool.submit(() -> {
            ComfyApiClient api = new ComfyApiClient(server);
            int ok = 0;
            for (int i = 0; i < selected.size(); i++) {
                try {
                    ImageRef ref = selected.get(i);
                    ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                    MediaSaver.saveImage(this, dl.bytes, ref.filename, dl.mime);
                    ok++;
                } catch (Exception ignored) {}
                int done = i + 1;
                int good = ok;
                runOnUiThread(() -> state.setText("批量下载中：" + done + " / " + selected.size() + " · 成功 " + good));
            }
            int finalOk = ok;
            runOnUiThread(() -> {
                downloading = false;
                Toast.makeText(this, "下载完成：" + finalOk + " / " + selected.size(), Toast.LENGTH_LONG).show();
                clearSelection();
                state.setText("共 " + refs.size() + " 张 · 最后一张标记为最终图 · 长按进入多选");
            });
        });
    }

    private void saveOne(ImageRef ref, ComfyApiClient.ImageDownload dl) {
        downloadPool.submit(() -> {
            try {
                MediaSaver.saveImage(this, dl.bytes, ref.filename, dl.mime);
                runOnUiThread(() -> Toast.makeText(this, "已保存到 Pictures/ComfyRemote", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void updateSelectionControls() {
        int n = selectedKeys.size();
        downloadButton.setText("下载 " + n);
        downloadButton.setEnabled(n > 0 && !downloading);
        clearButton.setEnabled(n > 0 && !downloading);
        selectAllButton.setEnabled(!refs.isEmpty() && !downloading);
    }

    private void setTileBackground(LinearLayout tile, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? ThemeManager.selected(this) : ThemeManager.card(this));
        bg.setCornerRadius(dp(14));
        if (selected) bg.setStroke(dp(2), ThemeManager.accent(this));
        tile.setBackground(bg);
    }

    private String formatTime(long t) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(t));
    }

    private String safe(String s) { return s == null ? "" : s; }
    private LinearLayout.LayoutParams weightedButton() { return new LinearLayout.LayoutParams(0, dp(44), 1f); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(ThemeManager.text(this));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
}
