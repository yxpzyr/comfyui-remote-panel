package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Phone storyboard data and the corresponding official Director API graph. */
public final class H3DirectorPlan {
    public static final String EXPORT_TYPE = "ComfyRemoteH3VideoOutput";
    public static final String EXPORT_ID = "comfyremote_h3_video";
    private H3DirectorPlan() {}

    public static boolean hasDirector(JSONObject prompt) {
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            JSONObject node = prompt.optJSONObject(ids.next());
            String type = node == null ? "" : node.optString("class_type");
            if ("MiniMaxH3Director".equals(type) || "ComfyMiniMaxH3Director".equals(type)) return true;
        }
        return false;
    }

    public static String directorId(JSONObject prompt) throws Exception {
        String found = null;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            String type = node == null ? "" : node.optString("class_type");
            if (!"MiniMaxH3Director".equals(type) && !"ComfyMiniMaxH3Director".equals(type)) continue;
            if (found != null) throw new Exception("请使用只有一个导演台节点的手机工作流");
            found = id;
        }
        if (found == null) throw new Exception("请先导入配套的 H3 导演台手机工作流");
        return found;
    }

    public static String mode(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        if (value.startsWith("r2v")) return "r2v";
        if (value.startsWith("fl2v") || value.startsWith("i2v")) return "fl2v";
        if (value.startsWith("t2v")) return "t2v";
        return "";
    }

    public static JSONObject newShot() throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("prompt", "")
                .put("seconds", 10).put("continuity", true).put("first", new JSONObject())
                .put("last", new JSONObject()).put("images", new JSONArray());
    }

    public static JSONObject fromPrompt(JSONObject prompt, String projectKey) throws Exception {
        JSONObject inputs = prompt.getJSONObject(directorId(prompt)).getJSONObject("inputs");
        for (String port : new String[]{"i2v_groups", "r2v_groups", "refine", "selflift", "face_refine", "semantic_bridge"})
            if (inputs.opt(port) instanceof JSONArray) throw new Exception("手机版暂不编辑外接分组、二采或语义桥，请使用配套手机模板");
        String task = mode(inputs.optString("task_type", "t2v"));
        if (task.isEmpty()) throw new Exception("手机版先支持文生、首帧和图片参考三种模式");
        String raw = inputs.optString("timeline_data", "");
        JSONObject timeline = raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
        rejectMediaFiles(timeline);
        JSONObject global = timeline.optJSONObject("global");
        if (global == null) global = new JSONObject();
        boolean shared = global.optBoolean("commonEnabled", false) || "global".equals(timeline.optString("editMode"));
        JSONObject output = timeline.optJSONObject("output");
        if (output == null) output = new JSONObject();
        double fps = inputs.optDouble("frame_rate", 24);
        JSONArray source = timeline.optJSONArray("fl2v".equals(task) ? "shots" : "segments");
        if (source == null || source.length() == 0) source = new JSONArray().put(new JSONObject()
                .put("length", inputs.optInt("total_frames", 243)).put("prompt", inputs.optString("global_prompt")));
        JSONArray shots = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i), shot = newShot();
            shot.put("id", item.optString("id", shot.getString("id")));
            shot.put("prompt", shared && "global".equals(timeline.optString("editMode")) ? "" : item.optString("prompt"));
            shot.put("seconds", item.has("durationSec") ? item.optDouble("durationSec")
                    : item.optInt("frameCount", item.optInt("length", 243)) / fps);
            shot.put("continuity", item.optBoolean("continuityFromPrev", true));
            shot.put("first", image(item.opt("startImage")));
            shot.put("last", image(item.opt("endImage")));
            shot.put("images", indexedImages(item.optJSONArray("refs")));
            shots.put(shot);
        }
        return new JSONObject().put("mode", task).put("shots", shots)
                .put("common_prompt", shared ? global.optString("prompt", inputs.optString("global_prompt")) : "")
                .put("images", shared ? indexedImages(global.optJSONArray("refs")) : new JSONArray())
                .put("continuity", output.optBoolean("continuityEnabled", true))
                .put("width", inputs.optInt("width", 864)).put("height", inputs.optInt("height", 480))
                .put("seed", inputs.optLong("seed", -1)).put("steps", inputs.optInt("steps", "r2v".equals(task) ? 4 : 8))
                .put("cache", "mobile_h3_" + UUID.nameUUIDFromBytes(projectKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString().replace("-", "").substring(0, 24));
    }

    private static void rejectMediaFiles(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (("videoFile".equals(key) || "audioFile".equals(key)) && !object.optString(key).isEmpty())
                    throw new Exception("手机版当前支持图片参考；原工作流含参考视频或音频，请先在电脑端处理");
                rejectMediaFiles(object.opt(key));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) rejectMediaFiles(array.opt(i));
        }
    }

    private static JSONObject image(Object raw) throws Exception {
        if (raw instanceof JSONObject) return new JSONObject(raw.toString());
        return raw instanceof String && !((String) raw).isEmpty()
                ? new JSONObject().put("imageFile", raw) : new JSONObject();
    }

    private static JSONArray indexedImages(JSONArray refs) throws Exception {
        JSONArray images = new JSONArray();
        if (refs != null) for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.optJSONObject(i);
            if (ref == null) continue;
            int index = ref.optInt("index", i);
            if (index < 0 || index >= 9) throw new Exception("参考图编号必须在图片1至图片9内");
            while (images.length() <= index) images.put(new JSONObject());
            images.put(index, image(ref));
        }
        return images;
    }

    public static JSONObject shot(JSONObject draft, String id) throws Exception {
        JSONArray shots = draft.getJSONArray("shots");
        for (int i = 0; i < shots.length(); i++) if (id.equals(shots.getJSONObject(i).optString("id"))) return shots.getJSONObject(i);
        throw new Exception("该分镜已被删除，请重新选择图片位置");
    }

    public static JSONObject imageSlot(JSONObject draft, String shotId, String role, int index) throws Exception {
        JSONObject owner = shotId == null ? draft : shot(draft, shotId);
        if ("first".equals(role) || "last".equals(role)) {
            JSONObject slot = owner.optJSONObject(role);
            if (slot == null) { slot = new JSONObject(); owner.put(role, slot); }
            return slot;
        }
        JSONArray images = owner.optJSONArray("images");
        if (images == null) { images = new JSONArray(); owner.put("images", images); }
        if (index < 0 || index >= 9) throw new Exception("最多支持9张参考图片");
        while (images.length() <= index) images.put(new JSONObject());
        return images.getJSONObject(index);
    }

    public static void selectImage(JSONObject slot, String uri) throws Exception {
        slot.remove("imageFile"); slot.remove("imageB64"); slot.remove("fileName");
        slot.put("localUri", uri);
    }

    public static void clearImage(JSONObject slot) {
        Iterator<String> keys = slot.keys();
        Set<String> all = new HashSet<>(); while (keys.hasNext()) all.add(keys.next());
        for (String key : all) slot.remove(key);
    }

    public static Set<String> localUris(JSONObject draft) throws Exception {
        Set<String> uris = new java.util.LinkedHashSet<>();
        String task = draft.getString("mode");
        if ("r2v".equals(task)) collectImages(draft.optJSONArray("images"), uris);
        JSONArray shots = draft.getJSONArray("shots");
        for (int i = 0; i < shots.length(); i++) {
            JSONObject shot = shots.getJSONObject(i);
            if ("r2v".equals(task)) collectImages(shot.optJSONArray("images"), uris);
            if ("fl2v".equals(task)) for (String role : new String[]{"first", "last"}) {
                JSONObject media = shot.optJSONObject(role);
                if (media != null && !media.optString("localUri").isEmpty()) uris.add(media.getString("localUri"));
            }
        }
        return uris;
    }

    private static void collectImages(JSONArray images, Set<String> uris) {
        if (images != null) for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image != null && !image.optString("localUri").isEmpty()) uris.add(image.optString("localUri"));
        }
    }

    private static JSONObject uploadedImage(JSONObject slot, Map<String, String> uploads) throws Exception {
        if (slot == null) return new JSONObject();
        String uri = slot.optString("localUri");
        String file = uri.isEmpty() ? slot.optString("imageFile") : uploads.get(uri);
        if (!uri.isEmpty() && (file == null || file.isEmpty())) throw new Exception("参考图尚未上传完成，请重新选择或重试");
        return file == null || file.isEmpty() ? new JSONObject() : new JSONObject().put("imageFile", file);
    }

    private static JSONArray uploadedRefs(JSONArray images, Map<String, String> uploads) throws Exception {
        JSONArray refs = new JSONArray();
        if (images != null) for (int i = 0; i < images.length(); i++) {
            JSONObject ref = uploadedImage(images.optJSONObject(i), uploads);
            if (ref.has("imageFile")) refs.put(ref.put("index", i));
        }
        return refs;
    }

    public static int alignedFrames(double seconds) throws Exception {
        if (!Double.isFinite(seconds) || seconds <= 0) throw new Exception("分镜时长必须大于0秒");
        long frames = Math.max(5, Math.round(seconds * 24));
        frames += (5 - frames % 17 + 17) % 17;
        if (frames > 100000) throw new Exception("分镜帧数超过导演台支持的范围");
        return (int) frames;
    }

    private static String chooseFile(JSONObject schemas, String type, String input, String current, String wanted) throws Exception {
        JSONArray choices = schemas.getJSONObject(type).getJSONObject("input").getJSONObject("required").getJSONArray(input).getJSONArray(0);
        for (int i = 0; i < choices.length(); i++) if (choices.getString(i).equals(current) && current.toLowerCase(Locale.ROOT).contains(wanted)) return current;
        for (int i = 0; i < choices.length(); i++) if (choices.getString(i).toLowerCase(Locale.ROOT).contains(wanted)) return choices.getString(i);
        throw new Exception("电脑端没有找到匹配文件：" + wanted + "。请检查模型与LoRA是否安装完整。");
    }

    public static JSONObject compile(JSONObject original, JSONObject draft, Map<String, String> uploads, JSONObject schemas, String requestId) throws Exception {
        String id = directorId(original), task = draft.getString("mode");
        if (!("t2v".equals(task) || "fl2v".equals(task) || "r2v".equals(task))) throw new Exception("不支持的导演台模式");
        JSONObject prompt = new JSONObject(original.toString());
        JSONObject inputs = prompt.getJSONObject(id).getJSONObject("inputs");
        Set<String> keep = new HashSet<>(); collectUpstream(prompt, id, keep);
        Set<String> remove = new HashSet<>(); Iterator<String> all = prompt.keys();
        while (all.hasNext()) { String key = all.next(); if (!keep.contains(key)) remove.add(key); }
        for (String key : remove) prompt.remove(key);
        String modelId = inputs.getJSONArray("model").getString(0);
        String unetId = null, loraId = null;
        Set<String> seen = new HashSet<>();
        while (seen.add(modelId)) {
            JSONObject node = prompt.getJSONObject(modelId);
            String type = node.getString("class_type");
            if ("UNETLoader".equals(type)) { unetId = modelId; break; }
            if ("LoraLoaderModelOnly".equals(type)) {
                if (loraId != null) throw new Exception("请使用只有一个加速LoRA的手机模板");
                loraId = modelId;
            }
            JSONArray link = node.getJSONObject("inputs").optJSONArray("model");
            if (link == null) break;
            modelId = link.getString(0);
        }
        if (unetId == null || loraId == null) throw new Exception("请使用带 UNETLoader 和 LoRA模型加载器的手机导演台模板");
        boolean reference = "r2v".equals(task);
        JSONArray taskChoices = schemas.getJSONObject("MiniMaxH3Director").getJSONObject("input")
                .getJSONObject("required").getJSONArray("task_type").getJSONArray(0);
        String taskLabel = null;
        for (int i = 0; i < taskChoices.length(); i++) {
            String choice = taskChoices.getString(i);
            if (choice.equals(task) || choice.startsWith(task + " ")) { taskLabel = choice; break; }
        }
        if (taskLabel == null) throw new Exception("电脑导演台没有此模式：" + task);
        JSONObject unet = prompt.getJSONObject(unetId).getJSONObject("inputs");
        JSONObject lora = prompt.getJSONObject(loraId).getJSONObject("inputs");
        unet.put("unet_name", chooseFile(schemas, "UNETLoader", "unet_name", unet.optString("unet_name"),
                "minimax_h3_" + (reference ? "ref2va" : "fl2va") + "_pruned_int8_convrot"));
        lora.put("lora_name", chooseFile(schemas, "LoraLoaderModelOnly", "lora_name", lora.optString("lora_name"),
                reference ? "minimax_h3_ref2v_turbo_4step_v0.1" : "minimax_h3_fl2v_turbo_8step_v1.0"));
        lora.put("strength_model", 1.0);
        int width = draft.getInt("width"), height = draft.getInt("height");
        if (width < 32 || height < 32 || width > 8192 || height > 8192 || width % 32 != 0 || height % 32 != 0) throw new Exception("H3宽高需为32的倍数，范围32至8192");
        int steps = draft.getInt("steps");
        long seed = draft.getLong("seed");
        if (steps < 1 || steps > 200) throw new Exception("采样步数需在1至200之间");
        if (seed < -1) throw new Exception("种子请填写-1或非负整数");
        JSONArray shots = draft.getJSONArray("shots");
        if (shots.length() == 0) throw new Exception("请至少添加一个分镜");
        JSONArray sharedRefs = reference ? uploadedRefs(draft.optJSONArray("images"), uploads) : new JSONArray();
        JSONArray segments = new JSONArray(); int total = 0;
        for (int i = 0; i < shots.length(); i++) {
            JSONObject shot = shots.getJSONObject(i);
            int frames = alignedFrames(shot.getDouble("seconds"));
            JSONObject item = new JSONObject().put("id", shot.getString("id")).put("start", total)
                    .put("length", frames).put("frameCount", frames).put("durationSec", shot.getDouble("seconds"))
                    .put("prompt", shot.optString("prompt")).put("taskType", task)
                    .put("continuityFromPrev", i > 0 && shot.optBoolean("continuity", true));
            if (reference) {
                JSONArray refs = uploadedRefs(shot.optJSONArray("images"), uploads);
                if (sharedRefs.length() == 0 && refs.length() == 0) throw new Exception("分镜" + (i + 1) + "缺少参考图，请选择公共参考图或本段参考图");
                item.put("refs", refs);
            } else if ("fl2v".equals(task)) {
                JSONObject first = uploadedImage(shot.optJSONObject("first"), uploads), last = uploadedImage(shot.optJSONObject("last"), uploads);
                if (i == 0 && first.length() == 0 && last.length() == 0) throw new Exception("首帧模式请先为第1段选择首帧或尾帧");
                item.put("startImage", first).put("endImage", last);
            }
            if (shot.optString("prompt").trim().isEmpty() && draft.optString("common_prompt").trim().isEmpty()) throw new Exception("请填写分镜" + (i + 1) + "的提示词");
            segments.put(item); total += frames;
        }
        if (total > 100000) throw new Exception("总帧数超过导演台支持的100000帧");
        JSONObject timeline = new JSONObject().put("version", 4).put("editMode", "segment")
                .put("timelineMode", "fl2v".equals(task) ? "fl2v" : "prompt_batch")
                .put("totalFrames", total).put("frameRate", 24).put("width", width).put("height", height)
                .put("refMaxSize", Math.max(width, height)).put("runSelectEnabled", false)
                .put("global", new JSONObject().put("taskType", task).put("prompt", draft.optString("common_prompt"))
                        .put("commonEnabled", true).put("refs", sharedRefs).put("referenceVideo", new JSONObject()))
                .put("output", new JSONObject().put("mode", "fixed").put("width", width).put("height", height)
                        .put("longEdge", Math.max(width, height)).put("exportMode", "segments").put("audioMode", "generate")
                        .put("refImageSize", "match").put("continuityEnabled", draft.optBoolean("continuity", true))
                        .put("continuityOverlapFrames", 22).put("continuityMode", "guide").put("continuityKeepTail", false));
        timeline.put("fl2v".equals(task) ? "shots" : "segments", segments);
        // FL2V has no shared prompt merger: materialize it in each shot once.
        if ("fl2v".equals(task) && !draft.optString("common_prompt").isEmpty()) for (int i = 0; i < segments.length(); i++) {
            JSONObject item = segments.getJSONObject(i);
            item.put("prompt", draft.optString("common_prompt") + "\n" + item.optString("prompt"));
        }
        inputs.put("task_type", taskLabel).put("global_prompt", draft.optString("common_prompt"))
                .put("timeline_data", timeline.toString()).put("total_frames", total).put("width", width).put("height", height)
                .put("ref_max_size", Math.max(width, height)).put("frame_rate", 24).put("seed", draft.optLong("seed", -1))
                .put("steps", steps).put("sampler", "euler").put("scheduler", "simple")
                .put("cfg", 1.0).put("clear_vram_between_segments", true).put("cache_frames_codec", "ffv1")
                .put("export_source_images", false).put("export_pre_face_refine", false).put("cache_name", draft.getString("cache"));
        prompt.put(EXPORT_ID, new JSONObject().put("class_type", EXPORT_TYPE).put("inputs", new JSONObject()
                .put("report", new JSONArray().put(id).put(5)).put("expected_segments", shots.length())
                .put("frame_rate", 24).put("request_id", requestId)));
        return prompt;
    }

    private static void collectUpstream(JSONObject prompt, String id, Set<String> keep) throws Exception {
        if (!keep.add(id)) return;
        JSONObject node = prompt.optJSONObject(id);
        if (node == null) throw new Exception("缺少接线节点 " + id);
        JSONObject inputs = node.getJSONObject("inputs"); Iterator<String> names = inputs.keys();
        while (names.hasNext()) {
            JSONArray link = inputs.optJSONArray(names.next());
            if (link != null && link.length() == 2 && prompt.has(link.optString(0))) collectUpstream(prompt, link.getString(0), keep);
        }
    }
}
