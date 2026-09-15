package com.comfyremote.panel;

import android.content.Context;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks many ComfyUI prompt IDs at once. ComfyUI remains the source of truth for
 * GPU scheduling; this manager only submits/monitors jobs and persists status.
 */
public final class GenerationManager {
    public interface JobListener { void onJobChanged(JobRecord job); }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Set<String> MONITORING = ConcurrentHashMap.newKeySet();
    private static final Set<JobListener> LISTENERS = new CopyOnWriteArraySet<>();

    private GenerationManager() {}

    public static void addListener(JobListener l) { if (l != null) LISTENERS.add(l); }
    public static void removeListener(JobListener l) { if (l != null) LISTENERS.remove(l); }

    public static void resumePending(Context context, String server) {
        Context app = context.getApplicationContext();
        for (JobRecord job : JobStore.list(app)) {
            if (!job.isTerminal() && job.promptId != null && !job.promptId.isEmpty()) {
                monitorPrompt(app, server, job.localId, job.promptId);
            }
        }
    }

    public static void monitorPrompt(Context context, String server, String localId, String promptId) {
        if (promptId == null || promptId.isEmpty()) return;
        if (!MONITORING.add(promptId)) return;
        Context app = context.getApplicationContext();
        POOL.submit(() -> {
            try {
                monitorLoop(app, server, localId, promptId);
            } finally {
                MONITORING.remove(promptId);
            }
        });
    }

    private static void monitorLoop(Context context, String server, String localId, String promptId) {
        ComfyApiClient api = new ComfyApiClient(server);
        int completedWithoutImages = 0;
        int consecutiveNetworkErrors = 0;

        for (int tick = 0; tick < 1800; tick++) { // up to about one hour
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

                    List<ImageRef> refs = api.parseImagesFromPromptHistory(history, promptId);
                    if (refs.isEmpty()) refs = api.parseImagesDeepForPrompt(history, promptId);
                    if (!refs.isEmpty()) {
                        update(context, localId, JobRecord.COMPLETED, "生成完成，共 " + refs.size() + " 张输出", refs.size());
                        return;
                    }

                    if (api.isHistoryCompleted(history, promptId)) {
                        List<ImageRef> fallbackRefs = new java.util.ArrayList<>();
                        try {
                            JSONObject all = api.getAllHistory(160);
                            fallbackRefs = api.parseImagesFromPromptHistory(all, promptId);
                            if (fallbackRefs.isEmpty()) fallbackRefs = api.parseImagesDeepForPrompt(all, promptId);
                        } catch (Exception ignored) {}

                        if (!fallbackRefs.isEmpty()) {
                            update(context, localId, JobRecord.COMPLETED,
                                    "生成完成，共 " + fallbackRefs.size() + " 张输出", fallbackRefs.size());
                            return;
                        }

                        completedWithoutImages++;
                        update(context, localId, JobRecord.SYNCING,
                                "任务已完成，正在同步输出图片… " + completedWithoutImages + "/15", -1);
                        if (completedWithoutImages >= 15) {
                            // Do not falsely mark the generation itself as failed. Some custom output
                            // nodes never expose downloadable image metadata even though execution succeeds.
                            update(context, localId, JobRecord.COMPLETED,
                                    "ComfyUI 已完成任务，但未发现可下载的图片记录", 0);
                            return;
                        }
                        consecutiveNetworkErrors = 0;
                        continue;
                    }
                }

                String queueState = api.getQueueState(promptId);
                if (ComfyApiClient.QUEUE_RUNNING.equals(queueState)) {
                    update(context, localId, JobRecord.RUNNING, "ComfyUI 正在生成", -1);
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

    public static void cancelPending(Context context, String server, JobRecord job) {
        if (job == null || job.promptId == null || job.promptId.isEmpty()) return;
        Context app = context.getApplicationContext();
        POOL.submit(() -> {
            try {
                new ComfyApiClient(server).deleteQueuePrompt(job.promptId);
                update(app, job.localId, JobRecord.CANCELLED, "已从 ComfyUI 等待队列取消", -1);
            } catch (Exception e) {
                update(app, job.localId, job.status, "取消失败：" + safeMessage(e), -1);
            }
        });
    }

    public static void interruptCurrent(Context context, String server, JobRecord job) {
        Context app = context.getApplicationContext();
        POOL.submit(() -> {
            try {
                new ComfyApiClient(server).interrupt();
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

    public static void notifySubmitted(Context context, String localId) {
        notifyListeners(JobStore.find(context, localId));
    }

    private static void notifyListeners(JobRecord job) {
        if (job == null) return;
        for (JobListener listener : LISTENERS) {
            try { listener.onJobChanged(job); } catch (Exception ignored) {}
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
