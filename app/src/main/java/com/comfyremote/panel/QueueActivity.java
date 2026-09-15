package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class QueueActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout listContainer;
    private TextView state;
    private String server;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            renderJobs();
            handler.postDelayed(this, 1200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = getSharedPreferences("comfy_remote", MODE_PRIVATE).getString("server", "8188");
        buildUi();
        GenerationManager.resumePending(this, server);
    }

    @Override protected void onStart() {
        super.onStart();
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
        root.setBackgroundColor(Color.rgb(246, 247, 249));

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
        state.setTextColor(Color.DKGRAY);
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
        listContainer.removeAllViews();
        int active = 0;
        for (JobRecord job : jobs) if (!job.isTerminal()) active++;
        state.setText("共 " + jobs.size() + " 个任务 · " + active + " 个进行中/等待中 · ComfyUI 负责实际 GPU 排队");
        if (jobs.isEmpty()) {
            TextView empty = text("还没有任务。回到主界面选择输入图后点“加入队列”。", 14, false);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(0, dp(24), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (JobRecord job : jobs) listContainer.addView(jobCard(job), cardParams());
    }

    private LinearLayout jobCard(JobRecord job) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
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

        TextView input = text(job.inputSummary == null || job.inputSummary.isEmpty() ? "输入：使用工作流原设置" : "输入：" + job.inputSummary, 12, false);
        input.setTextColor(Color.DKGRAY);
        input.setPadding(0, dp(6), 0, 0);
        card.addView(input);

        TextView time = text("提交：" + formatTime(job.submittedAt) +
                (job.promptId == null || job.promptId.isEmpty() ? "" : " · " + shortId(job.promptId)), 11, false);
        time.setTextColor(Color.GRAY);
        time.setPadding(0, dp(4), 0, 0);
        card.addView(time);

        TextView message = text(job.message == null ? "" : job.message, 12, false);
        message.setPadding(0, dp(7), 0, 0);
        card.addView(message);

        if (JobRecord.QUEUED.equals(job.status)) {
            Button cancel = button("取消等待");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.setMargins(0, dp(8), 0, 0);
            card.addView(cancel, lp);
            cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("取消排队任务？")
                    .setMessage("只会从 ComfyUI 等待队列删除这个任务，不影响正在运行的任务。")
                    .setNegativeButton("不取消", null)
                    .setPositiveButton("取消任务", (d, w) -> GenerationManager.cancelPending(this, server, job))
                    .show());
        } else if (JobRecord.RUNNING.equals(job.status)) {
            Button stop = button("中断当前任务");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.setMargins(0, dp(8), 0, 0);
            card.addView(stop, lp);
            stop.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("中断当前 ComfyUI 任务？")
                    .setMessage("ComfyUI 的 interrupt 会停止当前正在执行的任务。等待队列里的其他任务会保留。")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("中断", (d, w) -> GenerationManager.interruptCurrent(this, server, job))
                    .show());
        }
        return card;
    }

    private String statusLabel(String status) {
        if (JobRecord.UPLOADING.equals(status)) return "上传中";
        if (JobRecord.QUEUED.equals(status)) return "等待中";
        if (JobRecord.RUNNING.equals(status)) return "生成中";
        if (JobRecord.SYNCING.equals(status)) return "同步结果";
        if (JobRecord.COMPLETED.equals(status)) return "已完成";
        if (JobRecord.CANCELLED.equals(status)) return "已取消";
        if (JobRecord.FAILED.equals(status)) return "失败";
        return status == null ? "未知" : status;
    }

    private int statusColor(String status) {
        if (JobRecord.COMPLETED.equals(status)) return Color.rgb(30, 145, 80);
        if (JobRecord.FAILED.equals(status)) return Color.rgb(200, 55, 55);
        if (JobRecord.CANCELLED.equals(status)) return Color.GRAY;
        if (JobRecord.RUNNING.equals(status)) return Color.rgb(30, 105, 200);
        return Color.rgb(210, 135, 0);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private String formatTime(long t) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(t));
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
        t.setTextColor(Color.rgb(28, 30, 35));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
}
