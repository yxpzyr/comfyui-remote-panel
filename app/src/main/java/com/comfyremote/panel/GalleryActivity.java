package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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

public class GalleryActivity extends Activity {
    private static final int MAX_IMAGES = 100;
    private static final long AUTO_REFRESH_MS = 5000L;

    private final ExecutorService thumbPool = Executors.newFixedThreadPool(5);
    private final ExecutorService downloadPool = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ImageRef> allRefs = new ArrayList<>();
    private final Set<String> selectedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, LinearLayout> tileByKey = new LinkedHashMap<>();

    private GridLayout grid;
    private TextView state;
    private Button sortButton, selectAllButton, downloadButton, clearSelectButton;
    private String server;
    private SortMode sortMode = SortMode.TIME_DESC;
    private int renderGeneration = 0;
    private volatile boolean fetching = false;
    private volatile boolean batchDownloading = false;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            refreshGallery(false);
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    private enum SortMode { TIME_DESC, TIME_ASC, NAME_ASC, NAME_DESC }

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);
        server = getSharedPreferences("comfy_remote", MODE_PRIVATE).getString("server", "8188");
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(autoRefresh);
        refreshGallery(true);
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(autoRefresh);
        super.onPause();
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
        TextView title = text("图库", 22, true);
        sortButton = button("时间 ↓");
        Button refresh = button("刷新");
        header.addView(back, new LinearLayout.LayoutParams(dp(82), dp(46)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMargins(dp(8), 0, dp(6), 0);
        header.addView(title, titleLp);
        header.addView(sortButton, new LinearLayout.LayoutParams(dp(92), dp(46)));
        header.addView(refresh, new LinearLayout.LayoutParams(dp(76), dp(46)));
        root.addView(header);

        state = text("正在同步最新图库…", 12, false);
        state.setTextColor(ThemeManager.secondary(this));
        state.setPadding(0, dp(7), 0, dp(7));
        root.addView(state);

        LinearLayout selection = new LinearLayout(this);
        selection.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = button("全选");
        downloadButton = button("下载 0");
        clearSelectButton = button("取消选择");
        selection.addView(selectAllButton, weightedButton());
        LinearLayout.LayoutParams middle = weightedButton(); middle.setMargins(dp(6), 0, dp(6), 0);
        selection.addView(downloadButton, middle);
        selection.addView(clearSelectButton, weightedButton());
        root.addView(selection);

        ScrollView scroll = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        back.setOnClickListener(v -> finish());
        sortButton.setOnClickListener(v -> showSortMenu(v));
        refresh.setOnClickListener(v -> refreshGallery(true));
        selectAllButton.setOnClickListener(v -> selectAll());
        downloadButton.setOnClickListener(v -> downloadSelected());
        clearSelectButton.setOnClickListener(v -> clearSelection());
        updateSelectionControls();
        setContentView(root);
    }

    private void refreshGallery(boolean forceMessage) {
        if (fetching || batchDownloading) return;
        fetching = true;
        if (forceMessage) state.setText("正在同步 ComfyUI history 与已完成队列…");
        thumbPool.submit(() -> {
            List<ImageRef> merged = new ArrayList<>();
            String warning = "";
            try {
                ComfyApiClient api = new ComfyApiClient(server);
                merged.addAll(api.parseImagesFromAllHistory(api.getAllHistory(300), 300));
            } catch (Exception e) {
                warning = e.getMessage() == null ? "history 读取失败" : e.getMessage();
            }
            // Queue results are persisted independently of Activity lifecycle and can fill the
            // short window where ComfyUI /history has not exposed the newest result yet.
            merged.addAll(JobStore.recentOutputRefs(this, 300));
            List<ImageRef> normalized = normalizeLatest(merged, MAX_IMAGES);
            String finalWarning = warning;
            runOnUiThread(() -> {
                fetching = false;
                boolean changed = !sameRefs(allRefs, normalized);
                if (changed) {
                    allRefs.clear();
                    allRefs.addAll(normalized);
                    selectedKeys.retainAll(keysOf(allRefs));
                    renderGrid();
                } else {
                    updateState(finalWarning);
                }
            });
        });
    }

    private List<ImageRef> normalizeLatest(List<ImageRef> refs, int limit) {
        LinkedHashMap<String, ImageRef> map = new LinkedHashMap<>();
        for (ImageRef ref : refs) {
            if (ref == null || ref.filename == null || ref.filename.isEmpty()) continue;
            ImageRef old = map.get(ref.key());
            if (old == null || ref.timestamp >= old.timestamp) map.put(ref.key(), ref);
        }
        List<ImageRef> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparingLong((ImageRef x) -> x.timestamp).reversed());
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    private boolean sameRefs(List<ImageRef> a, List<ImageRef> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ImageRef x = a.get(i), y = b.get(i);
            if (!x.key().equals(y.key()) || x.timestamp != y.timestamp) return false;
        }
        return true;
    }

    private Set<String> keysOf(List<ImageRef> refs) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (ImageRef r : refs) out.add(r.key());
        return out;
    }

    private void renderGrid() {
        final int generation = ++renderGeneration;
        grid.removeAllViews();
        tileByKey.clear();
        List<ImageRef> sorted = new ArrayList<>(allRefs);
        sortRefs(sorted);
        updateState("");
        ComfyApiClient api = new ComfyApiClient(server);
        for (ImageRef ref : sorted) addTile(api, ref, generation);
        updateSelectionControls();
    }

    private void updateState(String warning) {
        if (allRefs.isEmpty()) {
            state.setText(warning == null || warning.isEmpty() ? "暂时没有图像输出" : "暂时没有可显示图片 · " + warning);
        } else {
            String suffix = warning == null || warning.isEmpty() ? "" : " · history 暂不可用，已显示本地任务结果";
            state.setText("已同步最近 " + allRefs.size() + " 张（最多 100 张）· 每 5 秒自动刷新 · 长按多选" + suffix);
        }
    }

    private void sortRefs(List<ImageRef> refs) {
        Comparator<ImageRef> c;
        if (sortMode == SortMode.TIME_ASC) c = Comparator.comparingLong(x -> x.timestamp);
        else if (sortMode == SortMode.NAME_ASC) c = Comparator.comparing(x -> x.filename.toLowerCase(Locale.ROOT));
        else if (sortMode == SortMode.NAME_DESC) c = Comparator.comparing((ImageRef x) -> x.filename.toLowerCase(Locale.ROOT)).reversed();
        else c = Comparator.comparingLong((ImageRef x) -> x.timestamp).reversed();
        Collections.sort(refs, c);
    }

    private void addTile(ComfyApiClient api, ImageRef ref, int generation) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        setTileBackground(tile, selectedKeys.contains(ref.key()));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(ThemeManager.imagePlaceholder(this));
        tile.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(168)));

        TextView filename = text(ref.filename, 11, false);
        filename.setTextColor(ThemeManager.secondary(this));
        filename.setMaxLines(2);
        filename.setPadding(dp(2), dp(6), dp(2), 0);
        tile.addView(filename);

        if (ref.timestamp > 0) {
            TextView time = text(formatTime(ref.timestamp), 10, false);
            time.setTextColor(ThemeManager.muted(this));
            time.setPadding(dp(2), dp(2), dp(2), 0);
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
                Bitmap b = MainActivity.decodeScaled(dl.bytes, 720, 720);
                runOnUiThread(() -> {
                    if (generation == renderGeneration && b != null) image.setImageBitmap(b);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (generation == renderGeneration) filename.setText(ref.filename + "\n（缩略图加载失败）");
                });
            }
        });

        tile.setOnLongClickListener(v -> {
            toggleSelected(ref);
            Toast.makeText(this, "已进入多选模式，可继续点选图片", Toast.LENGTH_SHORT).show();
            return true;
        });
        tile.setOnClickListener(v -> {
            if (!selectedKeys.isEmpty()) toggleSelected(ref);
            else showPreview(ref);
        });
    }

    private void showPreview(ImageRef ref) {
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
                .setTitle(ref.filename)
                .setView(holder)
                .setNegativeButton("关闭", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            thumbPool.submit(() -> {
                try {
                    ComfyApiClient.ImageDownload dl = new ComfyApiClient(server).fetchImage(ref);
                    Bitmap b = MainActivity.decodeScaled(dl.bytes, 2200, 2200);
                    runOnUiThread(() -> {
                        large.setImageBitmap(b);
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

    private void toggleSelected(ImageRef ref) {
        String key = ref.key();
        if (!selectedKeys.add(key)) selectedKeys.remove(key);
        LinearLayout tile = tileByKey.get(key);
        if (tile != null) setTileBackground(tile, selectedKeys.contains(key));
        updateSelectionControls();
    }

    private void selectAll() {
        if (allRefs.isEmpty()) return;
        selectedKeys.clear();
        for (ImageRef ref : allRefs) selectedKeys.add(ref.key());
        for (Map.Entry<String, LinearLayout> e : tileByKey.entrySet()) setTileBackground(e.getValue(), selectedKeys.contains(e.getKey()));
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
        for (ImageRef ref : allRefs) if (selectedKeys.contains(ref.key())) selected.add(ref);
        downloadButton.setEnabled(false);
        batchDownloading = true;
        state.setText("准备批量下载 " + selected.size() + " 张图片…");
        downloadPool.submit(() -> {
            ComfyApiClient api = new ComfyApiClient(server);
            int ok = 0;
            for (int i = 0; i < selected.size(); i++) {
                ImageRef ref = selected.get(i);
                try {
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
                batchDownloading = false;
                downloadButton.setEnabled(true);
                Toast.makeText(this, "批量下载完成：" + finalOk + " / " + selected.size(), Toast.LENGTH_LONG).show();
                clearSelection();
                updateState("");
            });
        });
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("时间：最新优先");
        menu.getMenu().add("时间：最早优先");
        menu.getMenu().add("名称：A → Z");
        menu.getMenu().add("名称：Z → A");
        menu.setOnMenuItemClickListener(item -> {
            String t = String.valueOf(item.getTitle());
            if (t.startsWith("时间：最新")) { sortMode = SortMode.TIME_DESC; sortButton.setText("时间 ↓"); }
            else if (t.startsWith("时间：最早")) { sortMode = SortMode.TIME_ASC; sortButton.setText("时间 ↑"); }
            else if (t.contains("A → Z")) { sortMode = SortMode.NAME_ASC; sortButton.setText("名称 A-Z"); }
            else { sortMode = SortMode.NAME_DESC; sortButton.setText("名称 Z-A"); }
            renderGrid();
            return true;
        });
        menu.show();
    }

    private void updateSelectionControls() {
        int n = selectedKeys.size();
        downloadButton.setText("下载 " + n);
        downloadButton.setEnabled(n > 0);
        clearSelectButton.setEnabled(n > 0);
        selectAllButton.setEnabled(!allRefs.isEmpty());
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
