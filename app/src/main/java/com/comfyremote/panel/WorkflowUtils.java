package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class WorkflowUtils {
    private WorkflowUtils() {}

    public static JSONObject extractPromptObject(JSONObject raw) throws JSONException {
        if (raw.has("prompt") && raw.opt("prompt") instanceof JSONObject) {
            return new JSONObject(raw.getJSONObject("prompt").toString());
        }
        if (raw.has("nodes") && raw.opt("nodes") instanceof JSONArray) {
            throw new JSONException("检测到普通 UI 工作流，请使用自动转换流程读取。");
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
        if (!hasApiNode) throw new JSONException("没有检测到可执行的 ComfyUI 节点（class_type）。");
        return new JSONObject(raw.toString());
    }

    /** Finds file-backed image loader inputs. Supports standard LoadImage and many custom loaders. */
    public static List<NodeChoice> findLoadImageNodes(JSONObject prompt) {
        List<NodeChoice> result = new ArrayList<>();
        Iterator<String> keys = prompt.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            String classType = node.optString("class_type", "");
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) continue;

            String inputName = findImageFilenameInput(classType, inputs);
            if (inputName == null) continue;
            result.add(new NodeChoice(id, nodeTitle(node, classType), inputName));
        }
        return result;
    }

    private static String findImageFilenameInput(String classType, JSONObject inputs) {
        String lowerType = classType == null ? "" : classType.toLowerCase(Locale.ROOT);
        String[] preferred = new String[]{"image", "image_path", "filename", "file", "path"};
        for (String name : preferred) {
            Object v = inputs.opt(name);
            if (!(v instanceof String)) continue;
            String n = name.toLowerCase(Locale.ROOT);
            if (n.equals("image") || lowerType.contains("load") || lowerType.contains("input") || lowerType.contains("image")) {
                return name;
            }
        }
        Iterator<String> it = inputs.keys();
        while (it.hasNext()) {
            String name = it.next();
            Object v = inputs.opt(name);
            if (!(v instanceof String)) continue;
            String n = name.toLowerCase(Locale.ROOT);
            if ((n.contains("image") || n.contains("file")) && (lowerType.contains("load") || lowerType.contains("input"))) return name;
        }
        return null;
    }

    /** Finds user-facing prompt/switch-like scalar inputs from an API prompt. */
    public static List<FieldChoice> findEditableFields(JSONObject prompt) {
        List<FieldChoice> out = new ArrayList<>();
        Iterator<String> keys = prompt.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            String classType = node.optString("class_type", "");
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) continue;
            String title = nodeTitle(node, classType);

            Iterator<String> names = inputs.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object value = inputs.opt(name);
                if (value == null || value == JSONObject.NULL || value instanceof JSONArray || value instanceof JSONObject) continue;
                String n = name.toLowerCase(Locale.ROOT);

                if (value instanceof Boolean) {
                    out.add(new FieldChoice(id, name, title, FieldChoice.BOOL, value));
                } else if (value instanceof String && isPromptLike(n, classType, title)) {
                    out.add(new FieldChoice(id, name, title, FieldChoice.TEXT, value));
                } else if ((value instanceof Number || value instanceof String) && isSwitchLike(n)) {
                    out.add(new FieldChoice(id, name, title, FieldChoice.CONTROL, value));
                }
            }
        }
        return out;
    }

    private static boolean isPromptLike(String name, String classType, String title) {
        String c = (classType + " " + title).toLowerCase(Locale.ROOT);
        if (name.equals("text") || name.contains("prompt") || name.contains("instruction") || name.contains("caption")) return true;
        if (name.equals("positive") || name.equals("negative") || name.equals("system_prompt") || name.equals("user_prompt")) return true;
        return name.equals("string") && (c.contains("text") || c.contains("prompt") || c.contains("clip"));
    }

    private static boolean isSwitchLike(String name) {
        return name.equals("mode") || name.equals("method") || name.equals("switch") ||
                name.startsWith("use_") || name.startsWith("enable_") || name.startsWith("enabled_") ||
                name.contains("_enable") || name.contains("_enabled") || name.contains("switch") ||
                name.contains("toggle") || name.contains("bypass");
    }

    public static JSONObject setInputValue(JSONObject prompt, String nodeId, String inputName, Object value) throws JSONException {
        JSONObject node = prompt.optJSONObject(nodeId);
        if (node == null) throw new JSONException("找不到节点：" + nodeId);
        JSONObject inputs = node.optJSONObject("inputs");
        if (inputs == null) throw new JSONException("节点缺少 inputs：" + nodeId);
        inputs.put(inputName, value);
        return prompt;
    }

    public static JSONObject withImageFilename(JSONObject original, String nodeId, String inputName, String filename) throws JSONException {
        JSONObject prompt = new JSONObject(original.toString());
        setInputValue(prompt, nodeId, inputName, filename);
        return prompt;
    }

    private static String nodeTitle(JSONObject node, String fallback) {
        JSONObject meta = node.optJSONObject("_meta");
        if (meta != null) {
            String t = meta.optString("title", "");
            if (!t.isEmpty()) return t;
        }
        return fallback == null || fallback.isEmpty() ? "节点" : fallback;
    }

    public static class NodeChoice {
        public final String id;
        public final String title;
        public final String inputName;
        public NodeChoice(String id, String title, String inputName) {
            this.id = id;
            this.title = title;
            this.inputName = inputName;
        }
        @Override public String toString() { return title + " · 节点 " + id; }
    }

    public static class FieldChoice {
        public static final int TEXT = 1;
        public static final int BOOL = 2;
        public static final int CONTROL = 3;
        public final String nodeId, inputName, nodeTitle;
        public final int kind;
        public final Object value;
        public FieldChoice(String nodeId, String inputName, String nodeTitle, int kind, Object value) {
            this.nodeId = nodeId;
            this.inputName = inputName;
            this.nodeTitle = nodeTitle;
            this.kind = kind;
            this.value = value;
        }
        public String label() { return nodeTitle + " · " + inputName + " · 节点 " + nodeId; }
    }
}
