package com.comfyremote.panel;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small, privacy-conscious persistent crash/failure summary. Independent of
 * removable job queue entries. Never stores prompts, API payloads, media or files.
 */
public final class FailureDiagnostics {
    private static final String PREFS = "comfy_remote";
    private static final String KEY = "video_generation_diagnostics_v283";
    private static final int LIMIT = 25;
    private FailureDiagnostics() {}

    public static synchronized void record(Context context, JobRecord job) {
        if (job == null || !JobRecord.FAILED.equals(job.status)) return;
        try {
            SharedPreferences pref = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(pref.getString(KEY, "[]"));
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("time", System.currentTimeMillis());
            entry.put("job", job.localId == null ? "" : job.localId);
            entry.put("prompt_id", job.promptId == null ? "" : job.promptId);
            entry.put("workflow", safe(job.workflowName, 120));
            entry.put("message", safe(job.message, 2200));
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < LIMIT; i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous == null || entry.optString("job").equals(previous.optString("job"))) continue;
                next.put(previous);
            }
            pref.edit().putString(KEY, next.toString()).apply();
        } catch (Exception e) {
            android.util.Log.e("FailureDiagnostics", "Unable to persist failure summary", e);
        }
    }

    public static synchronized String display(Context context) {
        StringBuilder out = new StringBuilder();
        try {
            JSONArray entries = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]"));
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject j = entries.optJSONObject(i);
                if (j == null) continue;
                if (out.length() > 0) out.append("\n\n────\n");
                out.append(format.format(new Date(j.optLong("time", 0L))))
                        .append("\n工作流：").append(j.optString("workflow", ""))
                        .append("\n任务：").append(j.optString("prompt_id", "未提交"))
                        .append("\n错误：").append(j.optString("message", ""));
            }
        } catch (Exception ignored) {}
        return out.length() > 0 ? out.toString() : "暂无失败诊断记录。\n今后即使清理任务列表，这里仍会保留最近的错误摘要。";
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    private static String safe(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
