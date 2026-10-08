package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V2.6: Output-rooted branches from a multi-function ComfyUI UI workflow.
 *
 * Never mutates the imported UI JSON. Groups name/discover branches only: dependencies are
 * selected by following actual node links backwards from that branch's output sink(s).
 * Inactive auxiliary groups keep their original bypass modes. This is intentionally NOT
 * an arbitrary crop of the visual canvas and does not emulate arbitrary custom group logic.
 */
public final class WorkflowVariants {
    private WorkflowVariants() {}

    public static JSONArray detect(JSONObject original) {
        JSONArray result = new JSONArray();
        JSONObject ui = unwrap(original);
        JSONArray nodes = ui.optJSONArray("nodes"), groups = ui.optJSONArray("groups");
        if (nodes == null || groups == null || groups.length() == 0) return result;

        Map<Integer, List<String>> byGroup = new LinkedHashMap<>();
        Map<Integer, Boolean> active = new HashMap<>();
        for (int i = 0; i < groups.length(); i++) byGroup.put(i, new ArrayList<>());
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null || !isFinalOutput(n.optString("type", ""))) continue;
            int idx = mostSpecificContainingGroup(n, groups);
            if (idx < 0) continue; // No trustworthy mapping to a distinct function.
            byGroup.get(idx).add(String.valueOf(n.opt("id")));
            if (n.optInt("mode", 0) == 0) active.put(idx, true);
        }
        for (Map.Entry<Integer, List<String>> e : byGroup.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            int idx = e.getKey();
            JSONObject group = groups.optJSONObject(idx);
            if (group == null) continue;
            String title = group.optString("title", "").replaceFirst("^[▶▷\\s]+", "").trim();
            if (title.isEmpty()) title = "子功能 " + (result.length() + 1);
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", "group:" + idx);
                entry.put("title", title);
                entry.put("group_index", idx);
                entry.put("outputs", new JSONArray(e.getValue()));
                entry.put("initially_enabled", active.getOrDefault(idx, false));
                result.put(entry);
            } catch (JSONException ignored) {}
        }
        // Do not split ordinary workflows that merely use groups for organization.
        return result.length() >= 2 ? result : new JSONArray();
    }

    public static String initialVariantId(JSONArray variants) {
        for (int i = 0; i < variants.length(); i++) {
            JSONObject v = variants.optJSONObject(i);
            if (v != null && v.optBoolean("initially_enabled", false)) return v.optString("id", "");
        }
        JSONObject first = variants.optJSONObject(0);
        return first == null ? "" : first.optString("id", "");
    }

    public static String label(JSONArray variants, String id) {
        JSONObject v = find(variants, id);
        return v == null ? "" : v.optString("title", "子功能");
    }

    /** Only final sinks are selectable. Intermediate PreviewImage can be on a data path;
     * removing it as a presumed independent output would break dependent connections. */
    public static List<WorkflowUtils.OutputChoice> finalOutputs(JSONObject prompt, JSONArray variants,
                                                                  String variantId, JSONObject objectInfo) {
        JSONObject v = find(variants, variantId);
        JSONArray ids = v == null ? null : v.optJSONArray("outputs");
        Set<String> allowed = new HashSet<>();
        if (ids != null) for (int i = 0; i < ids.length(); i++) allowed.add(ids.optString(i, ""));
        List<WorkflowUtils.OutputChoice> out = new ArrayList<>();
        for (WorkflowUtils.OutputChoice c : WorkflowUtils.findOutputNodes(prompt, objectInfo))
            if (allowed.contains(c.id)) out.add(c);
        return out;
    }

    public static JSONObject compile(JSONObject original, JSONArray variants, String variantId, String server) throws Exception {
        JSONObject v = find(variants, variantId);
        if (v == null) throw new JSONException("找不到指定的合集子功能：" + variantId);
        JSONObject copy = new JSONObject(unwrap(original).toString());
        JSONArray originalNodes = copy.optJSONArray("nodes");
        JSONArray links = copy.optJSONArray("links");
        JSONArray groups = copy.optJSONArray("groups");
        if (originalNodes == null || links == null || groups == null) throw new JSONException("缺少合集节点、连线或分组数据");

        int selectedGroup = v.optInt("group_index", -1);
        JSONArray outputs = v.optJSONArray("outputs");
        if (selectedGroup < 0 || selectedGroup >= groups.length() || outputs == null || outputs.length() == 0)
            throw new JSONException("合集功能配置缺少有效输出节点");
        JSONObject selectedRect = groups.optJSONObject(selectedGroup);
        if (selectedRect == null) throw new JSONException("合集分组信息已损坏");

        Set<String> targetOutputIds = new HashSet<>();
        for (int i = 0; i < outputs.length(); i++) targetOutputIds.add(outputs.optString(i, ""));
        Map<String, JSONObject> all = new LinkedHashMap<>();
        for (int i = 0; i < originalNodes.length(); i++) {
            JSONObject n = originalNodes.optJSONObject(i);
            if (n == null) continue;
            String id = String.valueOf(n.opt("id"));
            all.put(id, n);
            if (isFinalOutput(n.optString("type", ""))) {
                n.put("mode", targetOutputIds.contains(id) ? 0 : 2);
            } else if (inside(n, selectedRect.optJSONArray("bounding"))) {
                // Activate the chosen feature only. Outside it, preserve e.g. optional
                // bypassed LoRA, reference-image and preprocessing groups as authored.
                if (n.optInt("mode", 0) == 4) n.put("mode", 0);
            }
        }

        Map<String, String> linkOrigins = new HashMap<>();
        for (int i = 0; i < links.length(); i++) {
            JSONArray a = links.optJSONArray(i);
            if (a != null && a.length() >= 5) linkOrigins.put(String.valueOf(a.opt(0)), String.valueOf(a.opt(1)));
            JSONObject o = links.optJSONObject(i);
            if (o != null) linkOrigins.put(String.valueOf(o.opt("id")), o.optString("origin_id", o.optString("source_id", "")));
        }
        Set<String> ancestorIds = new HashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>(targetOutputIds);
        while (!stack.isEmpty()) {
            String id = stack.removeLast();
            if (!ancestorIds.add(id)) continue;
            JSONObject node = all.get(id);
            if (node == null) throw new JSONException("功能输出引用不存在的节点：" + id);
            JSONArray inputs = node.optJSONArray("inputs");
            if (inputs == null) continue;
            for (int j = 0; j < inputs.length(); j++) {
                JSONObject input = inputs.optJSONObject(j);
                if (input == null || input.isNull("link")) continue;
                String rawLink = String.valueOf(input.opt("link"));
                String src = linkOrigins.get(rawLink);
                if (src == null || src.isEmpty()) throw new JSONException("节点 " + id + " 的输入 " + input.optString("name") + " 缺失连线 " + rawLink);
                stack.add(src);
            }
        }
        JSONArray keptNodes = new JSONArray();
        for (int i = 0; i < originalNodes.length(); i++) {
            JSONObject node = originalNodes.optJSONObject(i);
            if (node != null && ancestorIds.contains(String.valueOf(node.opt("id")))) keptNodes.put(node);
        }
        JSONArray keptLinks = new JSONArray();
        for (int i = 0; i < links.length(); i++) {
            JSONArray a = links.optJSONArray(i);
            if (a != null && a.length() >= 5 && ancestorIds.contains(String.valueOf(a.opt(1))) && ancestorIds.contains(String.valueOf(a.opt(3)))) keptLinks.put(a);
            JSONObject o = links.optJSONObject(i);
            if (o != null && ancestorIds.contains(o.optString("origin_id", "")) && ancestorIds.contains(o.optString("target_id", ""))) keptLinks.put(o);
        }
        copy.put("nodes", keptNodes);
        copy.put("links", keptLinks);
        // Conversion uses live /object_info and validates the actual node definitions.
        JSONObject prompt = WorkflowUiConverter.toApiPrompt(copy, server);
        for (String outputId : targetOutputIds) {
            if (!prompt.has(outputId)) throw new JSONException("没有成功编译子功能输出节点 " + outputId);
        }
        // A node connection must have a valid producer in the compiled execution graph.
        Iterator<String> keys = prompt.keys();
        while (keys.hasNext()) {
            String nodeId = keys.next();
            JSONObject inputs = prompt.getJSONObject(nodeId).optJSONObject("inputs");
            if (inputs == null) continue;
            Iterator<String> inputKeys = inputs.keys();
            while (inputKeys.hasNext()) {
                String inputName = inputKeys.next();
                JSONArray ref = inputs.optJSONArray(inputName);
                if (ref != null && ref.length() == 2 && ref.opt(0) instanceof String && ref.opt(1) instanceof Number && !prompt.has(ref.optString(0)))
                    throw new JSONException("子功能 " + label(variants, variantId) + " 的节点 " + nodeId + " / " + inputName + " 存在断开的输入：" + ref.optString(0));
            }
        }
        return prompt;
    }

    private static JSONObject find(JSONArray variants, String id) {
        if (variants == null) return null;
        for (int i = 0; i < variants.length(); i++) {
            JSONObject entry = variants.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id", ""))) return entry;
        }
        return null;
    }

    private static JSONObject unwrap(JSONObject original) {
        JSONObject nested = original.optJSONObject("workflow");
        return nested != null && nested.optJSONArray("nodes") != null ? nested : original;
    }

    private static boolean isFinalOutput(String type) {
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.equals("saveimage") || t.startsWith("saveimage") || t.contains("saveanimated") ||
                t.equals("savevideo") || t.equals("videocombine") || t.equals("savewebp") || t.equals("savegif");
    }

    private static int mostSpecificContainingGroup(JSONObject node, JSONArray groups) {
        int best = -1;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            JSONArray box = group.optJSONArray("bounding");
            if (!inside(node, box)) continue;
            double area = box.optDouble(2, 0) * box.optDouble(3, 0);
            if (area < bestArea) { best = i; bestArea = area; }
        }
        return best;
    }

    private static boolean inside(JSONObject node, JSONArray rect) {
        JSONArray pos = node.optJSONArray("pos");
        if (pos == null || rect == null || pos.length() < 2 || rect.length() < 4) return false;
        double x = pos.optDouble(0), y = pos.optDouble(1);
        return x >= rect.optDouble(0) && y >= rect.optDouble(1) &&
                x <= rect.optDouble(0) + rect.optDouble(2) && y <= rect.optDouble(1) + rect.optDouble(3);
    }
}
