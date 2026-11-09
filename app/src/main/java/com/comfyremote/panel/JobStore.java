package com.comfyremote.panel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JobStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "comfy_remote";
    private static final String KEY = "generation_jobs_v18"; // preserve V1.8 history
    private static final int MAX_RECORDS = 300;

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
        if (localId == null) return null;
        for (JobRecord j : list(context)) if (localId.equals(j.localId)) return j;
        return null;
    }

    public static JobRecord latestCompleted(Context context) {
        for (JobRecord j : list(context)) if (JobRecord.COMPLETED.equals(j.status)) return j;
        return null;
    }

    public static void markSubmitting(Context context, String localId) {
        update(context, localId, j -> {
            j.status = JobRecord.SUBMITTING;
            j.message = "正在提交到 ComfyUI";
            j.progressPercent = Math.max(j.progressPercent, 2);
        });
    }

    public static void attachPrompt(Context context, String localId, String promptId) {
        update(context, localId, j -> {
            j.promptId = promptId == null ? "" : promptId;
            j.status = JobRecord.QUEUED;
            j.message = "已提交到 ComfyUI 队列";
            j.promptSubmittedAt = System.currentTimeMillis();
            j.progressPercent = Math.max(j.progressPercent, 5);
        });
    }

    public static void markStarted(Context context, String localId, String message) {
        update(context, localId, j -> {
            if (j.startedAt <= 0) j.startedAt = System.currentTimeMillis();
            j.status = JobRecord.RUNNING;
            if (message != null && !message.isEmpty()) j.message = message;
            j.progressPercent = Math.max(j.progressPercent, 10);
        });
    }

    public static void updateProgress(Context context, String localId, int percent, String currentNode, String message) {
        update(context, localId, j -> {
            int p = Math.max(0, Math.min(99, percent));
            j.progressPercent = Math.max(j.progressPercent, p);
            if (currentNode != null) j.currentNode = currentNode;
            if (message != null && !message.isEmpty()) j.message = message;
            if (p >= 10 && !JobRecord.SYNCING.equals(j.status)) {
                j.status = JobRecord.RUNNING;
                if (j.startedAt <= 0) j.startedAt = System.currentTimeMillis();
            }
        });
    }

    public static void updateStatus(Context context, String localId, String status, String message, int outputCount) {
        update(context, localId, j -> {
            j.status = status;
            j.message = message == null ? "" : message;
            if (outputCount >= 0) j.outputCount = outputCount;
            if (JobRecord.SYNCING.equals(status)) j.progressPercent = Math.max(j.progressPercent, 95);
            if (JobRecord.COMPLETED.equals(status)) {
                j.progressPercent = 100;
                if (j.completedAt <= 0) j.completedAt = System.currentTimeMillis();
            }
            if (JobRecord.FAILED.equals(status) || JobRecord.CANCELLED.equals(status)) {
                if (j.completedAt <= 0) j.completedAt = System.currentTimeMillis();
            }
        });
    }

    public static void complete(Context context, String localId, List<ImageRef> refs, String message) {
        update(context, localId, j -> {
            JSONArray a = new JSONArray();
            if (refs != null) for (ImageRef ref : refs) a.put(ref.toJson());
            j.outputRefsJson = a.toString();
            j.outputCount = refs == null ? 0 : refs.size();
            j.status = JobRecord.COMPLETED;
            j.progressPercent = 100;
            j.message = message == null ? "生成完成" : message;
            j.completedAt = System.currentTimeMillis();
            if (j.startedAt <= 0) j.startedAt = j.promptSubmittedAt > 0 ? j.promptSubmittedAt : j.submittedAt;
        });
    }

    /** Persist V2.3 first-seen output order while a task is still running. */
    public static void observeOutputs(Context context, String localId, List<ImageRef> refs) {
        update(context, localId, j -> {
            JSONArray a = new JSONArray();
            if (refs != null) for (ImageRef ref : refs) a.put(ref.toJson());
            j.outputRefsJson = a.toString();
            j.outputCount = refs == null ? 0 : refs.size();
        });
    }

    public static List<JobRecord> completed(Context context) {
        List<JobRecord> out = new ArrayList<>();
        for (JobRecord j : list(context)) if (JobRecord.COMPLETED.equals(j.status) && !j.outputRefs().isEmpty()) out.add(j);
        return out;
    }

    /** Store long Python tracebacks outside SharedPreferences and the 1.2s queue render loop. */
    public static void saveErrorDetails(Context context, String localId, String details) {
        if (localId == null || localId.isEmpty() || details == null || details.isEmpty()) return;
        try {
            File dir = new File(context.getFilesDir(), "comfy_task_errors");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File target = new File(dir, safeErrorFilename(localId));
            String text = details.length() > 64000 ? details.substring(0, 64000) : details;
            Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w("JobStore", "无法保存完整任务错误日志", e);
        }
    }

    public static String readErrorDetails(Context context, String localId) {
        if (localId == null || localId.isEmpty()) return "";
        try {
            File target = new File(new File(context.getFilesDir(), "comfy_task_errors"),
                    safeErrorFilename(localId));
            if (!target.isFile()) return "";
            return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeErrorFilename(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_") + ".log";
    }

    public static void clearFinished(Context context) {
        synchronized (LOCK) {
            List<JobRecord> list = list(context);
            for (JobRecord j : list) {
                if (j.isTerminal()) {
                    File log = new File(new File(context.getFilesDir(), "comfy_task_errors"),
                            safeErrorFilename(j.localId));
                    if (log.isFile() && !log.delete())
                        android.util.Log.w("JobStore", "无法清理旧错误日志");
                }
            }
            list.removeIf(JobRecord::isTerminal);
            save(context, list);
        }
    }

    public static List<ImageRef> recentOutputRefs(Context context, int max) {
        List<ImageRef> out = new ArrayList<>();
        java.util.LinkedHashMap<String, ImageRef> seen = new java.util.LinkedHashMap<>();
        for (JobRecord j : list(context)) {
            if (!JobRecord.COMPLETED.equals(j.status)) continue;
            for (ImageRef ref : j.outputRefs()) {
                ImageRef normalized = ref.timestamp > 0 ? ref : new ImageRef(ref.filename, ref.subfolder, ref.type,
                        j.completedAt > 0 ? j.completedAt : j.updatedAt, j.promptId);
                seen.putIfAbsent(normalized.key(), normalized);
            }
        }
        out.addAll(seen.values());
        out.sort(Comparator.comparingLong((ImageRef x) -> x.timestamp).reversed());
        if (out.size() > max) return new ArrayList<>(out.subList(0, max));
        return out;
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
