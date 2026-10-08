package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only view of the original UI graph, NOT a set of executable /object_info nodes.
 * Frontend rgthree switches and image comparers must be visible without ever being
 * injected into ComfyUI's API prompt. Actual execution is determined by prompt IDs.
 */
public final class WorkflowNodeInventory {
    private WorkflowNodeInventory() {}

    public static final class Entry {
        public final String id, title, type, status, description;
        public final boolean availableLoRA, enabled, frontend;
        Entry(String id, String title, String type, String status, String description,
              boolean availableLoRA, boolean enabled, boolean frontend) {
            this.id = id; this.title = title; this.type = type; this.status = status;
            this.description = description; this.availableLoRA = availableLoRA;
            this.enabled = enabled; this.frontend = frontend;
        }
    }

    public static Set<String> optionalLoraIds(JSONObject original, JSONArray variants, String variantId) throws Exception {
        Graph g = new Graph(original, variants, variantId);
        Set<String> candidates = new LinkedHashSet<>();
        for (String id : g.ancestors) {
            JSONObject n = g.nodes.get(id);
            if (n != null && n.optInt("mode", 0) == 4 && isLoRA(n.optString("type", "")))
                candidates.add(id);
        }
        return candidates;
    }

    public static List<Entry> entries(JSONObject original, JSONArray variants, String variantId,
                                      JSONObject compiledPrompt, Set<String> enabledAuxImages) throws Exception {
        Graph g = new Graph(original, variants, variantId);
        Set<Integer> relevantGroups = new HashSet<>();
        relevantGroups.add(g.groupIndex);
        for (String id : g.ancestors) {
            JSONObject n = g.nodes.get(id);
            if (n != null) {
                for (int k = 0; k < g.groups.length(); k++)
                    if (inside(n, g.groups.optJSONObject(k))) relevantGroups.add(k);
            }
        }
        Set<String> relevantTitles = new HashSet<>();
        for (Integer k : relevantGroups) {
            JSONObject group = g.groups.optJSONObject(k);
            if (group != null) relevantTitles.add(normalize(group.optString("title", "")));
        }
        List<Entry> result = new ArrayList<>();
        for (JSONObject n : g.nodes.values()) {
            String id = String.valueOf(n.opt("id"));
            String type = n.optString("type", "");
            JSONObject prop = n.optJSONObject("properties");
            boolean bypassCtrl = type.contains("Fast Groups Bypasser");
            boolean front = bypassCtrl || isFrontend(type);
            boolean selectedArea = inside(n, g.groups.optJSONObject(g.groupIndex));
            boolean controllerForBranch = bypassCtrl && prop != null &&
                    relevantTitles.contains(normalize(prop.optString("matchTitle", "")));
            if (!g.ancestors.contains(id) && !selectedArea && !controllerForBranch) continue;
            String title = n.optString("title", "");
            if (title.isEmpty()) title = type;
            int mode = n.optInt("mode", 0);
            boolean running = compiledPrompt != null && compiledPrompt.has(id);
            boolean optLoRA = mode == 4 && isLoRA(type) && g.ancestors.contains(id);
            String status, description;
            if (bypassCtrl) {
                String match = prop == null ? "" : prop.optString("matchTitle", "");
                String target = normalize(match);
                boolean branchToggle = target.equals(normalize(g.groupTitle));
                boolean imageToggle = false;
                if (!branchToggle) {
                    for (String loaderId : g.ancestors) {
                        JSONObject loader = g.nodes.get(loaderId);
                        if (loader == null || !loader.optString("type", "").equals("LoadImage")) continue;
                        if (!isInTitleGroup(loader, g.groups, target)) continue;
                        imageToggle = true;
                        break;
                    }
                }
                status = "前端分组控制器";
                if (branchToggle) description = "当前子功能已由上方“合集功能”选择；此控制器不提交至 /prompt。";
                else if (imageToggle) {
                    boolean enabled = false;
                    for (String loaderId : g.ancestors) {
                        JSONObject loader = g.nodes.get(loaderId);
                        if (loader != null && "LoadImage".equals(loader.optString("type", "")) &&
                                isInTitleGroup(loader, g.groups, target) &&
                                enabledAuxImages != null && enabledAuxImages.contains(loaderId)) enabled = true;
                    }
                    description = "控制组“" + match + "”；可选参考图当前" + (enabled ? "启用" : "旁路") + "。请在“输入图片”里操作对应开关。";
                } else description = "控制组“" + match + "”；这是 ComfyUI 前端控件，不是后端执行节点。";
            } else if (isComparer(type)) {
                status = "前端图像对比";
                description = "此节点仅在 ComfyUI 网页端提供 A/B 对比操作；手机端本页显示结构信息，不作为 SaveImage 输出。";
            } else if (front) {
                status = "前端辅助节点";
                description = "用于画布预览、标签或其他前端操作；并非当前任务的可调执行参数。";
            } else if (running) {
                status = "正在执行";
                description = "存在于当前子功能编译后的 API /prompt；可在上方参数区域修改可编辑的输入。";
            } else if (mode == 4) {
                status = "旁路（不执行）";
                description = optLoRA ? "原工作流将此 LoRA 旁路；可以显式启用，成功编译后才参与生成。" :
                        "原工作流将此节点旁路；未出现在当前执行图中。";
            } else if (mode == 2) {
                status = "禁用（不执行）";
                description = "原工作流中处于禁用模式；不能把它误判为已运行。";
            } else {
                status = "不在当前执行路径";
                description = "结构中存在，但不是所选输出所需的可执行节点。";
            }
            result.add(new Entry(id, title, type, status, description, optLoRA, running, front));
        }
        return result;
    }

