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
    private static String cachedRaw;
    private static String cachedDirectory;
    private static List<JobRecord> cachedJobs = new ArrayList<>();

    private JobStore() {}

    public static List<JobRecord> list(Context context) {
        synchronized (LOCK) {
            List<JobRecord> out = new ArrayList<>();
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
            String directory = context.getFilesDir().getAbsolutePath();
            if (directory.equals(cachedDirectory) && raw != null && raw.equals(cachedRaw))
                return copies(cachedJobs);
            if (raw != null && !raw.trim().isEmpty()) {
                boolean migrated = false;
                try (android.util.JsonReader reader = new android.util.JsonReader(new java.io.StringReader(raw))) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue; }
                        JSONObject o = new JSONObject();
                        String payload = "";
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String key = reader.nextName();
                            if ("prompt_json".equals(key)) {
                                if (reader.peek() == android.util.JsonToken.STRING) payload = reader.nextString();
                                else reader.skipValue();
                            } else {
                                Object value = ComfyApiClient.readGalleryJson(reader, 0);
                                if (value != null) o.put(key, value);
                            }
                        }
                        reader.endObject();
                        if (!payload.isEmpty()) o.put("submission_payload_saved", true);
                        JobRecord job = JobRecord.fromJson(o);
                        if (!payload.isEmpty()) {
                            // Preserve the original index before compacting it. Each graph
                            // is streamed to one private file; never retain 300 graphs in RAM.
                            File backup = new File(payloadDir(context), "legacy_index_backup.json");
                            if (!backup.exists()) atomicText(backup, raw);
                            atomicText(payloadFile(context, job.localId), payload);
                            job.submissionPayloadSaved = true;
                            migrated = true;
                        }
                        job.promptJson = "";
                        out.add(job);
                    }
                    reader.endArray();
                    if (migrated) {
                        raw = metadataJson(out);
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply();
                    }
                } catch (Exception error) {
                    // Never replace a failed migration with an empty history.
                    throw new IllegalStateException("任务历史读取失败，原始记录已保留", error);
                }
            }
            out.sort(Comparator.comparingLong((JobRecord j) -> j.submittedAt).reversed());
            cachedRaw = raw; cachedDirectory = directory; cachedJobs = out;
            return copies(out);
        }
    }

    public static JobRecord loadSubmission(Context context, String localId) {
        synchronized (LOCK) {
            JobRecord job = find(context, localId);
            if (job == null || !job.hasSubmissionPayload()) return job;
            try {
                job.promptJson = new String(Files.readAllBytes(payloadFile(context, localId).toPath()), StandardCharsets.UTF_8);
                return job;
            } catch (Exception e) { throw new IllegalStateException("任务提交快照读取失败，未重新提交", e); }
        }
    }

    public static JobRecord loadTextResults(Context context, String localId) {
        synchronized (LOCK) {
            JobRecord job = find(context, localId);
            if (job == null || !job.hasTextOutput()) return job;
            try {
                job.textOutputsJson = new String(Files.readAllBytes(textFile(context, localId).toPath()), StandardCharsets.UTF_8);
                return job;
            } catch (Exception error) { throw new IllegalStateException("文本结果读取失败，原任务记录已保留", error); }
        }
    }

    private static File textFile(Context context, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("无效任务 ID");
        File dir = new File(context.getFilesDir(), "text_outputs_v2811");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建文本结果目录");
        return new File(dir, id + ".json");
    }

    private static List<JobRecord> copies(List<JobRecord> source) {
        List<JobRecord> result = new ArrayList<>(source.size());
        for (JobRecord job : source) result.add(JobRecord.fromJson(job.toJson()));
        return result;
    }

    private static File payloadDir(Context context) {
        File dir = new File(context.getFilesDir(), "job_payloads_v289");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建任务快照目录");
        return dir;
    }

    private static File payloadFile(Context context, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("无效任务 ID");
        return new File(payloadDir(context), id + ".json");
    }

    private static void atomicText(File target, String value) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (java.io.Writer writer = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8)) { writer.write(value); }
            try { Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { temp.delete(); }
    }

    private static String metadataJson(List<JobRecord> jobs) throws Exception {
        JSONArray result = new JSONArray();
        for (JobRecord job : jobs) {
            JSONObject metadata = job.toJson();
            metadata.put("prompt_json", "");
            result.put(metadata);
        }
        return result.toString();
    }

    public static JobRecord add(Context context, JobRecord record) {
        synchronized (LOCK) {
            List<JobRecord> list = list(context);
            list.add(0, record);
            trim(list);
            save(context, list);
            prunePayloads(context, list);
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
            if(j.isTerminal()||"attempted".equals(j.submissionPhase))return;
            j.status = JobRecord.SUBMITTING;
            j.submissionPhase = "preparing";
            j.message = "正在提交到 ComfyUI";
            j.progressPercent = Math.max(j.progressPercent, 2);
        });
    }

    public static void markSubmissionAttempted(Context context,String localId,String clientId) {
        synchronized(LOCK){
            update(context,localId,j->{if(!j.isTerminal()){j.clientId=clientId;j.submissionPhase="attempted";}});
            // Persist the receipt key before the non-idempotent POST can leave the device.
            if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,cachedRaw).commit())
                throw new IllegalStateException("提交记录未能保存，本次未发送");
        }
    }

    public static void attachPrompt(Context context, String localId, String promptId) {
        attachPrompt(context, localId, promptId, "");
    }

    public static void attachPrompt(Context context, String localId, String promptId, String clientId) {
        update(context, localId, j -> {
            j.clientId = clientId == null ? "" : clientId;
            j.promptId = promptId == null ? "" : promptId;
            j.status = JobRecord.QUEUED;
            j.message = "已提交到 ComfyUI 队列";
            j.promptSubmittedAt = System.currentTimeMillis();
            j.progressPercent = Math.max(j.progressPercent, 5);
        });
    }

    public static void markStarted(Context context, String localId, String message) {
        update(context, localId, j -> {
            if (j.isTerminal()) return;
            if (j.startedAt <= 0) j.startedAt = System.currentTimeMillis();
            j.status = JobRecord.RUNNING;
            if (message != null && !message.isEmpty()) j.message = message;
            j.progressPercent = Math.max(j.progressPercent, 10);
        });
    }

    public static void updateProgress(Context context, String localId, int percent, String currentNode, String message) {
        update(context, localId, j -> {
            if (j.isTerminal()) return;
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
            if(j.isTerminal()&&!j.status.equals(status))return;
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
        complete(context, localId, refs, new JSONArray(), message);
    }

    public static void complete(Context context, String localId, List<ImageRef> refs, JSONArray texts, String message) {
        update(context, localId, j -> {
            JSONArray a = new JSONArray();
            if (refs != null) for (ImageRef ref : refs) a.put(ref.toJson());
            j.outputRefsJson = a.toString();
            j.textOutputsJson = texts == null ? "[]" : texts.toString();
            j.outputCount = refs == null ? 0 : refs.size();
            j.status = JobRecord.COMPLETED;
            j.progressPercent = 100;
            j.message = message == null ? "生成完成" : message;
            j.completedAt = System.currentTimeMillis();
            if (j.startedAt <= 0) j.startedAt = j.promptSubmittedAt > 0 ? j.promptSubmittedAt : j.submittedAt;
        });
    }

    public static void freezeSubmission(Context context, String localId, JSONObject prompt, JSONObject seeds) {
        synchronized (LOCK) {
            List<JobRecord> jobs = list(context);
            for (JobRecord job : jobs) if (job.localId.equals(localId)) {
                job.promptJson = prompt.toString();
                job.resolvedSeedsJson = seeds.toString();
                job.expectsTextOutput = TextOutputs.expectsText(prompt);
                job.updatedAt = System.currentTimeMillis();
                save(context, jobs);
                return;
            }
            throw new IllegalStateException("找不到任务快照，未提交");
        }
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
            prunePayloads(context, list);
        }
    }

    private static void prunePayloads(Context context, List<JobRecord> retained) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (JobRecord job : retained) live.add(job.localId + ".json");
        File[] files = payloadDir(context).listFiles((dir, name) ->
                name.matches("[A-Za-z0-9_-]+\\.json") && !name.equals("legacy_index_backup.json"));
        if (files != null) for (File file : files) {
            if (!live.contains(file.getName()) && !file.delete())
                android.util.Log.w("JobStore", "无法清理已移除任务的提交快照");
        }
        File[] results = new File(context.getFilesDir(), "text_outputs_v2811").listFiles((dir, name) -> name.matches("[A-Za-z0-9_-]+\\.json"));
        if (results != null) for (File file : results) {
            if (!live.contains(file.getName())) file.delete();
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
                    String before = j.toJson().toString();
                    mutator.apply(j);
                    if (before.equals(j.toJson().toString())) return;
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
        try {
            for (JobRecord job : list) {
                if (job.textOutputsJson != null && !job.textOutputsJson.equals("[]")) {
                    atomicText(textFile(context, job.localId), job.textOutputsJson);
                    job.textResultSaved = true; job.textOutputsJson = "[]";
                }
                if (job.promptJson != null && !job.promptJson.isEmpty()) {
                    atomicText(payloadFile(context, job.localId), job.promptJson);
                    job.submissionPayloadSaved = true;
                    job.promptJson = "";
                }
            }
            String raw = metadataJson(list);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply();
            cachedRaw = raw; cachedDirectory = context.getFilesDir().getAbsolutePath();
            cachedJobs = copies(list);
        } catch (Exception error) { throw new IllegalStateException("任务保存失败，原始记录已保留", error); }
    }

    private static void trim(List<JobRecord> list) {
        while (list.size() > MAX_RECORDS) list.remove(list.size() - 1);
    }
}
