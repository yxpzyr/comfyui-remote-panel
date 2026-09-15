package com.comfyremote.panel;

import android.content.Context;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V1.9 queue engine. UI submissions are snapshotted immediately and a single-thread
 * submitter preserves tap order. ComfyUI remains the source of truth for GPU order.
 */
public final class GenerationManager {
    public interface JobListener { void onJobChanged(JobRecord job); }

    private static final ExecutorService SUBMITTER = Executors.newSingleThreadExecutor();
    private static final ExecutorService MONITOR_POOL = Executors.newCachedThreadPool();
    private static final Set<String> SUBMITTING = ConcurrentHashMap.newKeySet();
    private static final Set<String> MONITORING = ConcurrentHashMap.newKeySet();
    private static final Set<JobListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Map<String, ComfyProgressSocket.Handle> SOCKETS = new ConcurrentHashMap<>();

    private GenerationManager() {}

    public static void addListener(JobListener l) { if (l != null) LISTENERS.add(l); }
    public static void removeListener(JobListener l) { if (l != null) LISTENERS.remove(l); }

    public static void enqueueSubmission(Context context, String localId) {
        if (localId == null || localId.isEmpty() || !SUBMITTING.add(localId)) return;
        Context app = context.getApplicationContext();
        SUBMITTER.submit(() -> {
            try { submitStoredJob(app, localId); }
            finally { SUBMITTING.remove(localId); }
        });
    }

    public static void resumePending(Context context, String fallbackServer) {
        Context app = context.getApplicationContext();
        List<JobRecord> jobs = JobStore.list(app);
        List<JobRecord> local = new ArrayList<>();
        for (JobRecord job : jobs) {
            if (job.isTerminal()) continue;
            if ((job.promptId == null || job.promptId.isEmpty()) && !job.promptJson.isEmpty() &&
                    (JobRecord.LOCAL_QUEUED.equals(job.status) || JobRecord.SUBMITTING.equals(job.status))) {
                local.add(job);
            } else if (job.promptId != null && !job.promptId.isEmpty()) {
                monitorPrompt(app, serverFor(job, fallbackServer), job.localId, job.promptId);
            }
        }
        local.sort(Comparator.comparingLong(j -> j.submittedAt));
        for (JobRecord job : local) enqueueSubmission(app, job.localId);
    }

    private static void submitStoredJob(Context context, String localId) {
        JobRecord job = JobStore.find(context, localId);
        if (job == null || job.isTerminal() || job.promptJson == null || job.promptJson.isEmpty()) return;
        String server = serverFor(job, "8188");
        try {
            JobStore.markSubmitting(context, localId);
            notifyListeners(JobStore.find(context, localId));
            ComfyApiClient api = new ComfyApiClient(server);
            String promptId = api.queuePrompt(new JSONObject(job.promptJson));
            JobRecord afterSubmit = JobStore.find(context, localId);
            if (afterSubmit == null || afterSubmit.isTerminal()) {
                try { api.deleteQueuePrompt(promptId); } catch (Exception ignored) {}
                return;
            }
            JobStore.attachPrompt(context, localId, promptId);
            notifyListeners(JobStore.find(context, localId));
            startProgressSocket(context, localId, promptId, api.getClientId(), server);
            monitorPrompt(context, server, localId, promptId);
        } catch (Exception e) {
            JobRecord current = JobStore.find(context, localId);
            if (current != null && !current.isTerminal()) {
                update(context, localId, JobRecord.FAILED, "提交失败：" + safeMessage(e), 0);
            }
        }
    }

    private static void startProgressSocket(Context context, String localId, String promptId, String clientId, String server) {
        closeSocket(promptId);
        try {
            ComfyProgressSocket.Handle handle = ComfyProgressSocket.connect(server, clientId, new ComfyProgressSocket.Listener() {
                private boolean matches(String p) { return p == null || p.isEmpty() || promptId.equals(p); }

                @Override public void onProgress(String p, int value, int max, String node) {
                    if (!matches(p) || max <= 0) return;
                    int percent = 10 + Math.round((Math.max(0, Math.min(value, max)) * 82f) / max);
                    JobStore.updateProgress(context, localId, percent, node,
                            "生成中 · " + Math.max(0, value) + " / " + max);
                    notifyListeners(JobStore.find(context, localId));
                }

                @Override public void onExecuting(String p, String node) {
                    if (!matches(p)) return;
                    if (node != null && !node.isEmpty()) {
                        JobStore.markStarted(context, localId, "正在执行节点 " + node);
                        notifyListeners(JobStore.find(context, localId));
                    }
                }

                @Override public void onExecutionStart(String p) {
                    if (!matches(p)) return;
                    JobStore.markStarted(context, localId, "ComfyUI 已开始生成");
                    notifyListeners(JobStore.find(context, localId));
                }
            });
            SOCKETS.put(promptId, handle);
        } catch (Exception ignored) {}
    }

    public static void monitorPrompt(Context context, String server, String localId, String promptId) {
        if (promptId == null || promptId.isEmpty()) return;
        if (!MONITORING.add(promptId)) return;
        Context app = context.getApplicationContext();
        MONITOR_POOL.submit(() -> {
            try { monitorLoop(app, server, localId, promptId); }
            finally {
                MONITORING.remove(promptId);
                JobRecord j = JobStore.find(app, localId);
                if (j == null || j.isTerminal()) closeSocket(promptId);
            }
        });
    }

