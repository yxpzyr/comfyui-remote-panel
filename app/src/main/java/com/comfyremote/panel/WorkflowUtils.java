package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class WorkflowUtils {
    private WorkflowUtils() {}

    public static JSONObject extractPromptObject(JSONObject raw) throws JSONException {
        // Accept a direct API workflow or an object that wraps it as {"prompt": {...}}.
        if (raw.has("prompt") && raw.opt("prompt") instanceof JSONObject) {
            return new JSONObject(raw.getJSONObject("prompt").toString());
        }
        if (raw.has("nodes") && raw.opt("nodes") instanceof JSONArray) {
            throw new JSONException("检测到普通 UI 工作流（顶层 nodes 数组），请在 ComfyUI 中导出 API 格式 JSON。\n通常使用 File / Save(Export) API Format。 ");
        }

        boolean hasApiNode = false;
        Iterator<String> keys = raw.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = raw.opt(key);
            if (v instanceof JSONObject && ((JSONObject) v).has("class_type")) {
                hasApiNode = true;
                break;
            }
        }
        if (!hasApiNode) {
            throw new JSONException("没有检测到 API 工作流节点（class_type）。请导出 ComfyUI API 格式 JSON。 ");
        }
        return new JSONObject(raw.toString());
    }

    public static List<NodeChoice> findLoadImageNodes(JSONObject prompt) {
        List<NodeChoice> result = new ArrayList<>();
        Iterator<String> keys = prompt.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            String classType = node.optString("class_type", "");
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null || !inputs.has("image")) continue;

            Object imageValue = inputs.opt("image");
            String lower = classType.toLowerCase();
            boolean looksLikeFileLoader = imageValue instanceof String &&
                    (lower.contains("load") || lower.contains("image") || lower.contains("input"));
            if (lower.contains("loadimage") || lower.contains("load_image") || lower.equals("image loader") || looksLikeFileLoader) {
                String title = "";
                JSONObject meta = node.optJSONObject("_meta");
                if (meta != null) title = meta.optString("title", "");
                if (title.isEmpty()) title = classType;
                result.add(new NodeChoice(id, title));
            }
        }
        return result;
    }

    public static JSONObject withImageFilename(JSONObject original, String nodeId, String filename) throws JSONException {
        JSONObject prompt = new JSONObject(original.toString());
        JSONObject node = prompt.optJSONObject(nodeId);
        if (node == null) throw new JSONException("找不到图像输入节点：" + nodeId);
        JSONObject inputs = node.optJSONObject("inputs");
        if (inputs == null) throw new JSONException("图像输入节点缺少 inputs：" + nodeId);
        inputs.put("image", filename);
        return prompt;
    }

    public static class NodeChoice {
        public final String id;
        public final String title;
        public NodeChoice(String id, String title) {
            this.id = id;
            this.title = title;
        }
        @Override public String toString() {
            return title + "  ·  节点 " + id;
        }
    }
}
