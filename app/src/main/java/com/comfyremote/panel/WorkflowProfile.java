package com.comfyremote.panel;

import org.json.JSONObject;

import java.util.UUID;

/** Persisted reusable workflow profile for V1.8. */
public final class WorkflowProfile {
    public String id;
    public String name;
    public String promptJson;
    public String uiJson;
    public boolean favorite;
    public long createdAt;
    public long updatedAt;
    public JSONObject overrides;

    public WorkflowProfile(String id, String name, String promptJson, String uiJson,
                           boolean favorite, long createdAt, long updatedAt, JSONObject overrides) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.name = name == null || name.trim().isEmpty() ? "未命名工作流" : name.trim();
        this.promptJson = promptJson == null ? "{}" : promptJson;
        this.uiJson = uiJson;
        this.favorite = favorite;
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.updatedAt = updatedAt <= 0 ? this.createdAt : updatedAt;
        this.overrides = overrides == null ? new JSONObject() : overrides;
    }

    public static WorkflowProfile create(String name, JSONObject prompt, JSONObject uiRaw) {
        long now = System.currentTimeMillis();
        return new WorkflowProfile(
                UUID.randomUUID().toString(),
                name,
                prompt == null ? "{}" : prompt.toString(),
                uiRaw == null ? null : uiRaw.toString(),
                false,
                now,
                now,
                new JSONObject()
        );
    }

    public JSONObject promptObject() throws Exception {
        return new JSONObject(promptJson);
    }

    public JSONObject uiObjectOrNull() throws Exception {
        return uiJson == null || uiJson.trim().isEmpty() ? null : new JSONObject(uiJson);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("prompt_json", promptJson);
            if (uiJson != null) o.put("ui_json", uiJson);
            o.put("favorite", favorite);
            o.put("created_at", createdAt);
            o.put("updated_at", updatedAt);
            o.put("overrides", overrides == null ? new JSONObject() : overrides);
        } catch (Exception ignored) {}
        return o;
    }

    public static WorkflowProfile fromJson(JSONObject o) {
        JSONObject overrides = o.optJSONObject("overrides");
        return new WorkflowProfile(
                o.optString("id", ""),
                o.optString("name", "未命名工作流"),
                o.optString("prompt_json", "{}"),
                o.has("ui_json") ? o.optString("ui_json", null) : null,
                o.optBoolean("favorite", false),
                o.optLong("created_at", System.currentTimeMillis()),
                o.optLong("updated_at", System.currentTimeMillis()),
                overrides == null ? new JSONObject() : overrides
        );
    }

    public static String overrideKey(String nodeId, String inputName) {
        return (nodeId == null ? "" : nodeId) + "|" + (inputName == null ? "" : inputName);
    }
}
