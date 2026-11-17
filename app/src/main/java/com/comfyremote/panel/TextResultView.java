package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

final class TextResultView {
    private static final java.util.concurrent.ExecutorService READER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextResultView() {}
    static LinearLayout create(Activity activity, JobRecord job) {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 12, 16, 12);
        TextView heading = new TextView(activity); heading.setText("生成的提示词 / 文本结果"); heading.setTextSize(17);
        heading.setTextColor(ThemeManager.text(activity)); box.addView(heading);
        try {
            JSONArray outputs = new JSONArray(job.textOutputsJson);
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject output = outputs.getJSONObject(i); final String content = output.optString("text");
                TextView result = new TextView(activity);
                result.setText(content.length() > 30000 ? content.substring(0, 30000) + "\n（内容较长，可复制完整结果）" : content);
                result.setTextSize(14); result.setTextColor(ThemeManager.text(activity)); result.setTextIsSelectable(true);
                result.setPadding(0, 16, 0, 8); box.addView(result);
                Button copy = new Button(activity); copy.setText(outputs.length() == 1 ? "复制提示词" : "复制结果 " + (i + 1));
                copy.setOnClickListener(v -> copy(activity, content)); box.addView(copy);
            }
        } catch (Exception ignored) {}
        return box;
    }
    static void show(Activity activity, JobRecord job) {
        if (job.hasTextOutput() && job.textOutputsJson.equals("[]")) {
            READER.execute(() -> {
                try {
                    JobRecord loaded = JobStore.loadTextResults(activity.getApplicationContext(), job.localId);
                    activity.runOnUiThread(() -> { if (!activity.isDestroyed() && !activity.isFinishing() && loaded != null) show(activity, loaded); });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> { if (!activity.isDestroyed()) Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show(); });
                }
            });
            return;
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(create(activity, job));
        new AlertDialog.Builder(activity).setTitle("提示词结果").setView(scroll)
                .setPositiveButton("复制全部", (dialog, which) -> copy(activity, TextOutputs.combined(job.textOutputsJson)))
                .setNegativeButton("关闭", null).show();
    }
    static void copy(Activity activity, String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("生成的提示词", text));
        Toast.makeText(activity, "提示词已复制", Toast.LENGTH_SHORT).show();
    }
}
