package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts a normal ComfyUI UI/LiteGraph workflow (nodes + links) into /prompt API format. */
public final class WorkflowUiConverter {
    private WorkflowUiConverter() {}

    public static JSONObject toApiPrompt(JSONObject raw, String serverBase) throws Exception {
        JSONObject root = unwrap(raw);
        if (isApiFormat(root)) return new JSONObject(root.toString());

        JSONArray nodes = root.optJSONArray("nodes");
        JSONArray links = root.optJSONArray("links");
        if (nodes == null || links == null) {
            throw new JSONException("无法识别工作流：既不是 API 格式，也没有普通工作流需要的 nodes / links。 ");
        }

        JSONObject objectInfo = fetchObjectInfo(serverBase);
        Map<String, JSONObject> nodeById = new LinkedHashMap<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null) nodeById.put(String.valueOf(node.opt("id")), node);
        }
        Map<String, Link> linkById = parseLinks(links);

        JSONObject prompt = new JSONObject();
        List<String> unsupported = new ArrayList<>();

        for (JSONObject node : nodeById.values()) {
            String id = String.valueOf(node.opt("id"));
            String type = node.optString("type", "");
            int mode = node.optInt("mode", 0);

            if (isVirtualNode(type) || mode == 4 || mode == 2) continue;

            JSONObject def = objectInfo.optJSONObject(type);
            if (def == null) {
                if (isDecorativeOrUnused(node, linkById)) continue;
                unsupported.add(type + "（节点 " + id + "）");
                continue;
            }

            JSONObject apiNode = new JSONObject();
            JSONObject apiInputs = new JSONObject();
            apiNode.put("inputs", apiInputs);
            apiNode.put("class_type", type);
            JSONObject meta = new JSONObject();
            meta.put("title", displayTitle(node, type));
            apiNode.put("_meta", meta);

            Set<String> connectedNames = new HashSet<>();
            JSONArray uiInputs = node.optJSONArray("inputs");
            if (uiInputs != null) {
                for (int j = 0; j < uiInputs.length(); j++) {
                    JSONObject input = uiInputs.optJSONObject(j);
                    if (input == null) continue;
                    String name = input.optString("name", "");
                    String linkId = normalizeLinkId(input.opt("link"));
                    if (name.isEmpty() || linkId == null) continue;
                    Link link = linkById.get(linkId);
                    if (link == null) continue;
                    ResolvedSource src = resolveSource(link.sourceId, link.sourceSlot, nodeById, linkById, new HashSet<>());
                    if (src.literalSet) apiInputs.put(name, src.literal);
                    else apiInputs.put(name, new JSONArray().put(src.nodeId).put(src.slot));
                    connectedNames.add(name);
                }
            }

            NodeSchema schema = NodeSchema.from(def);
            applyWidgetValues(node, schema, apiInputs, connectedNames);
            applyDefaults(schema, apiInputs, connectedNames);

            prompt.put(id, apiNode);
        }

        if (!unsupported.isEmpty()) {
            StringBuilder sb = new StringBuilder("以下节点无法在远程 ComfyUI 的 /object_info 中找到，因此无法自动转换：\n\n");
            int n = Math.min(unsupported.size(), 12);
            for (int i = 0; i < n; i++) sb.append("• ").append(unsupported.get(i)).append('\n');
            if (unsupported.size() > n) sb.append("• 以及另外 ").append(unsupported.size() - n).append(" 个节点\n");
            sb.append("\n请确认电脑端已经安装并启用了这些自定义节点；如果它们属于前端专用/子图节点，则需要先在 ComfyUI 中展开或导出 API 工作流。");
            throw new JSONException(sb.toString());
        }
        if (prompt.length() == 0) throw new JSONException("转换后没有可执行节点。 ");
        return prompt;
    }

    private static JSONObject unwrap(JSONObject raw) {
        if (raw.optJSONObject("prompt") != null && isApiFormat(raw.optJSONObject("prompt"))) return raw.optJSONObject("prompt");
        if (raw.optJSONObject("workflow") != null && raw.optJSONObject("workflow").optJSONArray("nodes") != null) return raw.optJSONObject("workflow");
        return raw;
    }

    private static boolean isApiFormat(JSONObject obj) {
        if (obj == null) return false;
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            Object value = obj.opt(it.next());
            if (value instanceof JSONObject && ((JSONObject) value).has("class_type")) return true;
        }
        return false;
    }

    private static JSONObject fetchObjectInfo(String serverBase) throws Exception {
        String base = ComfyApiClient.normalizeBase(serverBase);
        Exception last = null;
        for (String path : new String[]{"/object_info", "/api/object_info"}) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(base + path).openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(8000);
                con.setReadTimeout(30000);
                con.setUseCaches(false);
                int code = con.getResponseCode();
                byte[] data = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
                if (code >= 200 && code < 300) return new JSONObject(new String(data, StandardCharsets.UTF_8));
                last = new Exception("HTTP " + code + " " + path);
            } catch (Exception e) { last = e; }
            finally { if (con != null) con.disconnect(); }
        }
        throw new Exception("读取 ComfyUI /object_info 失败。请先确认端口已连接：" + (last == null ? "未知错误" : last.getMessage()));
    }

    private static Map<String, Link> parseLinks(JSONArray links) {
        Map<String, Link> result = new HashMap<>();
        for (int i = 0; i < links.length(); i++) {
            Object raw = links.opt(i);
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                if (a.length() < 5) continue;
                String id = String.valueOf(a.opt(0));
                result.put(id, new Link(id, String.valueOf(a.opt(1)), a.optInt(2, 0), String.valueOf(a.opt(3)), a.optInt(4, 0)));
            } else if (raw instanceof JSONObject) {
                JSONObject o = (JSONObject) raw;
                String id = String.valueOf(o.opt("id"));
                String src = firstNonEmpty(o.optString("origin_id", ""), o.optString("source_id", ""), o.optString("from_node", ""));
                String dst = firstNonEmpty(o.optString("target_id", ""), o.optString("dest_id", ""), o.optString("to_node", ""));
                int srcSlot = o.has("origin_slot") ? o.optInt("origin_slot", 0) : o.optInt("source_slot", 0);
                int dstSlot = o.has("target_slot") ? o.optInt("target_slot", 0) : o.optInt("dest_slot", 0);
                if (!id.isEmpty() && !src.isEmpty()) result.put(id, new Link(id, src, srcSlot, dst, dstSlot));
            }
        }
        return result;
    }

    private static ResolvedSource resolveSource(String nodeId, int slot, Map<String, JSONObject> nodes,
                                                Map<String, Link> links, Set<String> visiting) throws Exception {
        String key = nodeId + ":" + slot;
        if (!visiting.add(key)) throw new JSONException("工作流中检测到循环的旁路/重定向连接：" + key);
        JSONObject node = nodes.get(nodeId);
        if (node == null) return ResolvedSource.connection(nodeId, slot);

        String type = node.optString("type", "");
        int mode = node.optInt("mode", 0);
        if ("PrimitiveNode".equalsIgnoreCase(type)) {
            Object literal = firstWidgetValue(node);
            if (literal == null || literal == JSONObject.NULL) throw new JSONException("PrimitiveNode " + nodeId + " 没有可解析的值");
            return ResolvedSource.literal(literal);
        }
        if (mode == 2) throw new JSONException("节点 " + nodeId + "（" + type + "）处于禁用模式，但仍被其他节点引用。请在 ComfyUI 中启用它或断开连接。");
        if (mode == 4 || isReroute(type)) {
            JSONArray ins = node.optJSONArray("inputs");
            if (ins == null || ins.length() == 0) throw new JSONException("无法解析旁路节点 " + nodeId);
            JSONObject chosen = choosePassthroughInput(node, slot);
            if (chosen == null) throw new JSONException("旁路节点 " + nodeId + " 没有可用输入连接");
            String lid = normalizeLinkId(chosen.opt("link"));
            Link l = lid == null ? null : links.get(lid);
            if (l == null) throw new JSONException("旁路节点 " + nodeId + " 的输入未连接");
            return resolveSource(l.sourceId, l.sourceSlot, nodes, links, visiting);
        }
        return ResolvedSource.connection(nodeId, slot);
    }

    private static JSONObject choosePassthroughInput(JSONObject node, int outputSlot) {
        JSONArray ins = node.optJSONArray("inputs");
        JSONArray outs = node.optJSONArray("outputs");
        if (ins == null) return null;
        String outType = "";
        if (outs != null && outputSlot >= 0 && outputSlot < outs.length()) {
            JSONObject out = outs.optJSONObject(outputSlot);
            if (out != null) outType = String.valueOf(out.opt("type"));
        }
        if (!outType.isEmpty()) {
            for (int i = 0; i < ins.length(); i++) {
                JSONObject in = ins.optJSONObject(i);
                if (in != null && normalizeLinkId(in.opt("link")) != null && outType.equals(String.valueOf(in.opt("type")))) return in;
            }
        }
        if (outputSlot >= 0 && outputSlot < ins.length()) {
            JSONObject same = ins.optJSONObject(outputSlot);
            if (same != null && normalizeLinkId(same.opt("link")) != null) return same;
        }
        for (int i = 0; i < ins.length(); i++) {
            JSONObject in = ins.optJSONObject(i);
            if (in != null && normalizeLinkId(in.opt("link")) != null) return in;
        }
        return null;
    }

    private static void applyWidgetValues(JSONObject node, NodeSchema schema, JSONObject apiInputs, Set<String> connected) throws JSONException {
        Object w = node.opt("widgets_values");
        if (w instanceof JSONObject) {
            JSONObject map = (JSONObject) w;
            Iterator<String> it = map.keys();
            while (it.hasNext()) {
                String name = it.next();
                if (schema.widgetNames.contains(name) && !connected.contains(name)) apiInputs.put(name, map.opt(name));
            }
            return;
        }
        if (!(w instanceof JSONArray)) return;
        JSONArray values = (JSONArray) w;
        int vi = 0;
        for (String name : schema.widgetNames) {
            if (connected.contains(name)) continue;
            InputDef def = schema.defs.get(name);
            if (def == null) continue;
            while (vi < values.length() && isControlToken(values.opt(vi)) && !isStringLike(def)) vi++;
            if (vi >= values.length()) break;

            Object value = values.opt(vi);
            if (!matchesDef(value, def)) {
                int found = -1;
                for (int look = vi + 1; look < Math.min(values.length(), vi + 4); look++) {
                    if (matchesDef(values.opt(look), def)) { found = look; break; }
                }
                if (found >= 0) vi = found;
            }
            value = values.opt(vi++);
            if (value != null && value != JSONObject.NULL) apiInputs.put(name, value);

            if (isSeedLike(name) && vi < values.length() && isControlToken(values.opt(vi))) vi++;
        }
    }

    private static void applyDefaults(NodeSchema schema, JSONObject apiInputs, Set<String> connected) throws JSONException {
        for (String name : schema.requiredNames) {
            if (apiInputs.has(name) || connected.contains(name)) continue;
            InputDef def = schema.defs.get(name);
            if (def != null && def.widget && def.hasDefault) apiInputs.put(name, def.defaultValue);
        }
    }

    private static boolean matchesDef(Object value, InputDef def) {
        if (value == null || value == JSONObject.NULL) return true;
        if (def.combo) return value instanceof String || value instanceof Number || value instanceof Boolean;
        String t = def.type.toUpperCase(Locale.ROOT);
        if ("INT".equals(t)) return value instanceof Number;
        if ("FLOAT".equals(t) || "NUMBER".equals(t)) return value instanceof Number;
        if ("BOOLEAN".equals(t)) return value instanceof Boolean;
        if ("STRING".equals(t)) return value instanceof String || value instanceof JSONObject || value instanceof JSONArray;
        return true;
    }

    private static boolean isStringLike(InputDef def) {
        return def.combo || "STRING".equalsIgnoreCase(def.type);
    }

    private static boolean isSeedLike(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("seed") || n.endsWith("_seed") || n.contains("noise_seed");
    }

    private static boolean isControlToken(Object v) {
        if (!(v instanceof String)) return false;
        String s = ((String) v).toLowerCase(Locale.ROOT);
        return s.equals("fixed") || s.equals("randomize") || s.equals("increment") || s.equals("decrement") || s.equals("increment per node") || s.equals("decrement per node");
    }

    private static boolean isVirtualNode(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("primitivenode") || isReroute(type) || t.equals("note") || t.equals("markdownnote") || t.equals("group") || t.equals("groupnode");
    }

    private static boolean isReroute(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("reroute") || t.contains("reroute");
    }

    private static boolean isDecorativeOrUnused(JSONObject node, Map<String, Link> links) {
        String id = String.valueOf(node.opt("id"));
        String type = node.optString("type", "").toLowerCase(Locale.ROOT);
        if (type.contains("note") || type.contains("markdown")) return true;
        for (Link l : links.values()) if (id.equals(l.sourceId) || id.equals(l.targetId)) return false;
        return true;
    }

    private static Object firstWidgetValue(JSONObject node) {
        Object w = node.opt("widgets_values");
        if (w instanceof JSONArray) return ((JSONArray) w).opt(0);
        if (w instanceof JSONObject) {
            JSONObject o = (JSONObject) w;
            Iterator<String> it = o.keys();
            if (it.hasNext()) return o.opt(it.next());
        }
        return null;
    }

    private static String displayTitle(JSONObject node, String fallback) {
        String title = node.optString("title", "");
        if (!title.isEmpty()) return title;
        JSONObject props = node.optJSONObject("properties");
        if (props != null) {
            title = props.optString("Node name for S&R", "");
            if (!title.isEmpty()) return title;
        }
        return fallback;
    }

    private static String normalizeLinkId(Object o) {
        if (o == null || o == JSONObject.NULL) return null;
        if (o instanceof Number && ((Number) o).longValue() < 0) return null;
        String s = String.valueOf(o);
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "-1".equals(s)) return null;
        return s;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static final class Link {
        final String id, sourceId, targetId;
        final int sourceSlot, targetSlot;
        Link(String id, String sourceId, int sourceSlot, String targetId, int targetSlot) {
            this.id = id; this.sourceId = sourceId; this.sourceSlot = sourceSlot; this.targetId = targetId; this.targetSlot = targetSlot;
        }
    }

    private static final class ResolvedSource {
        final String nodeId; final int slot; final boolean literalSet; final Object literal;
        private ResolvedSource(String nodeId, int slot, boolean literalSet, Object literal) {
            this.nodeId = nodeId; this.slot = slot; this.literalSet = literalSet; this.literal = literal;
        }
        static ResolvedSource connection(String id, int slot) { return new ResolvedSource(id, slot, false, null); }
        static ResolvedSource literal(Object value) { return new ResolvedSource(null, 0, true, value); }
    }

    private static final class InputDef {
        final String name, type;
        final boolean widget, combo, required, hasDefault;
        final Object defaultValue;
        InputDef(String name, String type, boolean widget, boolean combo, boolean required, boolean hasDefault, Object defaultValue) {
            this.name = name; this.type = type; this.widget = widget; this.combo = combo; this.required = required; this.hasDefault = hasDefault; this.defaultValue = defaultValue;
        }
    }

    private static final class NodeSchema {
        final Map<String, InputDef> defs = new LinkedHashMap<>();
        final List<String> orderedNames = new ArrayList<>();
        final List<String> widgetNames = new ArrayList<>();
        final List<String> requiredNames = new ArrayList<>();

        static NodeSchema from(JSONObject nodeDef) {
            NodeSchema out = new NodeSchema();
            JSONObject input = nodeDef.optJSONObject("input");
            JSONObject order = nodeDef.optJSONObject("input_order");
            appendGroup(out, input, order, "required", true);
            appendGroup(out, input, order, "optional", false);
            return out;
        }

        private static void appendGroup(NodeSchema out, JSONObject input, JSONObject order, String group, boolean required) {
            JSONObject defs = input == null ? null : input.optJSONObject(group);
            if (defs == null) return;
            List<String> names = new ArrayList<>();
            JSONArray ordered = order == null ? null : order.optJSONArray(group);
            if (ordered != null) {
                for (int i = 0; i < ordered.length(); i++) if (!ordered.optString(i, "").isEmpty()) names.add(ordered.optString(i));
            }
            if (names.isEmpty()) {
                Iterator<String> it = defs.keys();
                while (it.hasNext()) names.add(it.next());
            }
            for (String name : names) {
                Object raw = defs.opt(name);
                InputDef d = parseInputDef(name, raw, required);
                out.defs.put(name, d);
                out.orderedNames.add(name);
                if (required) out.requiredNames.add(name);
                if (d.widget) out.widgetNames.add(name);
            }
        }

        private static InputDef parseInputDef(String name, Object raw, boolean required) {
            boolean combo = false, widget = false, hasDefault = false;
            String type = "";
            Object defaultValue = null;
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                Object first = a.opt(0);
                if (first instanceof JSONArray) { combo = true; widget = true; type = "COMBO"; }
                else type = String.valueOf(first);
                JSONObject opts = a.optJSONObject(1);
                boolean forceInput = opts != null && opts.optBoolean("forceInput", false);
                String upper = type.toUpperCase(Locale.ROOT);
                if (!forceInput && (combo || upper.equals("INT") || upper.equals("FLOAT") || upper.equals("NUMBER") || upper.equals("STRING") || upper.equals("BOOLEAN"))) widget = true;
                if (opts != null && opts.has("default")) { hasDefault = true; defaultValue = opts.opt("default"); }
            }
            return new InputDef(name, type, widget, combo, required, hasDefault, defaultValue);
        }
    }
}
