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
