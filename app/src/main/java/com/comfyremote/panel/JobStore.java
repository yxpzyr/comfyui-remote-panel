package com.comfyremote.panel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JobStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "comfy_remote";
    private static final String KEY = "generation_jobs_v18";
    private static final int MAX_RECORDS = 250;

    private JobStore() {}

    public static List<JobRecord> list(Context context) {
        synchronized (LOCK) {
            List<JobRecord> out = new ArrayList<>();
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
            if (raw != null && !raw.trim().isEmpty()) {
                try {
                    JSONArray a = new JSONArray(raw);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null) out.add(JobRecord.fromJson(o));
                    }
                } catch (Exception ignored) {}
            }
            out.sort(Comparator.comparingLong((JobRecord j) -> j.submittedAt).reversed());
            return out;
        }
    }

    public static JobRecord add(Context context, JobRecord record) {
        synchronized (LOCK) {
            List<JobRecord> list = list(context);
            list.add(0, record);
            trim(list);
            save(context, list);
            return record;
        }
    }

    public static JobRecord find(Context context, String localId) {
        for (JobRecord j : list(context)) if (j.localId.equals(localId)) return j;
        return null;
    }

    public static void attachPrompt(Context context, String localId, String promptId) {
        update(context, localId, j -> {
            j.promptId = promptId == null ? "" : promptId;
            j.status = JobRecord.QUEUED;
            j.message = "已提交到 ComfyUI 队列";
        });
    }

    public static void updateStatus(Context context, String localId, String status, String message, int outputCount) {
        update(context, localId, j -> {
            j.status = status;
            j.message = message == null ? "" : message;
            if (outputCount >= 0) j.outputCount = outputCount;
        });
    }

    public static void clearFinished(Context context) {
        synchronized (LOCK) {
            List<JobRecord> list = list(context);
            list.removeIf(JobRecord::isTerminal);
            save(context, list);
        }
    }

    public static void markInterruptedSubmissionsFailed(Context context) {
        synchronized (LOCK) {
            List<JobRecord> list = list(context);
            boolean changed = false;
            for (JobRecord j : list) {
                if (JobRecord.UPLOADING.equals(j.status) && (j.promptId == null || j.promptId.isEmpty())) {
                    j.status = JobRecord.FAILED;
                    j.message = "App 在任务提交完成前被关闭，请重新提交";
                    j.updatedAt = System.currentTimeMillis();
                    changed = true;
                }
            }
            if (changed) save(context, list);
        }
    }

    private interface Mutator { void apply(JobRecord j); }

    private static void update(Context context, String localId, Mutator mutator) {
        synchronized (LOCK) {
            List<JobRecord> list = list(context);
            for (JobRecord j : list) {
                if (j.localId.equals(localId)) {
                    mutator.apply(j);
                    j.updatedAt = System.currentTimeMillis();
                    break;
                }
            }
            save(context, list);
        }
    }

    private static void save(Context context, List<JobRecord> list) {
        list.sort(Comparator.comparingLong((JobRecord j) -> j.submittedAt).reversed());
        trim(list);
        JSONArray a = new JSONArray();
        for (JobRecord j : list) a.put(j.toJson());
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY, a.toString()).apply();
    }

    private static void trim(List<JobRecord> list) {
        while (list.size() > MAX_RECORDS) list.remove(list.size() - 1);
    }
}
