package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private final ExecutorService imagePool = Executors.newFixedThreadPool(4);
    private final Map<String, Bitmap> thumbCache = new ConcurrentHashMap<>();
    private final Set<String> loadingThumbs = ConcurrentHashMap.newKeySet();
    private LinearLayout listContainer;
    private TextView state;
    private String server;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            renderJobs();
            handler.postDelayed(this, 1200);
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
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override protected void onStop() {
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

        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        back.setOnClickListener(v -> finish());
        clear.setOnClickListener(v -> {
            JobStore.clearFinished(this);
            renderJobs();
        });
        setContentView(root);
    }

    private void renderJobs() {
        List<JobRecord> jobs = JobStore.list(this);
        Map<String, Integer> waitingPositions = waitingPositions(jobs);
        listContainer.removeAllViews();
        int active = 0, done = 0;
        for (JobRecord job : jobs) {
            if (!job.isTerminal()) active++;
            if (JobRecord.COMPLETED.equals(job.status)) done++;
        }
        state.setText("共 " + jobs.size() + " 个任务 · " + active + " 个等待/生成中 · " + done + " 个已完成 · 进度自动刷新");
        if (jobs.isEmpty()) {
            TextView empty = text("还没有任务。回到主界面选择图片，上传完成后点“开始生成”。", 14, false);
            empty.setTextColor(ThemeManager.muted(this));
            empty.setPadding(0, dp(24), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (JobRecord job : jobs) listContainer.addView(jobCard(job, waitingPositions.get(job.localId)), cardParams());
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

    private LinearLayout jobCard(JobRecord job, Integer waitingPosition) {
        LinearLayout card = new LinearLayout(this);
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

        List<ImageRef> outputs = job.outputRefs();
        if (JobRecord.COMPLETED.equals(job.status) && !outputs.isEmpty()) {
            card.setOnClickListener(v -> showOutput(job, outputs.get(0)));
            thumb.setOnClickListener(v -> showOutput(job, outputs.get(0)));
            thumb.setOnLongClickListener(v -> {
                saveRef(job, outputs.get(0));
                return true;
            });
            TextView hint = text("点任务/缩略图查看原图 · 长按缩略图保存", 11, false);
            hint.setTextColor(ThemeManager.accent(this));
            hint.setPadding(0, dp(5), 0, 0);
            card.addView(hint);
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
        List<ImageRef> refs = job.outputRefs();
        if (JobRecord.COMPLETED.equals(job.status) && !refs.isEmpty()) {
            ImageRef ref = refs.get(0);
            String key = "out|" + ref.key();
            Bitmap cached = thumbCache.get(key);
            if (cached != null) { target.setImageBitmap(cached); return; }
            if (!loadingThumbs.add(key)) return;
            imagePool.submit(() -> {
                try {
                    String targetServer = job.server == null || job.server.isEmpty() ? server : job.server;
                    ComfyApiClient.ImageDownload dl = new ComfyApiClient(targetServer).fetchImage(ref);
                    Bitmap bmp = MainActivity.decodeScaled(dl.bytes, 520, 520);
                    if (bmp != null) thumbCache.put(key, bmp);
                    runOnUiThread(this::renderJobs);
                } catch (Exception ignored) {}
                finally { loadingThumbs.remove(key); }
            });
            return;
        }
        if (job.inputPreviewUri == null || job.inputPreviewUri.isEmpty()) return;
        String key = "in|" + job.inputPreviewUri;
        Bitmap cached = thumbCache.get(key);
        if (cached != null) { target.setImageBitmap(cached); return; }
        if (!loadingThumbs.add(key)) return;
        imagePool.submit(() -> {
            try {
                Bitmap bmp = decodeUriThumb(Uri.parse(job.inputPreviewUri), 520, 520);
                if (bmp != null) thumbCache.put(key, bmp);
                runOnUiThread(this::renderJobs);
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
        imagePool.submit(() -> {
            try {
                String targetServer = job.server == null || job.server.isEmpty() ? server : job.server;
                ComfyApiClient.ImageDownload dl = new ComfyApiClient(targetServer).fetchImage(ref);
                MediaSaver.saveImage(this, dl.bytes, ref.filename, dl.mime);
                runOnUiThread(() -> Toast.makeText(this, "已保存到 Pictures/ComfyRemote", Toast.LENGTH_SHORT).show());
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
