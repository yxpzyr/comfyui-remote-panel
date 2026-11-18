package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;

/** V3 choices on the wire are scalar keys, never /object_info option definitions. */
public final class DynamicComboGuard {
    private DynamicComboGuard() {}

    public static boolean isDefinition(Object value) {
        if (!(value instanceof JSONObject)) return false;
        JSONObject item = (JSONObject) value;
        return item.has("key") && item.optJSONObject("inputs") != null;
    }

    public static Object optionKey(Object value) {
        return isDefinition(value) ? ((JSONObject) value).opt("key") : value;
    }

    private static boolean dynamic(JSONArray spec) {
        return spec != null && String.valueOf(spec.opt(0)).startsWith("COMFY_DYNAMICCOMBO");
    }

    private static boolean hasDedicatedGuard(JSONObject node) {
        String type = node.optString("class_type").toLowerCase(java.util.Locale.ROOT);
        return type.equals("savevideo") || type.endsWith("_savevideo") || type.endsWith(".savevideo");
    }

    /** Read original widget literals without compiling unrelated collection branches. */
    public static JSONObject configuredFromUi(JSONObject ui) throws JSONException {
        JSONObject result = new JSONObject();
        JSONArray nodes = ui == null ? null : ui.optJSONArray("nodes");
        if (nodes == null) return result;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            JSONObject values = node.optJSONObject("widgets_values_named");
            if (values == null) values = node.optJSONObject("widgets_values");
            if (values == null) {
                values = new JSONObject();
                JSONArray widgets = node.optJSONArray("widgets_values");
                JSONArray inputs = node.optJSONArray("inputs");
                int cursor = 0;
                if (widgets != null && inputs != null) for (int j = 0; j < inputs.length(); j++) {
                    JSONObject input = inputs.optJSONObject(j);
                    if (input == null || input.optJSONObject("widget") == null) continue;
                    if (cursor < widgets.length()) values.put(input.optString("name"), widgets.opt(cursor));
                    cursor++;
                }
            }
            result.put(String.valueOf(node.opt("id")), new JSONObject()
                    .put("class_type", node.optString("type")).put("inputs", values));
        }
        return result;
    }

    private static Object selection(Object value, String name, JSONArray options) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            value = object.has(name) ? object.opt(name) : object.opt("key");
        }
        if (value == null || value == JSONObject.NULL || options == null) return null;
        for (int i = 0; i < options.length(); i++) {
            Object key = optionKey(options.opt(i));
            if (key != null && !(key instanceof JSONObject) && !(key instanceof JSONArray) &&
                    String.valueOf(key).equals(String.valueOf(value))) return key;
        }
        return null;
    }

    public static Object selectedKey(Object value, String name, JSONArray spec) {
        JSONObject meta = spec == null ? null : spec.optJSONObject(1);
        return selection(value, name, meta == null ? null : meta.optJSONArray("options"));
    }

    /** Only broken/missing selectors need the original UI's configured choice. */
    public static boolean needsUiRecovery(JSONObject prompt, JSONObject info) {
        if (prompt == null || info == null) return false;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            JSONObject node = prompt.optJSONObject(ids.next());
            if (node == null) continue;
            if (hasDedicatedGuard(node)) continue;
            JSONObject definition = info.optJSONObject(node.optString("class_type"));
            JSONObject input = definition == null ? null : definition.optJSONObject("input");
            JSONObject values = node.optJSONObject("inputs");
            if (input == null || values == null) continue;
            for (String group : new String[]{"required", "optional"}) {
                JSONObject defs = input.optJSONObject(group);
                if (defs == null) continue;
                Iterator<String> names = defs.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    JSONArray spec = defs.optJSONArray(name);
                    if (!dynamic(spec)) continue;
                    Object current = values.opt(name);
                    if (current instanceof JSONArray) continue;
                    if (current instanceof JSONObject || (current == null && group.equals("required")) ||
                            (current != null && selectedKey(current, name, spec) == null)) return true;
                }
            }
        }
        return false;
    }

    /** Fix submission copies only; valid selected keys and existing values survive. */
    public static int repair(JSONObject prompt, JSONObject info, JSONObject originalPrompt) throws JSONException {
        if (prompt == null || info == null) return 0;
        int changes = 0;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            if (hasDedicatedGuard(node)) continue;
            JSONObject definition = info.optJSONObject(node.optString("class_type"));
            JSONObject input = definition == null ? null : definition.optJSONObject("input");
            JSONObject values = node.optJSONObject("inputs");
            if (input == null || values == null) continue;
            JSONObject original = originalPrompt == null ? null : originalPrompt.optJSONObject(id);
            if (original != null && !node.optString("class_type").equals(original.optString("class_type"))) original = null;
            JSONObject originalValues = original == null ? null : original.optJSONObject("inputs");
            for (String group : new String[]{"required", "optional"}) {
                JSONObject defs = input.optJSONObject(group);
                if (defs == null) continue;
                Iterator<String> names = defs.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    JSONArray spec = defs.optJSONArray(name);
                    if (!dynamic(spec)) continue;
                    Object current = values.opt(name);
                    if (current instanceof JSONArray) continue;
                    Object key = selectedKey(current, name, spec);
                    if (isDefinition(current) || key == null) {
                        Object configured = originalValues == null ? null : selectedKey(originalValues.opt(name), name, spec);
                        if (configured != null) key = configured;
                    }
                    if (key == null) {
                        if (current == null || current == JSONObject.NULL) continue; // existing missing-default guard handles this
                        throw new JSONException("节点 " + id + " (" + node.optString("class_type") +
                                ") 的动态选项 " + name + " 无法恢复，请从已配置的工作流重新导入。");
                    }
                    if (!(current instanceof String) || !key.equals(current)) {
                        values.put(name, key);
                        changes++;
                    }
                    JSONObject meta = spec.optJSONObject(1);
                    JSONArray options = meta == null ? null : meta.optJSONArray("options");
                    if (options == null) continue;
                    for (int i = 0; i < options.length(); i++) {
                        JSONObject option = options.optJSONObject(i);
                        if (option == null || !String.valueOf(option.opt("key")).equals(String.valueOf(key))) continue;
                        JSONObject children = option.optJSONObject("inputs");
                        JSONObject required = children == null ? null : children.optJSONObject("required");
                        if (required == null) break;
                        Iterator<String> childNames = required.keys();
                        while (childNames.hasNext()) {
                            String child = childNames.next();
                            String fullName = name + "." + child;
                            if (values.has(fullName) && !values.isNull(fullName)) continue;
                            Object nested = current instanceof JSONObject ? ((JSONObject) current).opt(child) : null;
                            JSONArray childSpec = required.optJSONArray(child);
                            JSONObject childMeta = childSpec == null ? null : childSpec.optJSONObject(1);
                            Object defaultValue = childMeta == null ? null : childMeta.opt("default");
                            Object value = nested != null && !(nested instanceof JSONObject) && !(nested instanceof JSONArray)
                                    ? nested : defaultValue;
                            if (value != null && value != JSONObject.NULL) values.put(fullName, value);
                        }
                        break;
                    }
                }
            }
        }
        return changes;
    }
}
