package com.comfyremote.panel;

import org.json.JSONObject;

import java.util.UUID;

public final class JobRecord {
    public static final String UPLOADING = "UPLOADING";
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
    public String status;
    public String message;
    public long submittedAt;
    public long updatedAt;
    public int outputCount;

    public JobRecord() {}

    public static JobRecord create(String workflowId, String workflowName, String inputSummary) {
        JobRecord j = new JobRecord();
        j.localId = UUID.randomUUID().toString();
        j.promptId = "";
        j.workflowId = workflowId == null ? "" : workflowId;
        j.workflowName = workflowName == null ? "工作流" : workflowName;
        j.inputSummary = inputSummary == null ? "" : inputSummary;
        j.status = UPLOADING;
        j.message = "正在上传输入并提交任务";
        j.submittedAt = System.currentTimeMillis();
        j.updatedAt = j.submittedAt;
        return j;
    }

    public boolean isTerminal() {
        return COMPLETED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("local_id", localId);
            o.put("prompt_id", promptId);
            o.put("workflow_id", workflowId);
            o.put("workflow_name", workflowName);
            o.put("input_summary", inputSummary);
            o.put("status", status);
            o.put("message", message);
            o.put("submitted_at", submittedAt);
            o.put("updated_at", updatedAt);
            o.put("output_count", outputCount);
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
        j.status = o.optString("status", FAILED);
        j.message = o.optString("message", "");
        j.submittedAt = o.optLong("submitted_at", System.currentTimeMillis());
        j.updatedAt = o.optLong("updated_at", j.submittedAt);
        j.outputCount = o.optInt("output_count", 0);
        return j;
    }
}
