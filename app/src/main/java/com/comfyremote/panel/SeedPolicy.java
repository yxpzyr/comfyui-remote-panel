package com.comfyremote.panel;

import org.json.JSONObject;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** -1 is an app-side policy. Each queued job freezes real seeds before its first HTTP submission. */
final class SeedPolicy {
    static final long MAX_SAFE_SEED = 9007199254740991L;
    private SeedPolicy() {}
    static boolean isSeed(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.equals("seed") || n.endsWith("_seed") || n.equals("种子") || n.equals("随机种子");
    }
    static long roll(double minimum, double maximum) {
        long min = Double.isNaN(minimum) ? 0 : Math.max(0, (long) Math.ceil(minimum));
        long max = Double.isNaN(maximum) ? MAX_SAFE_SEED : Math.min(MAX_SAFE_SEED, (long) Math.floor(maximum));
        if (max < min) throw new IllegalArgumentException("种子范围无效");
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }
    static long roll(String type, String name, JSONObject info) {
        JSONObject meta = metadata(info, type, name);
        if (meta == null) throw new IllegalArgumentException("无法读取该种子的取值范围，请连接 ComfyUI 后重试");
        return roll(meta.optDouble("min", 0), meta.optDouble("max", MAX_SAFE_SEED));
    }
    static boolean needsResolution(JSONObject prompt) {
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            JSONObject node = prompt.optJSONObject(ids.next()), inputs = node == null ? null : node.optJSONObject("inputs");
            if (inputs == null) continue;
            Iterator<String> names = inputs.keys();
            while (names.hasNext()) {
                String name = names.next(); Object value = inputs.opt(name);
                if (isSeed(name) && value instanceof Number && ((Number) value).longValue() == -1) return true;
            }
        }
        return false;
    }
    static JSONObject resolve(JSONObject prompt, JSONObject objectInfo) throws Exception {
        JSONObject seeds = new JSONObject();
        java.util.List<SeedField> fields = new java.util.ArrayList<>();
        java.util.Map<String, double[]> bounds = new java.util.LinkedHashMap<>();
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next(); JSONObject node = prompt.optJSONObject(id);
            JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
            if (inputs == null) continue;
            Iterator<String> names = inputs.keys();
            while (names.hasNext()) {
                String name = names.next(); Object value = inputs.opt(name);
                if (!isSeed(name) || !(value instanceof Number)) continue;
                long seed = ((Number) value).longValue();
                JSONObject metaNode = node.optJSONObject("_meta");
                JSONObject sources = metaNode == null ? null : metaNode.optJSONObject("panel_seed_sources");
                String source = sources == null ? "" : sources.optString(name);
                String group = source.isEmpty() ? id + "." + name : "source:" + source;
                if (seed == -1) {
                    JSONObject meta = metadata(objectInfo, node.optString("class_type"), name);
                    if (meta == null) throw new IllegalArgumentException("无法读取 " + node.optString("class_type") + " 的种子范围，请连接 ComfyUI 后重试");
                    double min = Math.max(0, meta.optDouble("min", 0)), max = meta.optDouble("max", MAX_SAFE_SEED);
                    double[] shared = bounds.get(group);
                    if (shared == null) bounds.put(group, new double[]{min, max});
                    else { shared[0] = Math.max(shared[0], min); shared[1] = Math.min(shared[1], max); }
                } else if (seed < -1) throw new IllegalArgumentException("随机种子仅支持 -1，或非负固定数值");
                fields.add(new SeedField(id, name, group, inputs, seed));
            }
        }
        java.util.Map<String, Long> random = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, double[]> entry : bounds.entrySet())
            random.put(entry.getKey(), roll(entry.getValue()[0], entry.getValue()[1]));
        for (SeedField field : fields) {
            long seed = field.seed == -1 ? random.get(field.group) : field.seed;
            if (field.seed == -1) field.inputs.put(field.name, seed);
            seeds.put(field.id + "." + field.name, seed);
        }
        return seeds;
    }
    private static final class SeedField {
        final String id, name, group; final JSONObject inputs; final long seed;
        SeedField(String id, String name, String group, JSONObject inputs, long seed) {
            this.id=id; this.name=name; this.group=group; this.inputs=inputs; this.seed=seed;
        }
    }
    private static JSONObject metadata(JSONObject info, String type, String name) {
        JSONObject def = info == null ? null : info.optJSONObject(type), input = def == null ? null : def.optJSONObject("input");
        if (input == null) return null;
        for (String group : new String[]{"required", "optional"}) {
            JSONObject fields = input.optJSONObject(group);
            org.json.JSONArray spec = fields == null ? null : fields.optJSONArray(name);
            if (spec != null && "INT".equalsIgnoreCase(spec.optString(0)))
                return spec.optJSONObject(1) == null ? new JSONObject() : spec.optJSONObject(1);
        }
        return null;
    }
}
