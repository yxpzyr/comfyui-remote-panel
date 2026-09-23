package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
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

/**
 * V2.3 task-level paged gallery. Only the final image thumbnail of tasks on the current page is
 * downloaded. Full process images are fetched only after the user explicitly opens "查看全部图".
 */
public class GalleryActivity extends Activity {
    private static final int REMOTE_HISTORY_ITEMS = 300;
    private static final int REMOTE_IMAGE_METADATA_LIMIT = 10000;
    private static final String PREF_PAGE_SIZE = "gallery_page_size_v23";

    private final ExecutorService thumbPool = Executors.newFixedThreadPool(4);
    private final ExecutorService downloadPool = Executors.newFixedThreadPool(2);
    private final List<GalleryTask> allTasks = new ArrayList<>();
    private final Set<String> selectedTaskKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, LinearLayout> tileByTaskKey = new LinkedHashMap<>();

    private GridLayout grid;
    private TextView state, pageCountText;
    private EditText pageEdit;
    private Button prevButton, nextButton, pageSizeButton, sortButton;
    private Button selectAllButton, downloadButton, clearSelectButton;
    private SharedPreferences prefs;
    private String server;
    private SortMode sortMode = SortMode.TIME_DESC;
    private int renderGeneration = 0;
    private int page = 1;
    private int pageSize = 10;
    private volatile boolean fetching = false;
    private volatile boolean batchDownloading = false;

