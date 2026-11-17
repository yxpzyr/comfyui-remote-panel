package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Text output metadata is kept alongside media refs; never interpret arbitrary inputs as results. */
final class TextOutputs {
    private TextOutputs() {}
    static boolean isTextSink(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", "");
        return t.contains("textdisplay") || t.contains("displaytext") || t.contains("showtext") || t.contains("previewtext")
                || t.contains("savetext") || t.contains("showany") || t.contains("promptenhancer");
    }
    static boolean expectsText(JSONObject prompt) {
        boolean hasText = false;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            JSONObject node = prompt.optJSONObject(ids.next());
            if (node == null) continue;
            String type = node.optString("class_type");
            if (isTextSink(type)) hasText = true;
            String t = type.toLowerCase(Locale.ROOT).replace("_", "");
            if (t.contains("savevideo") || t.contains("videocombine") || t.contains("saveimage") || t.contains("saveaudio")) return false;
        }
        return hasText;
    }
    static JSONArray parse(JSONObject history, String promptId, Set<String> allowed) throws Exception {
        JSONArray result = new JSONArray();
        JSONObject entry = history == null ? null : history.optJSONObject(promptId);
        JSONObject outputs = entry == null ? null : entry.optJSONObject("outputs");
        if (outputs == null) return result;
        Set<String> seen = new HashSet<>(); int remaining = 1024 * 1024;
        Iterator<String> ids = outputs.keys();
        while (ids.hasNext() && result.length() < 32 && remaining > 0) {
            String id = ids.next();
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(id)) continue;
            JSONObject output = outputs.optJSONObject(id);
            if (output == null) continue;
            Iterator<String> fields = output.keys();
            while (fields.hasNext() && result.length() < 32 && remaining > 0) {
                String field = fields.next(), lower = field.toLowerCase(Locale.ROOT);
                if (!(lower.contains("text") || lower.contains("string") || lower.contains("prompt") ||
                        lower.equals("result") || lower.equals("value") || lower.equals("output") ||
                        lower.contains("提示词") || lower.contains("文本"))) continue;
                String value = text(output.opt(field), 0);
                if (value.trim().isEmpty() || !seen.add(id + "|" + value)) continue;
                int limit = Math.min(256 * 1024, remaining);
                boolean truncated = value.length() > limit;
                if (truncated) value = value.substring(0, limit);
                result.put(new JSONObject().put("node_id", id).put("field", field)
                        .put("text", value).put("truncated", truncated));
                remaining -= value.length();
            }
        }
        return result;
    }
    private static String text(Object value, int depth) {
        if (depth > 4 || value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder out = new StringBuilder(); JSONArray array = (JSONArray) value;
            for (int i = 0; i < Math.min(128, array.length()) && out.length() < 256 * 1024; i++) {
                String part = text(array.opt(i), depth + 1);
                if (!part.isEmpty()) { if (out.length() > 0) out.append('\n'); out.append(part); }
            }
            return out.toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : new String[]{"text", "content", "value", "result"}) {
                String part = text(object.opt(key), depth + 1); if (!part.isEmpty()) return part;
            }
        }
        return "";
    }
    static String combined(String json) {
        try {
            JSONArray array = new JSONArray(json); StringBuilder result = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append("\n\n");
                result.append(array.getJSONObject(i).optString("text"));
            }
            return result.toString();
        } catch (Exception ignored) { return ""; }
    }
}
