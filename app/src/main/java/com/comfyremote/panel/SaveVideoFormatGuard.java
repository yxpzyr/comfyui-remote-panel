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
    /**
     * Repair a converted UI branch using the exact original SaveVideo widget choice.
     * In some ComfyUI versions the generic widget aligner writes a nested "codec"
     * object into format; that object is NOT a valid SaveVideo.format choice.
     */
    public static int repairConvertedBranch(JSONObject uiGraph, JSONObject prompt,
                                            JSONObject objectInfo) throws JSONException {
        if (uiGraph == null || prompt == null || !hasSaveVideo(prompt)) return 0;
        JSONObject sourceNodes = indexUiNodes(uiGraph);
        int repaired = 0;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject target = prompt.optJSONObject(id);
            if (target == null || !"SaveVideo".equals(target.optString("class_type", ""))) continue;
            JSONObject inputs = target.optJSONObject("inputs");
            if (inputs == null || !needsRepair(inputs.opt("format"))) continue;
            JSONObject source = sourceNodes.optJSONObject(id);
            if (source == null || !"SaveVideo".equals(source.optString("type", ""))) continue;
            JSONObject nodeInfo = objectInfo == null ? null : objectInfo.optJSONObject("SaveVideo");
            if (nodeInfo == null) continue;
            preserveMisplacedCodec(inputs.opt("format"), inputs);
            Object value = uiWidgetValue(source, "format");
            if (value == null || value == JSONObject.NULL) continue;
            inputs.put("format", canonicalize(id, value, objectInfo, true));
            repaired++;
        }
        return repaired;
    }

    /** Also invoked during UI conversion, before regular required-field validation. */
    public static void repairUiNode(JSONObject uiNode, JSONObject apiInputs, JSONObject nodeSchema)
            throws JSONException {
        if (uiNode == null || apiInputs == null ||
                !"SaveVideo".equals(uiNode.optString("type", ""))) return;
        String id = String.valueOf(uiNode.opt("id"));
        JSONObject info = new JSONObject().put("SaveVideo", nodeSchema);
        Object original = uiWidgetValue(uiNode, "format");
        if (original != null && original != JSONObject.NULL) {
            // The UI's recorded choice is authoritative when compiling a new branch.
            Object existing = apiInputs.opt("format");
            if (needsRepair(existing)) preserveMisplacedCodec(existing, apiInputs);
            apiInputs.put("format", canonicalize(id, original, info, true));
        } else if (needsRepair(apiInputs.opt("format"))) {
            Object existing = apiInputs.opt("format");
            preserveMisplacedCodec(existing, apiInputs);
            apiInputs.put("format", canonicalize(id, null, info, true));
        }
    }

    /** Only malformed or missing values need expensive saved-UI rehydration. */
    public static boolean needsUiRecovery(JSONObject prompt) {
        if (prompt == null) return false;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            JSONObject node = prompt.optJSONObject(ids.next());
            if (node == null || !"SaveVideo".equals(node.optString("class_type", ""))) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs != null && needsRepair(inputs.opt("format"))) return true;
        }
        return false;
    }

    private static boolean needsRepair(Object original) {
        if (original == null || original == JSONObject.NULL) return true;
        if (!(original instanceof JSONObject)) return false;
        JSONObject object = (JSONObject) original;
        return !object.has("format") || object.isNull("format");
    }

    private static JSONObject indexUiNodes(JSONObject root) throws JSONException {
        JSONObject nodes = new JSONObject();
        if (root == null) return nodes;
        JSONArray list = root.optJSONArray("nodes");
        if (list == null) {
            JSONObject wrapped = root.optJSONObject("workflow");
            list = wrapped == null ? null : wrapped.optJSONArray("nodes");
        }
        if (list == null) return nodes;
        for (int i = 0; i < list.length(); i++) {
            JSONObject node = list.optJSONObject(i);
            if (node != null) nodes.put(String.valueOf(node.opt("id")), node);
        }
        return nodes;
    }

    /** Keep a misplaced codec selection rather than discarding user settings. */
    private static void preserveMisplacedCodec(Object badFormat, JSONObject inputs) throws JSONException {
        if (!(badFormat instanceof JSONObject) || inputs.has("codec")) return;
        JSONObject obj = (JSONObject) badFormat;
        if (!obj.has("codec") || obj.isNull("codec")) return;
        Object codec = obj.opt("codec");
        if (codec instanceof JSONObject) inputs.put("codec", codec);
        else if (codec instanceof String)
            inputs.put("codec", new JSONObject().put("codec", codec));
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
            if (needsRepair(original)) preserveMisplacedCodec(original, inputs);
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
            if (selected != null && selected != JSONObject.NULL) {
                // Native SaveVideo supports a full nested format/codec selection.
                validateOption(id, selected, options);
                return candidate;
            }
            // Legacy widget alignment may place {"codec":"auto"} in the
            // format slot. It is a codec selection, NOT a valid format.
            // Unwrap explicit choice wrappers only if the server lists them.
            for (String key : new String[]{"value", "key"}) {
                Object wrapped = obj.opt(key);
                if (wrapped != null && wrapped != JSONObject.NULL &&
                        options != null && findMatchingOption(wrapped, options) != null) {
                    return findMatchingOption(wrapped, options);
                }
            }
            candidate = null; // recover via schema default / explicit auto
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
