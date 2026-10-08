package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persisted reusable workflow profile for V1.8. */
public final class WorkflowProfile {
    public String id;
    public String name;
    public String promptJson;
    public String uiJson;
    public boolean favorite;
    // V2.4: logical workflow-folder UUID; empty means system "未分类".
    public String folderId = "";
    public long createdAt;
    public long updatedAt;
    public JSONObject overrides;
    public String outputNodesJson = "[]";
    public String selectedOutputNodeIdsJson = "[]";
    public boolean outputNodesVerified = false;
    // V2.1: persist each workflow's chosen local input URIs and replace switches.
    public JSONObject inputUris = new JSONObject();
    public JSONObject inputReplace = new JSONObject();
    // V2.2: optional per-input RGBA file containing a painted alpha mask.
    public JSONObject inputMaskPaths = new JSONObject();
    // V2.6.2: optional source-image branches that are bypassed in the original UI.
    // Stored per subworkflow; false means preserve the original bypass semantics.
    public JSONObject enabledAuxImages = new JSONObject();
    // V2.6: One original UI workflow, multiple independently persisted execution views.
    public JSONArray variants = new JSONArray();
    public String activeVariantId = "";
    public JSONObject variantStates = new JSONObject();


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


    public List<WorkflowUtils.OutputChoice> outputChoices() {
        List<WorkflowUtils.OutputChoice> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(outputNodesJson == null || outputNodesJson.isEmpty() ? "[]" : outputNodesJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(WorkflowUtils.OutputChoice.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public Set<String> selectedOutputNodeIds() {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(selectedOutputNodeIdsJson == null || selectedOutputNodeIdsJson.isEmpty() ? "[]" : selectedOutputNodeIdsJson);
            for (int i = 0; i < a.length(); i++) {
                String id = a.optString(i, "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void setOutputChoices(List<WorkflowUtils.OutputChoice> choices, boolean verified, boolean preserveSelection) {
        JSONArray nodes = new JSONArray();
        Set<String> valid = new LinkedHashSet<>();
        if (choices != null) for (WorkflowUtils.OutputChoice c : choices) { nodes.put(c.toJson()); valid.add(c.id); }
        Set<String> chosen = preserveSelection ? selectedOutputNodeIds() : new LinkedHashSet<>();
        chosen.retainAll(valid);
        if (chosen.isEmpty()) chosen.addAll(valid);
        outputNodesJson = nodes.toString();
        setSelectedOutputNodeIds(chosen);
        outputNodesVerified = verified;
    }

    public void setSelectedOutputNodeIds(Set<String> ids) {
        JSONArray a = new JSONArray();
        if (ids != null) for (String id : ids) if (id != null && !id.isEmpty()) a.put(id);
        selectedOutputNodeIdsJson = a.toString();
    }


    public String savedInputUri(String key) {
        if (key == null || key.isEmpty()) return "";
        return inputUris == null ? "" : inputUris.optString(key, "");
    }

    public void setSavedInputUri(String key, String uri) {
        if (key == null || key.isEmpty()) return;
        if (inputUris == null) inputUris = new JSONObject();
        try {
            if (uri == null || uri.isEmpty()) inputUris.remove(key);
            else inputUris.put(key, uri);
        } catch (Exception ignored) {}
    }

    public boolean savedReplaceEnabled(String key, boolean fallback) {
        if (key == null || key.isEmpty() || inputReplace == null || !inputReplace.has(key)) return fallback;
        return inputReplace.optBoolean(key, fallback);
    }

    public void setSavedReplaceEnabled(String key, boolean enabled) {
        if (key == null || key.isEmpty()) return;
        if (inputReplace == null) inputReplace = new JSONObject();
        try { inputReplace.put(key, enabled); } catch (Exception ignored) {}
    }


    public String savedMaskPath(String key) {
        if (key == null || key.isEmpty()) return "";
        return inputMaskPaths == null ? "" : inputMaskPaths.optString(key, "");
    }

    public void setSavedMaskPath(String key, String path) {
        if (key == null || key.isEmpty()) return;
        if (inputMaskPaths == null) inputMaskPaths = new JSONObject();
        try {
            if (path == null || path.isEmpty()) inputMaskPaths.remove(key);
            else inputMaskPaths.put(key, path);
        } catch (Exception ignored) {}
    }

    public boolean isCollection() { return variants != null && variants.length() >= 2; }

    public void initializeCollection(JSONArray detected, String initial) {
        variants = detected == null ? new JSONArray() : detected;
        activeVariantId = initial == null ? "" : initial;
        variantStates = new JSONObject();
    }

    private JSONObject captureVariantState() {
        JSONObject state = new JSONObject();
        try {
            state.put("prompt_json", promptJson);
            state.put("overrides", new JSONObject(overrides.toString()));
            state.put("input_uris", new JSONObject(inputUris.toString()));
            state.put("input_replace", new JSONObject(inputReplace.toString()));
            state.put("input_mask_paths", new JSONObject(inputMaskPaths.toString()));
            state.put("enabled_aux_images", new JSONObject(enabledAuxImages.toString()));
            state.put("output_nodes", outputNodesJson);
            state.put("selected_outputs", selectedOutputNodeIdsJson);
            state.put("outputs_verified", outputNodesVerified);
        } catch (Exception ignored) {}
        return state;
    }

    public void rememberActiveVariant() {
        if (!isCollection() || activeVariantId == null || activeVariantId.isEmpty()) return;
        try { variantStates.put(activeVariantId, captureVariantState()); } catch (Exception ignored) {}
    }

    public void activateVariant(String selectedId, JSONObject compiledPrompt) throws Exception {
        if (!isCollection()) throw new Exception("当前工作流不是合集");
        boolean valid = false;
        for (int i = 0; i < variants.length(); i++) {
            JSONObject v = variants.optJSONObject(i);
            if (v != null && selectedId.equals(v.optString("id", ""))) valid = true;
        }
        if (!valid) throw new Exception("未知子功能：" + selectedId);
        rememberActiveVariant();
        JSONObject saved = variantStates.optJSONObject(selectedId);
        activeVariantId = selectedId;
        if (saved == null) {
            promptJson = compiledPrompt.toString();
            overrides = new JSONObject();
            inputUris = new JSONObject();
            inputReplace = new JSONObject();
            inputMaskPaths = new JSONObject();
            enabledAuxImages = new JSONObject();
            outputNodesJson = "[]";
            selectedOutputNodeIdsJson = "[]";
            outputNodesVerified = false;
        } else {
            promptJson = saved.optString("prompt_json", compiledPrompt.toString());
            overrides = new JSONObject(saved.optJSONObject("overrides") == null ? "{}" : saved.getJSONObject("overrides").toString());
            inputUris = new JSONObject(saved.optJSONObject("input_uris") == null ? "{}" : saved.getJSONObject("input_uris").toString());
            inputReplace = new JSONObject(saved.optJSONObject("input_replace") == null ? "{}" : saved.getJSONObject("input_replace").toString());
            inputMaskPaths = new JSONObject(saved.optJSONObject("input_mask_paths") == null ? "{}" : saved.getJSONObject("input_mask_paths").toString());
            enabledAuxImages = new JSONObject(saved.optJSONObject("enabled_aux_images") == null ? "{}" : saved.getJSONObject("enabled_aux_images").toString());
            outputNodesJson = saved.optString("output_nodes", "[]");
            selectedOutputNodeIdsJson = saved.optString("selected_outputs", "[]");
            outputNodesVerified = saved.optBoolean("outputs_verified", false);
        }
    }

    public boolean isAuxImageEnabled(String nodeId) {
        return enabledAuxImages != null && enabledAuxImages.optBoolean(nodeId, false);
    }

    public void setAuxImageEnabled(String nodeId, boolean enabled) {
        if (nodeId == null || nodeId.isEmpty()) return;
        if (enabledAuxImages == null) enabledAuxImages = new JSONObject();
        if (enabled) { try { enabledAuxImages.put(nodeId, true); } catch (Exception ignored) {} }
        else enabledAuxImages.remove(nodeId);
    }

    public JSONObject promptObject() throws Exception {
        return new JSONObject(promptJson);
    }

    public JSONObject uiObjectOrNull() throws Exception {
        return uiJson == null || uiJson.trim().isEmpty() ? null : new JSONObject(uiJson);
    }

    public JSONObject toJson() {
        rememberActiveVariant();
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("prompt_json", promptJson);
            if (uiJson != null) o.put("ui_json", uiJson);
            o.put("favorite", favorite);
            o.put("folder_id", folderId == null ? "" : folderId);
            o.put("variants", variants);
            o.put("active_variant_id", activeVariantId);
            o.put("variant_states", variantStates);
            o.put("created_at", createdAt);
            o.put("updated_at", updatedAt);
            o.put("overrides", overrides == null ? new JSONObject() : overrides);
            o.put("output_nodes", new JSONArray(outputNodesJson == null || outputNodesJson.isEmpty() ? "[]" : outputNodesJson));
            o.put("selected_output_node_ids", new JSONArray(selectedOutputNodeIdsJson == null || selectedOutputNodeIdsJson.isEmpty() ? "[]" : selectedOutputNodeIdsJson));
            o.put("output_nodes_verified", outputNodesVerified);
            o.put("input_uris", inputUris == null ? new JSONObject() : inputUris);
            o.put("input_replace", inputReplace == null ? new JSONObject() : inputReplace);
            o.put("input_mask_paths", inputMaskPaths == null ? new JSONObject() : inputMaskPaths);
            o.put("enabled_aux_images", enabledAuxImages == null ? new JSONObject() : enabledAuxImages);
        } catch (Exception ignored) {}
        return o;
    }

    public static WorkflowProfile fromJson(JSONObject o) {
        JSONObject overrides = o.optJSONObject("overrides");
        WorkflowProfile p = new WorkflowProfile(
                o.optString("id", ""),
                o.optString("name", "未命名工作流"),
                o.optString("prompt_json", "{}"),
                o.has("ui_json") ? o.optString("ui_json", null) : null,
                o.optBoolean("favorite", false),
                o.optLong("created_at", System.currentTimeMillis()),
                o.optLong("updated_at", System.currentTimeMillis()),
                overrides == null ? new JSONObject() : overrides
        );
        p.folderId = o.optString("folder_id", "");
        p.variants = o.optJSONArray("variants") == null ? new JSONArray() : o.optJSONArray("variants");
        p.activeVariantId = o.optString("active_variant_id", "");
        p.variantStates = o.optJSONObject("variant_states") == null ? new JSONObject() : o.optJSONObject("variant_states");
        JSONArray outputNodes = o.optJSONArray("output_nodes");
        JSONArray selected = o.optJSONArray("selected_output_node_ids");
        p.outputNodesJson = outputNodes == null ? o.optString("output_nodes_json", "[]") : outputNodes.toString();
        p.selectedOutputNodeIdsJson = selected == null ? o.optString("selected_output_node_ids_json", "[]") : selected.toString();
        p.outputNodesVerified = o.optBoolean("output_nodes_verified", false);
        JSONObject inputUris = o.optJSONObject("input_uris");
        JSONObject inputReplace = o.optJSONObject("input_replace");
        JSONObject inputMaskPaths = o.optJSONObject("input_mask_paths");
        p.inputUris = inputUris == null ? new JSONObject() : inputUris;
        p.inputReplace = inputReplace == null ? new JSONObject() : inputReplace;
        p.inputMaskPaths = inputMaskPaths == null ? new JSONObject() : inputMaskPaths;
        JSONObject enabledAux = o.optJSONObject("enabled_aux_images");
        p.enabledAuxImages = enabledAux == null ? new JSONObject() : enabledAux;
        return p;
    }

    public static String overrideKey(String nodeId, String inputName) {
        return (nodeId == null ? "" : nodeId) + "|" + (inputName == null ? "" : inputName);
    }
}
