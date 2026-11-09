package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Restores SaveVideo's actual user-selected container format when workflows are
 * converted from UI graphs, reopened from per-variant snapshots, or submitted.
 *
 * This intentionally only applies to the native class_type SaveVideo: arbitrary
 * custom video sinks may use a different input contract.
 */
public final class SaveVideoFormatGuard {
    private SaveVideoFormatGuard() {}

    public static boolean hasSaveVideo(JSONObject prompt) {
        if (prompt == null) return false;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            JSONObject node = prompt.optJSONObject(ids.next());
            if (node != null && "SaveVideo".equals(node.optString("class_type", "")))
                return true;
        }
        return false;
    }

    /**
     * UI nodes have their widget values in display-order. Reconstruct SaveVideo
     * format independently of object_info's dynamic widget annotations. Some
     * server revisions mark the format field differently, so the regular widget
     * mapper may skip it entirely.
     */
    public static int repairConvertedBranch(JSONObject uiGraph, JSONObject prompt,
                                            JSONObject objectInfo) throws JSONException {
        if (uiGraph == null || prompt == null || !hasSaveVideo(prompt)) return 0;
        JSONObject sourceNodes = new JSONObject();
        JSONArray nodes = uiGraph.optJSONArray("nodes");
        if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null) sourceNodes.put(String.valueOf(node.opt("id")), node);
        }
        int repaired = 0;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject target = prompt.optJSONObject(id);
            if (target == null || !"SaveVideo".equals(target.optString("class_type", "")))
                continue;
            JSONObject targetInputs = target.optJSONObject("inputs");
            if (targetInputs == null) continue;
            if (targetInputs.has("format") && !targetInputs.isNull("format")) continue;
            JSONObject source = sourceNodes.optJSONObject(id);
            Object originalFormat = uiWidgetValue(source, "format");
            Object normalized = canonicalize(id, originalFormat, objectInfo, true);
            targetInputs.put("format", normalized);
            repaired++;
        }
        return repaired;
    }

    /** Run before UI-converter required-input checks so missing format cannot escape. */
    public static void repairUiNode(JSONObject uiNode, JSONObject apiInputs, JSONObject nodeSchema)
            throws JSONException {
        if (uiNode == null || apiInputs == null ||
                !"SaveVideo".equals(uiNode.optString("type", ""))) return;
        if (apiInputs.has("format") && !apiInputs.isNull("format")) return;
        String nodeId = String.valueOf(uiNode.opt("id"));
        JSONObject info = new JSONObject().put("SaveVideo", nodeSchema);
        Object original = uiWidgetValue(uiNode, "format");
        apiInputs.put("format", canonicalize(nodeId, original, info, true));
    }

    /**
     * Final submission guard. Repairs old per-subworkflow API snapshots without
     * rebuilding their entire graph or modifying their image/seed/codec settings.
     *
     * No blind default: a value must come from the server's declared default,
     * its explicit 'auto' option, or an unambiguous single option.
     */
    public static int ensureSubmissionFormats(JSONObject prompt, JSONObject objectInfo)
            throws JSONException {
        if (prompt == null || !hasSaveVideo(prompt)) return 0;
        int repaired = 0;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null || !"SaveVideo".equals(node.optString("class_type", "")))
                continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) throw new JSONException("SaveVideo 节点 " + id + " 缺少 inputs");
            Object original = inputs.opt("format");
            Object normalized = canonicalize(id, original, objectInfo, true);
            if (original == null || original == JSONObject.NULL || !equalsValue(original, normalized)) {
                inputs.put("format", normalized);
                repaired++;
            }
            if (!inputs.has("format") || inputs.isNull("format"))
                throw new JSONException("SaveVideo 节点 " + id + " 仍然缺少 format");
        }
        return repaired;
    }

    static Object uiWidgetValue(JSONObject node, String field) {
        if (node == null) return null;
        Object w = node.opt("widgets_values");
        if (w instanceof JSONObject) return ((JSONObject) w).opt(field);
        if (!(w instanceof JSONArray)) return null;
        JSONArray values = (JSONArray) w;
        JSONArray inputs = node.optJSONArray("inputs");
        if (inputs == null) return null;
        int widgetIndex = 0;
        for (int i = 0; i < inputs.length(); i++) {
            JSONObject input = inputs.optJSONObject(i);
            if (input == null || !input.has("widget")) continue;
            if (field.equals(input.optString("name", ""))) {
                return widgetIndex < values.length() ? values.opt(widgetIndex) : null;
            }
            widgetIndex++;
        }
        return null;
    }

    private static Object canonicalize(String id, Object original, JSONObject objectInfo,
                                       boolean requireValidSchema) throws JSONException {
        JSONObject saveVideo = objectInfo == null ? null : objectInfo.optJSONObject("SaveVideo");
        JSONObject input = saveVideo == null ? null : saveVideo.optJSONObject("input");
        JSONArray specification = null;
        if (input != null) {
            JSONObject required = input.optJSONObject("required");
            JSONObject optional = input.optJSONObject("optional");
            if (required != null) specification = required.optJSONArray("format");
            if (specification == null && optional != null) specification = optional.optJSONArray("format");
        }
        if (specification == null && requireValidSchema)
            throw new JSONException("SaveVideo 节点 " + id +
                    " 在当前 ComfyUI /object_info 中找不到 format 定义。请刷新节点定义或检查 ComfyUI 版本。");

        JSONArray options = null;
        Object first = specification == null ? null : specification.opt(0);
        JSONObject metadata = specification == null ? null : specification.optJSONObject(1);
        if (first instanceof JSONArray) options = (JSONArray) first;
        else if (metadata != null) options = metadata.optJSONArray("options");

        Object candidate = original;
        if (candidate == JSONObject.NULL) candidate = null;
        if (candidate instanceof JSONObject) {
            JSONObject obj = (JSONObject) candidate;
            Object selected = obj.opt("format");
            if (selected == null || selected == JSONObject.NULL)
                throw new JSONException("SaveVideo 节点 " + id + " 的 format 对象缺少 format 键");
            validateOption(id, selected, options);
            return candidate;
        }
        if (candidate != null && options != null) {
            Object allowed = findMatchingOption(candidate, options);
            if (allowed != null) return allowed;
            throw new JSONException("SaveVideo 节点 " + id +
                    " 的原有 format 不在 ComfyUI 支持的选项中：" + candidate);
        }
        if (candidate != null) return candidate;

        if (metadata != null && metadata.has("default") && !metadata.isNull("default")) {
            Object declaredDefault = metadata.opt("default");
            if (options == null || findMatchingOption(declaredDefault, options) != null)
                return options == null ? declaredDefault : findMatchingOption(declaredDefault, options);
        }
        if (options != null) {
            Object auto = findMatchingOption("auto", options);
            if (auto != null) return auto;
            if (options.length() == 1) return optionValue(options.opt(0));
        }
        throw new JSONException("SaveVideo 节点 " + id +
                " 缺少 format，服务器未提供可确定的默认值；不能自动猜测编码格式");
    }

    private static void validateOption(String id, Object candidate, JSONArray options) throws JSONException {
        if (options != null && findMatchingOption(candidate, options) == null)
            throw new JSONException("SaveVideo 节点 " + id + " 的 format 不在服务器允许的选项中：" + candidate);
    }

    private static Object findMatchingOption(Object candidate, JSONArray options) {
        if (candidate == null || options == null) return null;
        for (int i = 0; i < options.length(); i++) {
            Object option = optionValue(options.opt(i));
            if (option != null && String.valueOf(option).equals(String.valueOf(candidate)))
                return option;
        }
        return null;
    }

    private static Object optionValue(Object entry) {
        if (entry instanceof JSONObject) return ((JSONObject) entry).opt("key");
        return entry == JSONObject.NULL ? null : entry;
    }

    private static boolean equalsValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.getClass().equals(b.getClass()) && a.equals(b);
    }

    /** Extract the video format actually recorded by ComfyUI /history, if available. */
    public static String submittedFormats(JSONObject entry) {
        if (entry == null) return "";
        JSONArray promptTuple = entry.optJSONArray("prompt");
        JSONObject graph = promptTuple != null && promptTuple.length() > 2
                ? promptTuple.optJSONObject(2) : entry.optJSONObject("prompt");
        if (graph == null) return "";
        StringBuilder text = new StringBuilder();
        Iterator<String> ids = graph.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = graph.optJSONObject(id);
            if (node == null || !"SaveVideo".equals(node.optString("class_type", ""))) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (text.length() > 0) text.append("；");
            text.append("SaveVideo#").append(id).append(" format=");
            text.append(inputs == null || !inputs.has("format")
                    ? "[缺失]" : String.valueOf(inputs.opt("format")));
        }
        return text.toString();
    }
}