    private static void monitorLoop(Context context, String server, String localId, String promptId) {
        ComfyApiClient api = new ComfyApiClient(server);
        int completedWithoutImages = 0;
        int consecutiveNetworkErrors = 0;

        for (int tick = 0; tick < 1800; tick++) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            JobRecord current = JobStore.find(context, localId);
            if (current == null || current.isTerminal()) return;

            try {
                JSONObject history = null;
                try { history = api.getHistoryForPrompt(promptId); }
                catch (FileNotFoundException ignored) {}

                if (history != null) {
                    String err = api.historyError(history, promptId);
                    if (!err.isEmpty()) {
                        update(context, localId, JobRecord.FAILED, "ComfyUI 执行失败：" + err, 0);
                        return;
                    }

                    java.util.Set<String> allowedOutputs = current.selectedOutputNodeIds();
                    List<ImageRef> refs = api.parseImagesFromPromptHistory(history, promptId, allowedOutputs);
                    if (refs.isEmpty()) refs = api.parseImagesDeepForPrompt(history, promptId, allowedOutputs);
                    if (!refs.isEmpty()) {
                        complete(context, localId, refs);
                        return;
                    }

                    if (api.isHistoryCompleted(history, promptId)) {
                        List<ImageRef> fallbackRefs = new ArrayList<>();
                        try {
                            JSONObject all = api.getAllHistory(300);
                            fallbackRefs = api.parseImagesFromPromptHistory(all, promptId, allowedOutputs);
                            if (fallbackRefs.isEmpty()) fallbackRefs = api.parseImagesDeepForPrompt(all, promptId, allowedOutputs);
                        } catch (Exception ignored) {}
                        if (!fallbackRefs.isEmpty()) {
                            complete(context, localId, fallbackRefs);
                            return;
                        }

                        completedWithoutImages++;
                        update(context, localId, JobRecord.SYNCING,
                                "任务已完成，正在同步输出图片… " + completedWithoutImages + "/20", -1);
                        if (completedWithoutImages >= 20) {
                            JobStore.complete(context, localId, new ArrayList<>(),
                                    "ComfyUI 已完成任务，但 history 未提供可下载图片记录");
                            notifyListeners(JobStore.find(context, localId));
                            return;
                        }
                        consecutiveNetworkErrors = 0;
                        continue;
                    }
                }

                String queueState = api.getQueueState(promptId);
                if (ComfyApiClient.QUEUE_RUNNING.equals(queueState)) {
                    JobStore.markStarted(context, localId, "ComfyUI 正在生成");
                    notifyListeners(JobStore.find(context, localId));
                } else if (ComfyApiClient.QUEUE_PENDING.equals(queueState)) {
                    update(context, localId, JobRecord.QUEUED, "正在 ComfyUI 队列中等待", -1);
                } else {
                    update(context, localId, current.status, "已提交，等待服务器状态更新", -1);
                }
                consecutiveNetworkErrors = 0;
            } catch (Exception e) {
                consecutiveNetworkErrors++;
                if (consecutiveNetworkErrors >= 10) {
                    update(context, localId, JobRecord.FAILED, "连续读取 ComfyUI 状态失败：" + safeMessage(e), -1);
                    return;
                }
                update(context, localId, current.status,
                        "暂时无法读取服务器状态，正在重试（" + consecutiveNetworkErrors + "/10）", -1);
            }
        }
        update(context, localId, JobRecord.FAILED, "等待 ComfyUI 任务结果超时", -1);
    }

    private static void complete(Context context, String localId, List<ImageRef> refs) {
        JobStore.complete(context, localId, refs, "生成完成，共 " + refs.size() + " 张输出");
        notifyListeners(JobStore.find(context, localId));
    }

    public static void cancelPending(Context context, String fallbackServer, JobRecord job) {
        if (job == null) return;
        Context app = context.getApplicationContext();
        if (job.promptId == null || job.promptId.isEmpty()) {
            update(app, job.localId, JobRecord.CANCELLED, "已取消本地等待提交任务", -1);
            return;
        }
        MONITOR_POOL.submit(() -> {
            try {
                new ComfyApiClient(serverFor(job, fallbackServer)).deleteQueuePrompt(job.promptId);
                update(app, job.localId, JobRecord.CANCELLED, "已从 ComfyUI 等待队列取消", -1);
            } catch (Exception e) {
                update(app, job.localId, job.status, "取消失败：" + safeMessage(e), -1);
            }
        });
    }

    public static void interruptCurrent(Context context, String fallbackServer, JobRecord job) {
        Context app = context.getApplicationContext();
        MONITOR_POOL.submit(() -> {
            try {
                new ComfyApiClient(serverFor(job, fallbackServer)).interrupt();
                if (job != null) update(app, job.localId, JobRecord.CANCELLED, "已请求中断当前 ComfyUI 任务", -1);
            } catch (Exception e) {
                if (job != null) update(app, job.localId, job.status, "中断失败：" + safeMessage(e), -1);
            }
        });
    }

    private static void update(Context context, String localId, String status, String message, int outputCount) {
        JobRecord before = JobStore.find(context, localId);
        boolean meaningful = before == null || !safe(before.status).equals(safe(status)) || !safe(before.message).equals(safe(message)) ||
                (outputCount >= 0 && before.outputCount != outputCount);
        JobStore.updateStatus(context, localId, status, message, outputCount);
        if (meaningful) notifyListeners(JobStore.find(context, localId));
    }

    public static void notifySubmitted(Context context, String localId) { notifyListeners(JobStore.find(context, localId)); }

    private static void notifyListeners(JobRecord job) {
        if (job == null) return;
        for (JobListener listener : LISTENERS) {
            try { listener.onJobChanged(job); } catch (Exception ignored) {}
        }
    }

    private static void closeSocket(String promptId) {
        ComfyProgressSocket.Handle h = SOCKETS.remove(promptId);
        if (h != null) try { h.close(); } catch (Exception ignored) {}
    }

    private static String serverFor(JobRecord job, String fallback) {
        return job != null && job.server != null && !job.server.trim().isEmpty() ? job.server : fallback;
    }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
}