    private enum SortMode { TIME_DESC, TIME_ASC, NAME_ASC, NAME_DESC }

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);
        prefs = getSharedPreferences("comfy_remote", MODE_PRIVATE);
        server = prefs.getString("server", "8188");
        pageSize = normalizePageSize(prefs.getInt(PREF_PAGE_SIZE, 10));
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        // V2.3 intentionally refreshes once on entry/resume; no 5-second polling loop.
        refreshGallery(true);
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

        state = text("正在同步任务图库…", 12, false);
        state.setTextColor(ThemeManager.secondary(this));
        state.setPadding(0, dp(7), 0, dp(7));
        root.addView(state);

        LinearLayout pager = new LinearLayout(this);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        prevButton = button("←");
        nextButton = button("→");
        pageEdit = new EditText(this);
        pageEdit.setSingleLine(true);
        pageEdit.setGravity(Gravity.CENTER);
        pageEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        pageEdit.setImeOptions(EditorInfo.IME_ACTION_GO);
        pageEdit.setText("1");
        pageCountText = text("/ 1", 13, false);
        pageCountText.setGravity(Gravity.CENTER_VERTICAL);
        pageSizeButton = button("每页 10");
        pager.addView(prevButton, new LinearLayout.LayoutParams(dp(56), dp(44)));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(dp(72), dp(44));
        editLp.setMargins(dp(6), 0, dp(4), 0);
        pager.addView(pageEdit, editLp);
        pager.addView(pageCountText, new LinearLayout.LayoutParams(dp(58), dp(44)));
        pager.addView(nextButton, new LinearLayout.LayoutParams(dp(56), dp(44)));
        LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        sizeLp.setMargins(dp(8), 0, 0, 0);
        pager.addView(pageSizeButton, sizeLp);
        root.addView(pager);

        LinearLayout selection = new LinearLayout(this);
        selection.setOrientation(LinearLayout.HORIZONTAL);
        selectAllButton = button("全选本页");
        downloadButton = button("下载最终图 0");
        clearSelectButton = button("取消选择");
        selection.addView(selectAllButton, weightedButton());
        LinearLayout.LayoutParams middle = weightedButton(); middle.setMargins(dp(6), dp(6), dp(6), 0);
        selection.addView(downloadButton, middle);
        LinearLayout.LayoutParams right = weightedButton(); right.setMargins(0, dp(6), 0, 0);
        selection.addView(clearSelectButton, right);
        root.addView(selection);

        ScrollView scroll = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        back.setOnClickListener(v -> finish());
        refresh.setOnClickListener(v -> refreshGallery(true));
        sortButton.setOnClickListener(v -> showSortMenu(v));
        prevButton.setOnClickListener(v -> goToPage(page - 1));
        nextButton.setOnClickListener(v -> goToPage(page + 1));
        pageSizeButton.setOnClickListener(v -> showPageSizeMenu(v));
        pageEdit.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (enter) {
                jumpFromInput();
                return true;
            }
            return false;
        });
        selectAllButton.setOnClickListener(v -> selectAllCurrentPage());
        downloadButton.setOnClickListener(v -> downloadSelectedFinals());
        clearSelectButton.setOnClickListener(v -> clearSelection());
        updateSelectionControls();
        setContentView(root);
    }

    private void refreshGallery(boolean forceMessage) {
        if (fetching || batchDownloading) return;
        fetching = true;
        if (forceMessage) state.setText("正在同步 ComfyUI history 与本机任务记录…");
        thumbPool.submit(() -> {
            LinkedHashMap<String, GalleryTask> tasks = new LinkedHashMap<>();
            String warning = "";

            // Local jobs preserve V2.3 first-seen generation order and are authoritative when present.
            for (JobRecord job : JobStore.completed(this)) {
                GalleryTask task = GalleryTask.fromJob(job);
                if (task != null) tasks.put(task.key, task);
            }

            // Merge server history for jobs that were generated outside this phone or older app records.
            try {
                ComfyApiClient api = new ComfyApiClient(server);
                List<ImageRef> remoteRefs = api.parseImagesFromAllHistory(api.getAllHistory(REMOTE_HISTORY_ITEMS), REMOTE_IMAGE_METADATA_LIMIT);
                LinkedHashMap<String, List<ImageRef>> grouped = new LinkedHashMap<>();
                for (ImageRef ref : remoteRefs) {
                    if (ref == null || ref.filename == null || ref.filename.isEmpty()) continue;
                    String prompt = safe(ref.promptId);
                    String groupKey = prompt.isEmpty() ? "legacy:" + ref.timestamp + ":" + ref.filename : "prompt:" + prompt;
                    grouped.computeIfAbsent(groupKey, x -> new ArrayList<>()).add(ref);
                }
                for (Map.Entry<String, List<ImageRef>> e : grouped.entrySet()) {
                    List<ImageRef> group = normalizeRefs(e.getValue());
                    if (group.isEmpty()) continue;
                    String prompt = safe(group.get(0).promptId);
                    GalleryTask existing = findByPrompt(tasks, prompt);
                    if (existing != null) {
                        existing.mergeMissing(group);
                    } else {
                        GalleryTask remote = GalleryTask.fromRemote(e.getKey(), prompt, server, group);
                        if (remote != null) tasks.put(remote.key, remote);
                    }
                }
            } catch (Exception e) {
                warning = e.getMessage() == null ? "history 读取失败" : e.getMessage();
            }

            List<GalleryTask> normalized = new ArrayList<>(tasks.values());
            String finalWarning = warning;
            runOnUiThread(() -> {
                fetching = false;
                allTasks.clear();
                allTasks.addAll(normalized);
                selectedTaskKeys.retainAll(taskKeys(allTasks));
                clampPage();
                renderCurrentPage();
                updateState(finalWarning);
            });
        });
    }

    private GalleryTask findByPrompt(Map<String, GalleryTask> tasks, String promptId) {
        if (promptId == null || promptId.isEmpty()) return null;
        for (GalleryTask t : tasks.values()) if (promptId.equals(t.promptId)) return t;
        return null;
    }

    private List<ImageRef> normalizeRefs(List<ImageRef> input) {
        LinkedHashMap<String, ImageRef> map = new LinkedHashMap<>();
        if (input != null) for (ImageRef ref : input) {
            if (ref == null || ref.filename == null || ref.filename.isEmpty()) continue;
            ImageRef old = map.get(ref.key());
            if (old == null || compareImageOrder(ref, old) >= 0) map.put(ref.key(), ref);
        }
        return new ArrayList<>(map.values());
    }

    private int compareImageOrder(ImageRef a, ImageRef b) {
        if (a.generatedAt != b.generatedAt) return Long.compare(a.generatedAt, b.generatedAt);
        if (a.outputOrder != b.outputOrder) return Integer.compare(a.outputOrder, b.outputOrder);
        return Long.compare(a.timestamp, b.timestamp);
    }

    private Set<String> taskKeys(List<GalleryTask> tasks) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (GalleryTask t : tasks) out.add(t.key);
        return out;
    }

    private void renderCurrentPage() {
        final int generation = ++renderGeneration;
        grid.removeAllViews();
        tileByTaskKey.clear();
        List<GalleryTask> sorted = sortedTasks();
        int pageCount = pageCount(sorted.size());
        if (page > pageCount) page = pageCount;
        if (page < 1) page = 1;
        int start = Math.min(sorted.size(), (page - 1) * pageSize);
        int end = Math.min(sorted.size(), start + pageSize);
        List<GalleryTask> visible = new ArrayList<>(sorted.subList(start, end));

        ComfyApiClient api = new ComfyApiClient(server);
        for (GalleryTask task : visible) addTaskTile(api, task, generation);
        updatePager(pageCount);
        updateSelectionControls();
    }

    private List<GalleryTask> sortedTasks() {
        List<GalleryTask> out = new ArrayList<>(allTasks);
        Comparator<GalleryTask> c;
        if (sortMode == SortMode.TIME_ASC) c = Comparator.comparingLong(x -> x.time);
        else if (sortMode == SortMode.NAME_ASC) c = Comparator.comparing(x -> x.displayName().toLowerCase(Locale.ROOT));
        else if (sortMode == SortMode.NAME_DESC) c = Comparator.comparing((GalleryTask x) -> x.displayName().toLowerCase(Locale.ROOT)).reversed();
        else c = Comparator.comparingLong((GalleryTask x) -> x.time).reversed();
        Collections.sort(out, c);
        return out;
    }

    private List<GalleryTask> visibleTasks() {
        List<GalleryTask> sorted = sortedTasks();
        int start = Math.min(sorted.size(), (Math.max(1, page) - 1) * pageSize);
        int end = Math.min(sorted.size(), start + pageSize);
        return new ArrayList<>(sorted.subList(start, end));
    }

    private void addTaskTile(ComfyApiClient api, GalleryTask task, int generation) {
        ImageRef ref = task.finalRef();
        if (ref == null) return;
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        setTileBackground(tile, selectedTaskKeys.contains(task.key));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(ThemeManager.imagePlaceholder(this));
        tile.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(168)));

        TextView name = text(task.displayName(), 11, true);
        name.setMaxLines(2);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        tile.addView(name);

        TextView meta = text(formatTime(task.time) + (task.refs.size() > 1 ? " · 📁 " + task.refs.size() + " 张" : ""), 10, false);
        meta.setTextColor(ThemeManager.muted(this));
        meta.setPadding(dp(2), dp(2), dp(2), 0);
        tile.addView(meta);

        int screen = getResources().getDisplayMetrics().widthPixels;
        int itemW = (screen - dp(42)) / 2;
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = itemW;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(tile, lp);
        tileByTaskKey.put(task.key, tile);

        // Critical V2.3 traffic rule: only current-page final thumbnails are requested.
        thumbPool.submit(() -> {
            try {
                if (generation != renderGeneration) return;
                ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                Bitmap bmp = MainActivity.decodeScaled(dl.bytes, 720, 720);
                runOnUiThread(() -> {
                    if (generation == renderGeneration && bmp != null) image.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (generation == renderGeneration) name.setText(task.displayName() + "\n（封面加载失败）");
                });
            }
        });

        tile.setOnLongClickListener(v -> {
            toggleSelected(task);
            Toast.makeText(this, "已进入多选模式；这里仅选择各任务最终图", Toast.LENGTH_SHORT).show();
            return true;
        });
        tile.setOnClickListener(v -> {
            if (!selectedTaskKeys.isEmpty()) toggleSelected(task);
            else showPreview(task);
        });
    }

    private void showPreview(GalleryTask task) {
        ImageRef ref = task.finalRef();
        if (ref == null) return;
        LinearLayout holder = new LinearLayout(this);
        holder.setGravity(Gravity.CENTER);
        holder.setPadding(dp(8), dp(8), dp(8), dp(8));
        holder.setBackgroundColor(ThemeManager.card(this));
        ImageView large = new ImageView(this);
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        large.setBackgroundColor(ThemeManager.imagePlaceholder(this));
        holder.addView(large, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(480)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(task.displayName())
                .setView(holder)
                .setNegativeButton("关闭", null)
                .setPositiveButton("保存最终图", null);
        if (task.refs.size() > 1) builder.setNeutralButton("查看全部图", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            if (task.refs.size() > 1) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    dialog.dismiss();
                    openTaskOutputs(task);
                });
            }
            thumbPool.submit(() -> {
                try {
                    ComfyApiClient.ImageDownload dl = new ComfyApiClient(task.server).fetchImage(ref);
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

    private void openTaskOutputs(GalleryTask task) {
        Intent i = new Intent(this, TaskOutputActivity.class);
        i.putExtra(TaskOutputActivity.EXTRA_LOCAL_ID, task.localId);
        i.putExtra(TaskOutputActivity.EXTRA_PROMPT_ID, task.promptId);
        i.putExtra(TaskOutputActivity.EXTRA_SERVER, task.server);
        i.putExtra(TaskOutputActivity.EXTRA_TITLE, task.displayName());
        startActivity(i);
    }

    private void toggleSelected(GalleryTask task) {
        if (!selectedTaskKeys.add(task.key)) selectedTaskKeys.remove(task.key);
        LinearLayout tile = tileByTaskKey.get(task.key);
        if (tile != null) setTileBackground(tile, selectedTaskKeys.contains(task.key));
        updateSelectionControls();
    }

    private void selectAllCurrentPage() {
        for (GalleryTask task : visibleTasks()) selectedTaskKeys.add(task.key);
        for (Map.Entry<String, LinearLayout> e : tileByTaskKey.entrySet()) setTileBackground(e.getValue(), selectedTaskKeys.contains(e.getKey()));
        updateSelectionControls();
    }

    private void clearSelection() {
        selectedTaskKeys.clear();
        for (LinearLayout tile : tileByTaskKey.values()) setTileBackground(tile, false);
        updateSelectionControls();
    }

    private void downloadSelectedFinals() {
        if (selectedTaskKeys.isEmpty()) {
            Toast.makeText(this, "先长按任务封面进行选择", Toast.LENGTH_SHORT).show();
            return;
        }
        List<GalleryTask> selected = new ArrayList<>();
        for (GalleryTask task : allTasks) if (selectedTaskKeys.contains(task.key) && task.finalRef() != null) selected.add(task);
        batchDownloading = true;
        downloadButton.setEnabled(false);
        state.setText("准备下载 " + selected.size() + " 个任务的最终图…");
        downloadPool.submit(() -> {
            int ok = 0;
            for (int i = 0; i < selected.size(); i++) {
                GalleryTask task = selected.get(i);
                ImageRef ref = task.finalRef();
                try {
                    ComfyApiClient.ImageDownload dl = new ComfyApiClient(task.server).fetchImage(ref);
                    MediaSaver.saveImage(this, dl.bytes, ref.filename, dl.mime);
                    ok++;
                } catch (Exception ignored) {}
                int done = i + 1;
                int good = ok;
                runOnUiThread(() -> state.setText("下载最终图：" + done + " / " + selected.size() + " · 成功 " + good));
            }
            int finalOk = ok;
            runOnUiThread(() -> {
                batchDownloading = false;
                Toast.makeText(this, "最终图下载完成：" + finalOk + " / " + selected.size(), Toast.LENGTH_LONG).show();
                clearSelection();
                updateState("");
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

    private void jumpFromInput() {
        String raw = pageEdit.getText().toString().trim();
        int target;
        try { target = Integer.parseInt(raw); }
        catch (Exception e) { target = page; }
        goToPage(target);
    }

    private void goToPage(int target) {
        int max = pageCount(allTasks.size());
        int clamped = Math.max(1, Math.min(target, max));
        if (clamped != target) Toast.makeText(this, "页码已调整到有效范围 1 - " + max, Toast.LENGTH_SHORT).show();
        page = clamped;
        clearSelection();
        renderCurrentPage();
    }

    private void showPageSizeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("每页 5 个任务");
        menu.getMenu().add("每页 10 个任务");
        menu.getMenu().add("每页 20 个任务");
        menu.setOnMenuItemClickListener(item -> {
            String t = String.valueOf(item.getTitle());
            int size = t.contains("20") ? 20 : t.contains("5") ? 5 : 10;
            pageSize = size;
            prefs.edit().putInt(PREF_PAGE_SIZE, size).apply();
            page = 1;
            clearSelection();
            renderCurrentPage();
            return true;
        });
        menu.show();
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
            page = 1;
            clearSelection();
            renderCurrentPage();
            return true;
        });
        menu.show();
    }

    private void updatePager(int pageCount) {
        pageEdit.setText(String.valueOf(page));
        pageCountText.setText("/ " + pageCount);
        pageSizeButton.setText("每页 " + pageSize);
        prevButton.setEnabled(page > 1 && !fetching && !batchDownloading);
        nextButton.setEnabled(page < pageCount && !fetching && !batchDownloading);
    }

    private void updateState(String warning) {
        if (allTasks.isEmpty()) {
            state.setText(warning == null || warning.isEmpty() ? "暂时没有图像任务" : "暂时没有可显示任务 · " + warning);
            return;
        }
        int count = pageCount(allTasks.size());
        String suffix = warning == null || warning.isEmpty() ? "" : " · history 暂不可用，已优先显示本机任务";
        state.setText("共 " + allTasks.size() + " 个任务 · 第 " + page + " / " + count + " 页 · 当前页只加载最终图 · 手动刷新" + suffix);
    }

    private void updateSelectionControls() {
        int n = selectedTaskKeys.size();
        downloadButton.setText("下载最终图 " + n);
        downloadButton.setEnabled(n > 0 && !batchDownloading);
        clearSelectButton.setEnabled(n > 0 && !batchDownloading);
        selectAllButton.setEnabled(!visibleTasks().isEmpty() && !batchDownloading);
    }

    private void setTileBackground(LinearLayout tile, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? ThemeManager.selected(this) : ThemeManager.card(this));
        bg.setCornerRadius(dp(14));
        if (selected) bg.setStroke(dp(2), ThemeManager.accent(this));
        tile.setBackground(bg);
    }

    private int pageCount(int total) { return Math.max(1, (total + pageSize - 1) / pageSize); }
    private void clampPage() { page = Math.max(1, Math.min(page, pageCount(allTasks.size()))); }
    private int normalizePageSize(int n) { return n == 5 || n == 20 ? n : 10; }
    private String safe(String s) { return s == null ? "" : s; }

    private String formatTime(long t) {
        if (t <= 0) return "时间未知";
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

    private static final class GalleryTask {
        final String key;
        final String localId;
        final String promptId;
        final String server;
        final String workflowName;
        final long time;
        final List<ImageRef> refs = new ArrayList<>();

        GalleryTask(String key, String localId, String promptId, String server, String workflowName,
                    long time, List<ImageRef> refs) {
            this.key = key;
            this.localId = localId == null ? "" : localId;
            this.promptId = promptId == null ? "" : promptId;
            this.server = server == null || server.isEmpty() ? "8188" : server;
            this.workflowName = workflowName == null ? "" : workflowName;
            this.time = time;
            mergeMissing(refs);
        }

        static GalleryTask fromJob(JobRecord job) {
            if (job == null) return null;
            List<ImageRef> refs = job.outputRefs();
            if (refs.isEmpty()) return null;
            long t = job.completedAt > 0 ? job.completedAt : job.updatedAt > 0 ? job.updatedAt : job.submittedAt;
            String key = job.localId == null || job.localId.isEmpty() ? "prompt:" + job.promptId : "local:" + job.localId;
            return new GalleryTask(key, job.localId, job.promptId, job.server, job.workflowName, t, refs);
        }

        static GalleryTask fromRemote(String key, String promptId, String server, List<ImageRef> refs) {
            if (refs == null || refs.isEmpty()) return null;
            long t = 0L;
            for (ImageRef ref : refs) t = Math.max(t, ref.timestamp);
            return new GalleryTask(key, "", promptId, server, "", t, refs);
        }

        void mergeMissing(List<ImageRef> more) {
            LinkedHashMap<String, ImageRef> map = new LinkedHashMap<>();
            for (ImageRef ref : refs) if (ref != null) map.put(ref.key(), ref);
            if (more != null) for (ImageRef ref : more) {
                if (ref == null || ref.filename == null || ref.filename.isEmpty()) continue;
                ImageRef old = map.get(ref.key());
                // Keep local V2.3 ordering when available; otherwise accept richer remote metadata.
                if (old == null || (old.generatedAt <= 0 && ref.generatedAt > 0) ||
                        (old.outputOrder <= 0 && ref.outputOrder > 0)) map.put(ref.key(), ref);
            }
            refs.clear();
            refs.addAll(map.values());
        }

        ImageRef finalRef() { return ImageRef.chooseFinal(refs); }

        String displayName() {
            if (workflowName != null && !workflowName.trim().isEmpty()) return workflowName;
            ImageRef f = finalRef();
            return f == null || f.filename == null || f.filename.isEmpty() ? "生成任务" : f.filename;
        }
    }
}
