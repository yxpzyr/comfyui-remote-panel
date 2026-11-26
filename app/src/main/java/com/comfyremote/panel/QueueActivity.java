package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.util.LruCache;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QueueActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imagePool = Executors.newFixedThreadPool(2);
    private final ExecutorService listPool = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadPool = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Map<String, ImageView> thumbnailTargets = new ConcurrentHashMap<>();
    private volatile boolean visible;
    private volatile boolean readingJobs;
    private List<JobRecord> displayedJobs = new ArrayList<>();
    private Map<String, Integer> positions = new HashMap<>();
    private String displayedVersion = "";
    private BaseAdapter adapter;
    private final Set<String> loadingThumbs = ConcurrentHashMap.newKeySet();
    private ListView listContainer;
    private TextView state;
    private String server;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            renderJobs();
            handler.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);
        server = getSharedPreferences("comfy_remote", MODE_PRIVATE).getString("server", "8188");
        buildUi();
        GenerationManager.resumePending(this, server);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        displayedVersion = "";
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override protected void onStop() {
        visible = false;
        thumbnailTargets.clear();
        handler.removeCallbacks(refresher);
        super.onStop();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(18));
        root.setBackgroundColor(ThemeManager.background(this));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("← 返回");
        TextView title = text("生成队列", 22, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMargins(dp(10), 0, dp(8), 0);
        Button clear = button("清理完成");
        header.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));
        header.addView(title, titleLp);
        header.addView(clear, new LinearLayout.LayoutParams(dp(104), dp(48)));
        root.addView(header);

        state = text("正在读取任务…", 13, false);
        state.setTextColor(ThemeManager.secondary(this));
        state.setPadding(0, dp(8), 0, dp(10));
        root.addView(state);

        listContainer = new ListView(this);
        listContainer.setDividerHeight(dp(10));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return displayedJobs.size(); }
            @Override public JobRecord getItem(int position) { return displayedJobs.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                JobRecord job = getItem(position);
                return jobCard(job, positions.get(job.localId), recycled instanceof LinearLayout ? (LinearLayout) recycled : null);
            }
        };
        listContainer.setAdapter(adapter);
        root.addView(listContainer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        back.setOnClickListener(v -> finish());
        clear.setOnClickListener(v -> {
            JobStore.clearFinished(this);
            renderJobs();
        });
        setContentView(root);
    }

    private void renderJobs() {
        if (!visible || readingJobs || listPool.isShutdown()) return;
        readingJobs = true;
        listPool.execute(() -> {
            try {
                List<JobRecord> jobs = JobStore.list(getApplicationContext());
                StringBuilder version = new StringBuilder();
                int active = 0, done = 0;
                for (JobRecord job : jobs) {
                    if (!job.isTerminal()) active++;
                    if (JobRecord.COMPLETED.equals(job.status)) done++;
                    version.append(job.localId).append(':').append(job.updatedAt).append('|');
                }
                if (active > 0) version.append(System.currentTimeMillis() / 10000);
                final String key = version.toString();
                final int activeCount = active, doneCount = done;
                runOnUiThread(() -> {
                    readingJobs = false;
                    if (!visible || isDestroyed()) return;
                    state.setText("共 " + jobs.size() + " 个任务 · " + activeCount + " 个等待/生成中 · " + doneCount + " 个已完成 · 进度自动刷新");
                    if (!key.equals(displayedVersion) || displayedJobs.isEmpty()) {
                        displayedVersion = key;
                        displayedJobs = jobs;
                        positions = waitingPositions(jobs);
                        adapter.notifyDataSetChanged();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    readingJobs = false;
                    if (visible) state.setText("任务列表读取失败，原数据已保留：" + error.getMessage());
                });
            }
        });
    }

    @Override protected void onDestroy() {
        visible = false;
        handler.removeCallbacksAndMessages(null);
        listPool.shutdownNow(); imagePool.shutdownNow();
        downloadPool.shutdown();
        thumbCache.evictAll(); thumbnailTargets.clear();
        super.onDestroy();
    }

    private Map<String, Integer> waitingPositions(List<JobRecord> jobs) {
        List<JobRecord> waiting = new ArrayList<>();
        for (JobRecord j : jobs) {
            if (JobRecord.LOCAL_QUEUED.equals(j.status) || JobRecord.SUBMITTING.equals(j.status) || JobRecord.QUEUED.equals(j.status)) waiting.add(j);
        }
        waiting.sort(Comparator.comparingLong(j -> j.submittedAt));
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < waiting.size(); i++) out.put(waiting.get(i).localId, i + 1);
        return out;
    }

    private LinearLayout jobCard(JobRecord job, Integer waitingPosition, LinearLayout recycled) {
        LinearLayout card = recycled == null ? new LinearLayout(this) : recycled;
        if (card.getTag() instanceof String) thumbnailTargets.remove((String) card.getTag());
        card.setTag(job.localId);
        card.removeAllViews();
        card.setOnClickListener(null);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ThemeManager.card(this));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView workflow = text(job.workflowName, 15, true);
        TextView status = text(statusLabel(job.status), 13, true);
        status.setTextColor(statusColor(job.status));
        top.addView(workflow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(status);
        card.addView(top);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, dp(8), 0, 0);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(ThemeManager.imagePlaceholder(this));
        body.addView(thumb, new LinearLayout.LayoutParams(dp(116), dp(116)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.setMargins(dp(10), 0, 0, 0);
        body.addView(info, infoLp);

        TextView input = text(job.inputSummary == null || job.inputSummary.isEmpty() ? "输入：使用工作流原设置" : "输入：" + job.inputSummary, 12, false);
        input.setTextColor(ThemeManager.secondary(this));
        info.addView(input);

        if (job.outputSelectionSummary != null && !job.outputSelectionSummary.isEmpty()) {
            TextView outputSel = text("输出：" + job.outputSelectionSummary, 11, false);
            outputSel.setTextColor(ThemeManager.secondary(this));
            outputSel.setPadding(0, dp(3), 0, 0);
            info.addView(outputSel);
        }

        if (waitingPosition != null) {
            TextView pos = text("等待队列：第 " + waitingPosition + " 位", 11, true);
            pos.setTextColor(ThemeManager.warning(this));
            pos.setPadding(0, dp(4), 0, 0);
            info.addView(pos);
        }

        TextView time = text("加入：" + formatTime(job.submittedAt) +
                (job.promptId == null || job.promptId.isEmpty() ? "" : " · " + shortId(job.promptId)), 11, false);
        time.setTextColor(ThemeManager.muted(this));
        time.setPadding(0, dp(4), 0, 0);
        info.addView(time);

        TextView duration = text(durationText(job), 11, false);
        duration.setTextColor(ThemeManager.muted(this));
        duration.setPadding(0, dp(3), 0, 0);
        info.addView(duration);

        TextView message = text(job.message == null ? "" : job.message, 12, false);
        message.setPadding(0, dp(5), 0, 0);
        if (JobRecord.FAILED.equals(job.status)) {
            message.setMaxLines(5);
            message.setEllipsize(TextUtils.TruncateAt.END);
        }
        info.addView(message);
        card.addView(body);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(progressFor(job));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18));
        pp.setMargins(0, dp(9), 0, 0);
        card.addView(progress, pp);
        TextView progressText = text("总体进度 " + progressFor(job) + "%" +
                (job.currentNode == null || job.currentNode.isEmpty() ? "" : " · 节点 " + job.currentNode), 11, false);
        progressText.setTextColor(ThemeManager.secondary(this));
        card.addView(progressText);

        loadJobThumbnail(job, thumb);
        if (job.hasTextOutput()) {
            card.setOnClickListener(v -> TextResultView.show(this, job));
            thumb.setOnClickListener(v -> TextResultView.show(this, job));
            card.addView(text("点任务查看文本结果 · 支持复制提示词", 11, false));
        }
        if (job.resolvedSeedsJson != null && !job.resolvedSeedsJson.equals("{}")) {
            try {
                JSONObject seeds = new JSONObject(job.resolvedSeedsJson);
                java.util.Iterator<String> names = seeds.keys();
                StringBuilder line = new StringBuilder("实际种子："); int shown = 0;
                while (names.hasNext() && shown++ < 3) {
                    String key = names.next();
                    if (shown > 1) line.append(" · ");
                    line.append(seeds.optString(key));
                }
                card.addView(text(line.toString(), 11, false));
            } catch (Exception ignored) {}
        }

        List<ImageRef> outputs = job.outputRefs();
        ImageRef finalOutput = ImageRef.chooseFinalSaved(outputs);
        if (JobRecord.COMPLETED.equals(job.status) && finalOutput != null) {
            card.setOnClickListener(v -> showOutput(job, finalOutput));
            thumb.setOnClickListener(v -> showOutput(job, finalOutput));
            thumb.setOnLongClickListener(v -> {
                saveRef(job, finalOutput);
                return true;
            });
            TextView hint = text(finalOutput.isVideo() ? "▶ 点任务播放视频 · 长按下载原始视频" : "点任务/缩略图查看原图 · 长按缩略图保存", 11, false);
            hint.setTextColor(ThemeManager.accent(this));
            hint.setPadding(0, dp(5), 0, 0);
            card.addView(hint);
        }

        if(JobRecord.SUBMISSION_UNKNOWN.equals(job.status)){
            Button check=button("核对提交回执");
            check.setOnClickListener(v->GenerationManager.checkSubmissionReceipt(this,job.localId));
            card.addView(check,new LinearLayout.LayoutParams(-1,dp(48)));
            Button forget=button("结束本地跟踪");
            forget.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("结束本地跟踪？")
                    .setMessage("仅结束手机上的回执核对，不会停止服务器任务。请先在电脑确认任务状态，避免重复生成。")
                    .setNegativeButton("继续核对",null).setPositiveButton("结束跟踪",(d,which)->{
                        JobStore.updateStatus(this,job.localId,JobRecord.CANCELLED,"已结束本地跟踪；服务器任务状态请在电脑确认",0);
                        renderJobs();
                    }).show());
            card.addView(forget,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        if (JobRecord.FAILED.equals(job.status)) {
            Button details = button("📋 查看 / 复制完整错误");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.setMargins(0, dp(8), 0, 0);
            card.addView(details, lp);
            details.setOnClickListener(v -> showJobError(job));
        }

        if (JobRecord.LOCAL_QUEUED.equals(job.status) || JobRecord.SUBMITTING.equals(job.status) || JobRecord.QUEUED.equals(job.status)) {
            Button cancel = button("取消等待");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.setMargins(0, dp(8), 0, 0);
            card.addView(cancel, lp);
            cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("取消排队任务？")
                    .setMessage(job.promptId == null || job.promptId.isEmpty() ? "这个任务尚未提交到 ComfyUI，可直接取消。" : "只会从 ComfyUI 等待队列删除这个任务。")
                    .setNegativeButton("不取消", null)
                    .setPositiveButton("取消任务", (d, w) -> GenerationManager.cancelPending(this, server, job))
                    .show());
        } else if (JobRecord.RUNNING.equals(job.status) || JobRecord.SYNCING.equals(job.status)) {
            Button stop = button("中断当前任务");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.setMargins(0, dp(8), 0, 0);
            card.addView(stop, lp);
            stop.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("中断当前 ComfyUI 任务？")
                    .setMessage("ComfyUI 的 interrupt 会停止当前正在执行的任务；等待队列中的其他任务会保留。")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("中断", (d, w) -> GenerationManager.interruptCurrent(this, server, job))
                    .show());
        }
        return card;
    }

    private void showJobError(JobRecord job) {
        String saved = JobStore.readErrorDetails(this, job.localId);
        if (saved.isEmpty() && job.promptId != null && !job.promptId.isEmpty()) {
            Toast.makeText(this, "正在从 ComfyUI 恢复这条任务的执行错误…", Toast.LENGTH_SHORT).show();
            imagePool.submit(() -> {
                String resolved = "";
                String summary = "";
                try {
                    ComfyApiClient api = new ComfyApiClient(job.server == null || job.server.isEmpty()
                            ? server : job.server);
                    JSONObject history = api.getHistoryForPrompt(job.promptId);
                    resolved = api.historyErrorDetails(history, job.promptId);
                    summary = api.historyError(history, job.promptId);
                    if (!resolved.isEmpty()) {
                        JobStore.saveErrorDetails(this, job.localId, "Prompt ID: " + job.promptId + "\n" + resolved);
                        if (!summary.isEmpty())
                            JobStore.updateStatus(this, job.localId, JobRecord.FAILED,
                                    "ComfyUI 执行失败：" + summary, -1);
                    }
                } catch (Exception ignored) { }
                String details = resolved.isEmpty() ? (job.message == null ? "" : job.message)
                        : "Prompt ID: " + job.promptId + "\n" + resolved;
                runOnUiThread(() -> {
                    if (!isFinishing()) showJobErrorDialog(job, details);
                });
            });
            return;
        }
        showJobErrorDialog(job, saved.isEmpty() ? (job.message == null ? "" : job.message) : saved);
    }

    private void showJobErrorDialog(JobRecord job, String full) {
        String title = "任务失败 · " + (job.workflowName == null ? "工作流" : job.workflowName);
        String viewText = full.length() > 18000
                ? full.substring(0, 18000) + "\n\n（内容较长，点“复制完整日志”获取全部）"
                : full;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(viewText)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制完整日志", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("ComfyUI 错误详情",
                                "Prompt ID: " + (job.promptId == null ? "" : job.promptId) +
                                        "\n工作流: " + job.workflowName + "\n" + full));
                        Toast.makeText(this, "已复制完整错误，可直接粘贴到聊天", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private int progressFor(JobRecord job) {
        if (JobRecord.COMPLETED.equals(job.status)) return 100;
        if (JobRecord.FAILED.equals(job.status) || JobRecord.CANCELLED.equals(job.status)) return Math.max(0, job.progressPercent);
        if (JobRecord.SYNCING.equals(job.status)) return Math.max(95, job.progressPercent);
        if (JobRecord.RUNNING.equals(job.status)) return Math.max(10, job.progressPercent);
        if (JobRecord.QUEUED.equals(job.status)) return Math.max(5, job.progressPercent);
        if (JobRecord.SUBMITTING.equals(job.status)) return Math.max(2, job.progressPercent);
        return Math.max(0, job.progressPercent);
    }

    private String durationText(JobRecord job) {
        if (JobRecord.COMPLETED.equals(job.status)) {
            return "总用时：" + formatDuration(job.totalDurationMs()) + " · 生成：" + formatDuration(job.generationDurationMs());
        }
        if (JobRecord.FAILED.equals(job.status) || JobRecord.CANCELLED.equals(job.status)) {
            return "任务用时：" + formatDuration(job.totalDurationMs());
        }
        if (JobRecord.RUNNING.equals(job.status) || JobRecord.SYNCING.equals(job.status)) {
            return "生成已用：" + formatDuration(job.generationDurationMs()) + " · 总计：" + formatDuration(job.totalDurationMs());
        }
        return "已等待：" + formatDuration(job.totalDurationMs());
    }

    private void loadJobThumbnail(JobRecord job, ImageView target) {
        if (job.hasTextOutput()) { target.setImageResource(android.R.drawable.ic_menu_edit); return; }
        List<ImageRef> refs = job.outputRefs();
        ImageRef finalRef = ImageRef.chooseFinalSaved(refs);
        if (JobRecord.COMPLETED.equals(job.status) && finalRef != null) {
            String targetServer = job.server == null || job.server.isEmpty() ? server : job.server;
            thumbnailTargets.put(job.localId, target);
            GalleryThumbnailLoader.load(this, targetServer, finalRef, target, 320,
                    () -> visible && !isDestroyed() && thumbnailTargets.get(job.localId) == target);
            return;
        }
        if (job.inputPreviewUri == null || job.inputPreviewUri.isEmpty()) return;
        thumbnailTargets.put(job.localId, target);
        String key = "in|" + job.inputPreviewUri;
        Bitmap cached = thumbCache.get(key);
        if (cached != null) { target.setImageBitmap(cached); return; }
        if (!loadingThumbs.add(key)) return;
        imagePool.submit(() -> {
            try {
                Bitmap bmp = decodeUriThumb(Uri.parse(job.inputPreviewUri), 520, 520);
                if (bmp != null) thumbCache.put(key, bmp);
                runOnUiThread(() -> {
                    if (visible && !isDestroyed() && thumbnailTargets.get(job.localId) == target && bmp != null)
                        target.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {}
            finally { loadingThumbs.remove(key); }
        });
    }

    private Bitmap decodeUriThumb(Uri uri, int maxW, int maxH) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / sample > maxW * 2 || bounds.outHeight / sample > maxH * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private void showOutput(JobRecord job, ImageRef ref) {
        if (ref.isVideo()) {
            VideoPlayerActivity.open(this, job.server == null || job.server.isEmpty() ? server : job.server, ref);
            return;
        }
        LinearLayout holder = new LinearLayout(this);
        holder.setGravity(Gravity.CENTER);
        holder.setPadding(dp(8), dp(8), dp(8), dp(8));
        holder.setBackgroundColor(ThemeManager.card(this));
        ImageView large = new ImageView(this);
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        large.setBackgroundColor(ThemeManager.imagePlaceholder(this));
        holder.addView(large, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(500)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(job.workflowName + " · " + ref.filename)
                .setView(holder)
                .setNegativeButton("关闭", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            imagePool.submit(() -> {
                try {
                    String targetServer = job.server == null || job.server.isEmpty() ? server : job.server;
                    ComfyApiClient.ImageDownload dl = new ComfyApiClient(targetServer).fetchImage(ref);
                    Bitmap bmp = MainActivity.decodeScaled(dl.bytes, 2400, 2400);
                    runOnUiThread(() -> {
                        large.setImageBitmap(bmp);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> saveDownloaded(ref, dl));
                        large.setOnLongClickListener(v -> {
                            saveDownloaded(ref, dl);
                            return true;
                        });
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "原图加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        dialog.show();
    }

    private void saveRef(JobRecord job, ImageRef ref) {
        downloadPool.submit(() -> {
            try {
                String targetServer = job.server == null || job.server.isEmpty() ? server : job.server;
                new ComfyApiClient(targetServer).saveMedia(getApplicationContext(), ref);
                runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this,
                        ref.isVideo() ? "已保存到 Movies/ComfyRemote" : "已保存到 Pictures/ComfyRemote", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void saveDownloaded(ImageRef ref, ComfyApiClient.ImageDownload dl) {
        imagePool.submit(() -> {
            try {
                MediaSaver.saveImage(this, dl.bytes, ref.filename, dl.mime);
                runOnUiThread(() -> Toast.makeText(this, "已保存到 Pictures/ComfyRemote", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String statusLabel(String status) {
        if (JobRecord.LOCAL_QUEUED.equals(status)) return "本地等待";
        if (JobRecord.SUBMITTING.equals(status)) return "提交中";
        if (JobRecord.SUBMISSION_UNKNOWN.equals(status)) return "回执待确认";
        if (JobRecord.UPLOADING.equals(status)) return "准备中";
        if (JobRecord.QUEUED.equals(status)) return "等待中";
        if (JobRecord.RUNNING.equals(status)) return "生成中";
        if (JobRecord.SYNCING.equals(status)) return "同步结果";
        if (JobRecord.COMPLETED.equals(status)) return "已完成";
        if (JobRecord.CANCELLED.equals(status)) return "已取消";
        if (JobRecord.FAILED.equals(status)) return "失败";
        return status == null ? "未知" : status;
    }

    private int statusColor(String status) {
        if (JobRecord.COMPLETED.equals(status)) return ThemeManager.success(this);
        if (JobRecord.FAILED.equals(status)) return ThemeManager.error(this);
        if (JobRecord.CANCELLED.equals(status)) return ThemeManager.muted(this);
        if (JobRecord.RUNNING.equals(status) || JobRecord.SYNCING.equals(status)) return ThemeManager.accent(this);
        return ThemeManager.warning(this);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private String formatTime(long t) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(t));
    }

    private String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    private String shortId(String id) { return id == null || id.length() <= 8 ? id : id.substring(0, 8); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13);
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
