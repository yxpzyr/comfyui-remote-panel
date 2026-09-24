package com.comfyremote.panel;

import org.json.JSONObject;

import java.util.UUID;

/** Lightweight logical folder metadata for V2.4 workflow organization. */
public final class WorkflowFolder {
    public String id;
    public String name;
    public long createdAt;
    public long updatedAt;

    public WorkflowFolder(String id, String name, long createdAt, long updatedAt) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.name = name == null || name.trim().isEmpty() ? "新文件夹" : name.trim();
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.updatedAt = updatedAt <= 0 ? this.createdAt : updatedAt;
    }

    public static WorkflowFolder create(String name) {
        long now = System.currentTimeMillis();
        return new WorkflowFolder(null, name, now, now);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("created_at", createdAt);
            o.put("updated_at", updatedAt);
        } catch (Exception ignored) {}
        return o;
    }

    public static WorkflowFolder fromJson(JSONObject o) {
        return new WorkflowFolder(
                o.optString("id", ""),
                o.optString("name", "新文件夹"),
                o.optLong("created_at", System.currentTimeMillis()),
                o.optLong("updated_at", System.currentTimeMillis())
        );
    }
}
