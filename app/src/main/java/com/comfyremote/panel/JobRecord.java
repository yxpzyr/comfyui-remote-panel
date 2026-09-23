package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class JobRecord {
    public static final String LOCAL_QUEUED = "LOCAL_QUEUED";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String UPLOADING = "UPLOADING"; // kept for migration / older records
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SYNCING = "SYNCING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public String localId;
    public String promptId;
    public String workflowId;
    public String workflowName;
    public String inputSummary;
    public String inputPreviewUri;
    public String server;
    public String promptJson;
    public String status;
    public String message;
    public long submittedAt;
    public long promptSubmittedAt;
    public long startedAt;
    public long completedAt;
    public long updatedAt;
    public int outputCount;
    public int progressPercent;
    public String currentNode;
    public String outputRefsJson;
    public String selectedOutputNodeIdsJson;
    public String outputSelectionSummary;

    public JobRecord() {}

    public static JobRecord create(String workflowId, String workflowName, String inputSummary,
                                   String inputPreviewUri, String server, String promptJson) {
        JobRecord j = new JobRecord();
        j.localId = UUID.randomUUID().toString();
        j.promptId = "";
        j.workflowId = workflowId == null ? "" : workflowId;
        j.workflowName = workflowName == null ? "工作流" : workflowName;
        j.inputSummary = inputSummary == null ? "" : inputSummary;
        j.inputPreviewUri = inputPreviewUri == null ? "" : inputPreviewUri;
        j.server = server == null ? "8188" : server;
        j.promptJson = promptJson == null ? "" : promptJson;
        j.status = LOCAL_QUEUED;
        j.message = "已加入本地提交队列";
        j.submittedAt = System.currentTimeMillis();
        j.updatedAt = j.submittedAt;
        j.progressPercent = 0;
        j.currentNode = "";
        j.outputRefsJson = "[]";
        j.selectedOutputNodeIdsJson = "[]";
        j.outputSelectionSummary = "";
        return j;
    }


    public Set<String> selectedOutputNodeIds() {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(selectedOutputNodeIdsJson == null || selectedOutputNodeIdsJson.isEmpty() ? "[]" : selectedOutputNodeIdsJson);
            for (int i = 0; i < a.length(); i++) { String id = a.optString(i, ""); if (!id.isEmpty()) out.add(id); }
        } catch (Exception ignored) {}
        return out;
    }

    public boolean isTerminal() {
        return COMPLETED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    public List<ImageRef> outputRefs() {
        List<ImageRef> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(outputRefsJson == null || outputRefsJson.isEmpty() ? "[]" : outputRefsJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    long ts = o.optLong("timestamp", 0L);
                    if (ts <= 0) ts = completedAt > 0 ? completedAt : updatedAt;
                    out.add(ImageRef.fromJson(o, ts, o.optString("prompt_id", promptId)));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public ImageRef finalOutputRef() {
        return ImageRef.chooseFinal(outputRefs());
    }

    public long totalDurationMs() {
        long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
        return submittedAt > 0 ? Math.max(0, end - submittedAt) : 0;
    }

    public long generationDurationMs() {
        long start = startedAt > 0 ? startedAt : promptSubmittedAt;
        long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
        return start > 0 ? Math.max(0, end - start) : 0;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("local_id", localId);
            o.put("prompt_id", promptId);
            o.put("workflow_id", workflowId);
            o.put("workflow_name", workflowName);
            o.put("input_summary", inputSummary);
            o.put("input_preview_uri", inputPreviewUri);
            o.put("server", server);
            o.put("prompt_json", promptJson);
            o.put("status", status);
            o.put("message", message);
            o.put("submitted_at", submittedAt);
            o.put("prompt_submitted_at", promptSubmittedAt);
            o.put("started_at", startedAt);
            o.put("completed_at", completedAt);
            o.put("updated_at", updatedAt);
            o.put("output_count", outputCount);
            o.put("progress_percent", progressPercent);
            o.put("current_node", currentNode);
            o.put("output_refs_json", outputRefsJson);
            o.put("selected_output_node_ids_json", selectedOutputNodeIdsJson);
            o.put("output_selection_summary", outputSelectionSummary);
        } catch (Exception ignored) {}
        return o;
    }

    public static JobRecord fromJson(JSONObject o) {
        JobRecord j = new JobRecord();
        j.localId = o.optString("local_id", UUID.randomUUID().toString());
        j.promptId = o.optString("prompt_id", "");
        j.workflowId = o.optString("workflow_id", "");
        j.workflowName = o.optString("workflow_name", "工作流");
        j.inputSummary = o.optString("input_summary", "");
        j.inputPreviewUri = o.optString("input_preview_uri", "");
        j.server = o.optString("server", "8188");
        j.promptJson = o.optString("prompt_json", "");
        j.status = o.optString("status", FAILED);
        j.message = o.optString("message", "");
        j.submittedAt = o.optLong("submitted_at", System.currentTimeMillis());
        j.promptSubmittedAt = o.optLong("prompt_submitted_at", 0L);
        j.startedAt = o.optLong("started_at", 0L);
        j.completedAt = o.optLong("completed_at", 0L);
        j.updatedAt = o.optLong("updated_at", j.submittedAt);
        j.outputCount = o.optInt("output_count", 0);
        j.progressPercent = o.optInt("progress_percent", COMPLETED.equals(j.status) ? 100 : 0);
        j.currentNode = o.optString("current_node", "");
        j.outputRefsJson = o.optString("output_refs_json", "[]");
        j.selectedOutputNodeIdsJson = o.optString("selected_output_node_ids_json", "[]");
        j.outputSelectionSummary = o.optString("output_selection_summary", "");
        // V1.8 migration: an interrupted upload without a prompt becomes locally queued only
        // when enough payload exists to safely resume; otherwise retain the old failure semantics.
        if (UPLOADING.equals(j.status) && j.promptId.isEmpty()) {
            if (!j.promptJson.isEmpty()) {
                j.status = LOCAL_QUEUED;
                j.message = "等待恢复提交";
            } else {
                j.status = FAILED;
                j.message = "V1.8 未完成提交缺少可恢复快照，请重新提交";
                if (j.completedAt <= 0) j.completedAt = j.updatedAt;
            }
        }
        return j;
    }
}