    private static boolean isLoRA(String t) {
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("loraloadermodelonly") || lower.equals("loraloader");
    }
    private static boolean isComparer(String t) { return t.toLowerCase(java.util.Locale.ROOT).contains("image comparer"); }
    private static boolean isFrontend(String t) {
        String l = t.toLowerCase(java.util.Locale.ROOT);
        return isComparer(t) || l.contains("fast groups bypasser") || l.contains("label (rgthree)") ||
                l.contains("note") || l.equals("markdownnote") || l.contains("image comparer") || l.equals("group");
    }
    private static String normalize(String s) {
        return (s == null ? "" : s).replaceFirst("^[▶▷\\s]+", "").trim().toLowerCase(java.util.Locale.ROOT);
    }
    private static boolean isInTitleGroup(JSONObject node, JSONArray groups, String title) {
        if (title.isEmpty()) return false;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null && normalize(group.optString("title", "")).equals(title) && inside(node, group)) return true;
        }
        return false;
    }
    private static boolean inside(JSONObject node, JSONObject group) {
        if (node == null || group == null) return false;
        JSONArray p = node.optJSONArray("pos"), b = group.optJSONArray("bounding");
        if (p == null || b == null || p.length() < 2 || b.length() < 4) return false;
        double x=p.optDouble(0), y=p.optDouble(1);
        return x >= b.optDouble(0) && y >= b.optDouble(1) &&
                x <= b.optDouble(0)+b.optDouble(2) && y <= b.optDouble(1)+b.optDouble(3);
    }

    private static final class Graph {
        final Map<String, JSONObject> nodes = new LinkedHashMap<>();
        final Set<String> ancestors = new LinkedHashSet<>();
        final JSONArray groups;
        final int groupIndex;
        final String groupTitle;
        Graph(JSONObject original, JSONArray variants, String variantId) throws Exception {
            JSONObject ui = original == null ? null : original.optJSONObject("workflow");
            if (ui == null || ui.optJSONArray("nodes") == null) ui = original;
            if (ui == null) throw new Exception("缺少原始合集数据");
            JSONArray list = ui.optJSONArray("nodes"), links = ui.optJSONArray("links");
            groups = ui.optJSONArray("groups");
            if (list == null || links == null || groups == null) throw new Exception("合集节点或连线数据不完整");
            JSONObject variant = null;
            if (variants != null) for (int i=0;i<variants.length();i++) {
                JSONObject v=variants.optJSONObject(i);
                if (v!=null && variantId.equals(v.optString("id", ""))) { variant=v; break; }
            }
            if (variant == null) throw new Exception("当前合集子功能不存在");
            groupIndex = variant.optInt("group_index", -1);
            JSONObject group = groups.optJSONObject(groupIndex);
            if (group == null) throw new Exception("子功能分组不存在");
            groupTitle = group.optString("title", "");
            for (int i=0;i<list.length();i++) {
                JSONObject n=list.optJSONObject(i);
                if (n!=null) nodes.put(String.valueOf(n.opt("id")), n);
            }
            Map<String,String> producers=new HashMap<>();
            for (int i=0;i<links.length();i++) {
                JSONArray a=links.optJSONArray(i);
                if (a!=null && a.length()>=5) producers.put(String.valueOf(a.opt(0)),String.valueOf(a.opt(1)));
                JSONObject o=links.optJSONObject(i);
                if (o!=null) producers.put(String.valueOf(o.opt("id")),o.optString("origin_id",o.optString("source_id", "")));
            }
            ArrayDeque<String> queue=new ArrayDeque<>();
            JSONArray roots=variant.optJSONArray("outputs");
            if (roots==null) throw new Exception("合集缺少功能输出");
            for (int i=0;i<roots.length();i++) queue.add(roots.optString(i,""));
            while(!queue.isEmpty()) {
                String id=queue.removeLast();
                if(!ancestors.add(id)) continue;
                JSONObject n=nodes.get(id);
                if(n==null) throw new Exception("依赖不存在的节点 " + id);
                JSONArray ins=n.optJSONArray("inputs");
                if(ins==null) continue;
                for(int k=0;k<ins.length();k++) {
                    JSONObject in=ins.optJSONObject(k);
                    if(in==null || in.isNull("link")) continue;
                    String source=producers.get(String.valueOf(in.opt("link")));
                    if(source==null) throw new Exception("节点 " + id + " 的连线不完整");
                    queue.add(source);
                }
            }
        }
    }
}
