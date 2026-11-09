package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/** Restore native ComfyUI COMBO JSON scalar types without changing user intent. */
public final class PromptValueNormalizer {
    private PromptValueNormalizer() {}

    /**
     * A previously saved API prompt may contain numeric choices as strings because
     * older Android spinner code converted every choice with String.valueOf().
     * Only call /object_info when a candidate exists, to avoid extra requests for
     * ordinary image/video prompts without suspicious scalar inputs.
     */
    public static boolean needsComboNormalization(JSONObject prompt) {
        if (prompt == null) return false;
        Iterator<String> nodes = prompt.keys();
        while (nodes.hasNext()) {
            JSONObject node = prompt.optJSONObject(nodes.next());
            JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
            if (inputs == null) continue;
            Iterator<String> names = inputs.keys();
            while (names.hasNext()) {
                Object value = inputs.opt(names.next());
                if (!(value instanceof String)) continue;
                String text = ((String) value).trim();
                if (text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)") ||
                        text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) return true;
            }
        }
        return false;
    }

    /**
     * Older saved API prompts and pre-V2.8.6 UI conversion can omit SaveVideo.format
     * entirely. ComfyUI V3's COMFY_DYNAMICCOMBO_V3 is not a normal COMBO and
     * cannot be handled by the numeric-string normalizer.
     */
    public static boolean needsRequiredDynamicInspection(JSONObject prompt) {
        if (prompt == null) return false;
        Iterator<String> it = prompt.keys();
        while (it.hasNext()) {
            JSONObject node = prompt.optJSONObject(it.next());
            if (node == null) continue;
            String type = node.optString("class_type", "").toLowerCase(java.util.Locale.ROOT);
            if (type.equals("savevideo") || type.endsWith("_savevideo") || type.endsWith(".savevideo"))
                return true;
        }
        return false;
    }

    /**
     * Reconcile missing required V3 dynamic choices against live /object_info.
     * Choose schema default first, then explicitly advertised auto, then an only
     * possible choice. Otherwise fail visibly instead of silently picking a codec.
     */
    public static int ensureRequiredDynamicInputs(JSONObject prompt, JSONObject objectInfo)
            throws JSONException {
        if (prompt == null || objectInfo == null) return 0;
        int filled = 0;
        Iterator<String> nodeIds = prompt.keys();
        while (nodeIds.hasNext()) {
            String nodeId = nodeIds.next();
            JSONObject node = prompt.optJSONObject(nodeId);
            if (node == null) continue;
            String type = node.optString("class_type", "");
            JSONObject nodeDef = objectInfo.optJSONObject(type);
            JSONObject input = nodeDef == null ? null : nodeDef.optJSONObject("input");
            JSONObject required = input == null ? null : input.optJSONObject("required");
            JSONObject values = node.optJSONObject("inputs");
            if (required == null || values == null) continue;
            Iterator<String> names = required.keys();
            while (names.hasNext()) {
                String name = names.next();
                JSONArray spec = required.optJSONArray(name);
                if (spec == null ||
                        !String.valueOf(spec.opt(0)).toUpperCase(java.util.Locale.ROOT)
                            .startsWith("COMFY_DYNAMICCOMBO")) continue;
                JSONObject meta = spec.optJSONObject(1);
                JSONArray options = meta == null ? null : meta.optJSONArray("options");
                if (values.has(name) && !values.isNull(name)) {
                    // Existing valid scalar and nested selections must survive unchanged.
                    continue;
                }
                Object fallback = meta == null ? null : meta.opt("default");
                if (fallback == JSONObject.NULL) fallback = null;
                if (fallback == null && options != null) {
                    Object sole = null;
                    for (int i = 0; i < options.length(); i++) {
                        JSONObject entry = options.optJSONObject(i);
                        if (entry == null || !entry.has("key")) continue;
                        Object key = entry.opt("key");
                        if ("auto".equals(String.valueOf(key))) { fallback = key; break; }
                        if (sole == null) sole = key;
                    }
                    if (fallback == null && options.length() == 1) fallback = sole;
                }
                if (fallback == null) {
                    throw new JSONException("节点 " + nodeId + " (" + type + ") 缺少必填 " +
                            name + "，而服务端没有可确定的默认选项；请在 ComfyUI 中配置后重新导入。");
                }
                values.put(name, fallback);
                filled++;
            }
        }
        return filled;
    }

    /** Changes only widget values whose raw /object_info choices prove a type correction. */
    public static int normalizeComboValues(JSONObject prompt, JSONObject objectInfo) throws JSONException {
        if (prompt == null || objectInfo == null) return 0;
        int modified = 0;
        Iterator<String> nodes = prompt.keys();
        while (nodes.hasNext()) {
            JSONObject node = prompt.optJSONObject(nodes.next());
            if (node == null) continue;
            JSONObject schema = objectInfo.optJSONObject(node.optString("class_type", ""));
            JSONObject definition = schema == null ? null : schema.optJSONObject("input");
            JSONObject inputs = node.optJSONObject("inputs");
            if (definition == null || inputs == null) continue;
            Iterator<String> names = inputs.keys();
            while (names.hasNext()) {
                String field = names.next();
                Object current = inputs.opt(field);
                // Links and structured inputs must remain untouched.
                if (!(current instanceof String) && !(current instanceof Number) &&
                        !(current instanceof Boolean)) continue;
                JSONArray spec = definitionFor(definition.optJSONObject("required"), field);
                if (spec == null) spec = definitionFor(definition.optJSONObject("optional"), field);
                if (spec == null) continue;
                JSONArray options = optionsFromSpec(spec);
                if (options == null) continue;
                Object canonical = canonicalValue(current, options);
                if (canonical == null || canonical == JSONObject.NULL) continue;
                if (!sameTypeAndValue(current, canonical)) {
                    inputs.put(field, canonical);
                    modified++;
                }
            }
        }
        return modified;
    }

    private static JSONArray definitionFor(JSONObject category, String field) {
        return category == null ? null : category.optJSONArray(field);
    }

    private static JSONArray optionsFromSpec(JSONArray spec) {
        if (spec == null) return null;
        Object first = spec.opt(0);
        if (first instanceof JSONArray) return (JSONArray) first; // legacy COMBO
        JSONObject meta = spec.optJSONObject(1);
        if ("COMBO".equalsIgnoreCase(String.valueOf(first)) && meta != null)
            return meta.optJSONArray("options");  // V3 COMBO
        return null;
    }

    static Object canonicalValue(Object value, JSONArray choices) {
        if (value == null || value == JSONObject.NULL || choices == null) return value;
        // Prefer retaining a value that is already the correct JSON type.
        for (int i = 0; i < choices.length(); i++) {
            Object candidate = choices.opt(i);
            if (sameTypeAndValue(value, candidate)) return candidate;
        }
        for (int i = 0; i < choices.length(); i++) {
            Object candidate = choices.opt(i);
            if (candidate != null && candidate != JSONObject.NULL &&
                    String.valueOf(candidate).equals(String.valueOf(value))) return candidate;
        }
        return value;
    }

    private static boolean sameTypeAndValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number)
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        return a.getClass().equals(b.getClass()) && a.equals(b);
    }
}
