package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryActivity extends Activity {
    private static final int MAX_IMAGES = 100;

    private final ExecutorService thumbPool = Executors.newFixedThreadPool(4);
    private final ExecutorService downloadPool = Executors.newSingleThreadExecutor();
    private final List<ImageRef> allRefs = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private final Map<String, LinearLayout> tileByKey = new HashMap<>();

    private GridLayout grid;
    private TextView state;
    private Button sortButton, selectAllButton, downloadButton, clearSelectButton;
    private String server;
    private SortMode sortMode = SortMode.TIME_DESC;
    private volatile int renderGeneration = 0;

    private enum SortMode { TIME_DESC, TIME_ASC, NAME_ASC, NAME_DESC }

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
        root.setPadding(dp(14), dp(14), dp(14), dp(16));
        root.setBackgroundColor(Color.rgb(246, 247, 249));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("← 返回");
        TextView title = text("生成图库", 22, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMargins(dp(8), 0, dp(8), 0);
        Button refresh = button("刷新");
        header.addView(back, new LinearLayout.LayoutParams(dp(86), dp(48)));
        header.addView(title, titleLp);
        header.addView(refresh, new LinearLayout.LayoutParams(dp(82), dp(48)));
        root.addView(header);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        sortButton = button("时间 ↓");
        selectAllButton = button("全选");
        downloadButton = button("下载 0");
        clearSelectButton = button("取消选择");
        controls.addView(sortButton, weightedButton());
        LinearLayout.LayoutParams mid1 = weightedButton(); mid1.setMargins(dp(6), 0, dp(6), 0);
        controls.addView(selectAllButton, mid1);
        controls.addView(downloadButton, weightedButton());
        LinearLayout.LayoutParams end = weightedButton(); end.setMargins(dp(6), 0, 0, 0);
        controls.addView(clearSelectButton, end);
        root.addView(controls);

        state = text("正在读取服务器历史…", 13, false);
        state.setTextColor(Color.DKGRAY);
        state.setPadding(0, dp(8), 0, dp(8));
        root.addView(state);

        ScrollView scroll = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        back.setOnClickListener(v -> finish());
        refresh.setOnClickListener(v -> refreshGallery());
        sortButton.setOnClickListener(this::showSortMenu);
        selectAllButton.setOnClickListener(v -> selectAll());
        downloadButton.setOnClickListener(v -> downloadSelected());
        clearSelectButton.setOnClickListener(v -> clearSelection());
        updateSelectionControls();
        setContentView(root);
    }

    private void refreshGallery() {
        int generation = ++renderGeneration;
        grid.removeAllViews();
        allRefs.clear();
        tileByKey.clear();
        selectedKeys.clear();
        updateSelectionControls();
        state.setText("正在读取服务器历史…");
        thumbPool.submit(() -> {
            try {
                ComfyApiClient api = new ComfyApiClient(server);
                List<ImageRef> refs = api.parseImagesFromAllHistory(api.getAllHistory(160), MAX_IMAGES);
                runOnUiThread(() -> {
                    if (generation != renderGeneration) return;
                    allRefs.clear();
                    allRefs.addAll(refs);
                    renderGrid();
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("图库读取失败")
                        .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                        .setPositiveButton("知道了", null)
                        .show());
            }
        });
    }

    private void renderGrid() {
        final int generation = ++renderGeneration;
        grid.removeAllViews();
        tileByKey.clear();
        List<ImageRef> sorted = new ArrayList<>(allRefs);
        sortRefs(sorted);
        state.setText(sorted.isEmpty() ? "服务器历史里暂时没有图像输出" :
                "显示最近 " + sorted.size() + " 张（最多 100 张）· 长按图片进入多选");

        ComfyApiClient api = new ComfyApiClient(server);
        for (ImageRef ref : sorted) addTile(api, ref, generation);
        updateSelectionControls();
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
        image.setBackgroundColor(Color.rgb(232, 234, 239));
        tile.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(168)));

        TextView filename = text(ref.filename, 11, false);
        filename.setTextColor(Color.DKGRAY);
        filename.setMaxLines(2);
        filename.setPadding(dp(2), dp(6), dp(2), 0);
        tile.addView(filename);

        if (ref.timestamp > 0) {
            TextView time = text(formatTime(ref.timestamp), 10, false);
            time.setTextColor(Color.GRAY);
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
        ImageView large = new ImageView(this);
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
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
                    Bitmap b = MainActivity.decodeScaled(dl.bytes, 1800, 1800);
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
                downloadButton.setEnabled(true);
                Toast.makeText(this, "批量下载完成：" + finalOk + " / " + selected.size(), Toast.LENGTH_LONG).show();
                clearSelection();
                state.setText("显示最近 " + allRefs.size() + " 张（最多 100 张）· 长按图片进入多选");
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
        bg.setColor(selected ? Color.rgb(222, 235, 255) : Color.WHITE);
        bg.setCornerRadius(dp(14));
        if (selected) bg.setStroke(dp(2), Color.rgb(45, 110, 220));
        tile.setBackground(bg);
    }

    private String formatTime(long t) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(t));
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
        t.setTextColor(Color.rgb(28, 30, 35));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
}
