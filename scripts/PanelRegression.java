package com.comfyremote.panel;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs real production classes on a JVM with an Android implementation jar.
 * GET /object_info is allowed; this harness never queues generation. */
public final class PanelRegression {
    private static int passed;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + (++passed) + " " + label);
    }
    private static JSONObject read(File f) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }
    private static final class TestContext extends ContextWrapper {
        private final File dir;
        private final Map<String, Object> values = new HashMap<>();
        private final SharedPreferences preferences;
        TestContext(File dir) {
            super(null); this.dir = dir;
            Object editor = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("put")) { values.put((String) args[0], args[1]); return proxy; }
                        if (name.equals("remove")) { values.remove(args[0]); return proxy; }
                        if (name.equals("clear")) { values.clear(); return proxy; }
                        return name.equals("commit") ? true : null;
                    });
            preferences = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("edit")) return editor;
                        if (name.equals("contains")) return values.containsKey(args[0]);
                        if (name.equals("getAll")) return new HashMap<>(values);
                        if (name.startsWith("get")) return values.getOrDefault(args[0], args[1]);
                        return null;
                    });
        }
        @Override public File getFilesDir() { return dir; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }

    private static JSONObject tiny(boolean sharedProcessing, boolean sharedLoader) throws Exception {
        JSONArray nodes = new JSONArray(), links = new JSONArray(), groups = new JSONArray();
        nodes.put(new JSONObject("{id:1,type:LoadImage,pos:[0,0],inputs:[],outputs:[{type:IMAGE}]}"));
        nodes.put(new JSONObject("{id:2,type:UNETLoader,pos:[0,0],inputs:[],outputs:[{type:MODEL}]}"));
        nodes.put(new JSONObject("{id:3,type:LoadImage,pos:[0,0],inputs:[],outputs:[{type:IMAGE}]}"));
        for (int i = 0; i < 2; i++) {
            int sampler = 10 + i, save = 20 + i, x = i * 1000;
            nodes.put(new JSONObject().put("id", sampler).put("type", "KSampler").put("pos", new JSONArray().put(x).put(20))
                    .put("inputs", new JSONArray().put(new JSONObject().put("name", "model").put("type", "MODEL").put("link", 100 + i))
                            .put(new JSONObject().put("name", "latent").put("type", "LATENT").put("link", 200 + i))));
            nodes.put(new JSONObject().put("id", save).put("type", "SaveImage").put("pos", new JSONArray().put(x).put(50))
                    .put("inputs", new JSONArray().put(new JSONObject().put("name", "images").put("type", "IMAGE").put("link", 300 + i))));
            links.put(new JSONArray().put(100 + i).put(2).put(0).put(sampler).put(0).put("MODEL"));
            links.put(new JSONArray().put(200 + i).put(sharedLoader ? 1 : 1 + 2 * i).put(0).put(sampler).put(1).put("LATENT"));
            links.put(new JSONArray().put(300 + i).put(sharedProcessing ? 10 : sampler).put(0).put(save).put(0).put("IMAGE"));
            groups.put(new JSONObject().put("title", "function " + i).put("bounding", new JSONArray().put(x).put(0).put(200).put(200)));
        }
        return new JSONObject().put("nodes", nodes).put("links", links).put("groups", groups);
    }

    public static void main(String[] args) throws Exception {
        File fixtures = new File(args[0]);
        JSONObject qwen = read(Objects.requireNonNull(fixtures.listFiles((d,n) -> n.startsWith("1-") && n.endsWith(".json")))[0]);
        JSONObject v45 = read(Objects.requireNonNull(fixtures.listFiles((d,n) -> n.startsWith("2-") && n.endsWith(".json")))[0]);
        String originalQwen = qwen.toString(), originalV45 = v45.toString();
        JSONArray variants = WorkflowVariants.detect(qwen);
        check(variants.length() == 4, "real Qwen retains all four independent functions");
        check(WorkflowVariants.detect(v45).length() == 0, "real V45 is a single complete workflow");
        check(originalQwen.equals(qwen.toString()) && originalV45.equals(v45.toString()), "detection preserves original JSON");
        check(WorkflowVariants.detect(new JSONObject().put("workflow", qwen)).length() == 4, "wrapped UI workflow supported");
        check(WorkflowVariants.detect(tiny(false, false)).length() == 2, "shared model alone permits independent functions");
        check(WorkflowVariants.detect(tiny(false, true)).length() == 2, "shared raw input alone permits independent functions");
        check(WorkflowVariants.detect(tiny(true, true)).length() == 0, "shared processing and diagnostic saves stay together");
        JSONObject broken = tiny(false, false); broken.getJSONArray("links").remove(0);
        check(WorkflowVariants.detect(broken).length() == 0, "incomplete graph is never split speculatively");

        File storeRoot = new File(args[1]); storeRoot.mkdirs();
        TestContext context = new TestContext(storeRoot);
        File profileDir = new File(storeRoot, "workflows_v18"); profileDir.mkdirs();
        String large = "x".repeat(1024 * 1024);
        for (int i = 0; i < 179; i++) {
            WorkflowProfile p = new WorkflowProfile("fixture-" + i, "workflow " + i, "{\"padding\":\"" + large + "\"}", originalV45,
                    i % 9 == 0, 1000 + i, 1000 + i, new JSONObject());
            p.folderId = "folder-" + (i % 7);
            Files.write(new File(profileDir, p.id + ".json").toPath(), p.toJson().toString().getBytes(StandardCharsets.UTF_8));
        }
        large = null;
        System.gc(); long start = System.nanoTime();
        List<WorkflowProfile> summaries = WorkflowStore.load(context);
        long cold = (System.nanoTime() - start) / 1_000_000;
        check(summaries.size() == 179, "179 large profiles indexed under a bounded JVM heap");
        check(summaries.stream().allMatch(p -> p.metadataOnly && p.uiJson == null && p.promptJson.equals("{}")), "list retains metadata only");
        start = System.nanoTime(); summaries = WorkflowStore.load(context);
        long warm = (System.nanoTime() - start) / 1_000_000;
        WorkflowProfile summary = summaries.get(0);
        String bodyBefore = read(summary.backingFile).getString("prompt_json");
        summary.name = "renamed"; summary.favorite = !summary.favorite; summary.folderId = "moved";
        WorkflowStore.upsert(context, summary);
        WorkflowProfile reopened = WorkflowStore.find(WorkflowStore.load(context), summary.id);
        check(reopened != null && reopened.name.equals("renamed") && reopened.folderId.equals("moved"), "metadata actions persist");
        check(reopened.promptJson.equals(bodyBefore) && reopened.uiJson.equals(originalV45), "metadata edits preserve full prompt and UI");
        check(summary.metadataOnly && summary.uiJson == null, "metadata save does not retain a full body in the list");
        WorkflowProfile restored = WorkflowProfile.fromJson(reopened.toJson().put("future_metadata", "retained"));
        WorkflowStore.upsert(context, restored);
        check(read(new File(profileDir, restored.id + ".json")).optString("future_metadata").equals("retained"), "unknown profile fields survive save and upgrade");
        File cache = new File(profileDir, "index_v274/" + restored.id + ".json");
        Files.write(cache.toPath(), "invalid cache".getBytes(StandardCharsets.UTF_8));
        java.lang.reflect.Field memory = WorkflowIndex.class.getDeclaredField("memory");
        memory.setAccessible(true); ((Map<?, ?>) memory.get(null)).clear();
        check(WorkflowStore.load(context).size() == 179, "corrupt metadata cache rebuilt without losing profiles");
        System.out.println("BENCHMARK profiles=179 body_per_profile>1MiB cold_ms=" + cold + " warm_ms=" + warm);

        if (args.length > 2) {
            String server = args[2];
            for (int i = 0; i < variants.length(); i++) {
                JSONObject v = variants.getJSONObject(i);
                JSONObject prompt = WorkflowVariants.compile(qwen, variants, v.getString("id"), server);
                check(prompt.has(v.getJSONArray("outputs").getString(0)), "Qwen live-schema compilation function " + i);
            }
            WorkflowProfile legacy = WorkflowProfile.create("legacy V45", new JSONObject("{\"oldPartial\":{\"class_type\":\"SaveImage\",\"inputs\":{}}}"), v45);
            legacy.collectionDetectionVersion = 0;
            legacy.variants = new JSONArray("[{id:'group:5',outputs:['254','50002']},{id:'group:8',outputs:['50003','50005']}]");
            legacy.activeVariantId = "group:5";
            legacy.inputUris.put("4500|image", "content://preserved-input");
            legacy.inputMaskPaths.put("4500|image", "/saved-mask.png");
            legacy.overrides.put("1307|steps", 9);
            WorkflowStore.upsert(context, legacy);
            byte[] before = Files.readAllBytes(new File(profileDir, legacy.id + ".json").toPath());
            WorkflowProfile repaired = WorkflowStore.open(context, legacy.id, server);
            check(!repaired.isCollection(), "legacy V45 collection repaired automatically on open");
            JSONObject prompt = repaired.promptObject();
            check(prompt.has("254") && prompt.has("50002") && prompt.has("50003") && prompt.has("50005"), "all four V45 output nodes restored");
            check(repaired.inputUris.optString("4500|image").equals("content://preserved-input") &&
                    repaired.inputMaskPaths.optString("4500|image").equals("/saved-mask.png") && repaired.overrides.optInt("1307|steps") == 9,
                    "repair preserves input, mask and parameters");
            check(Arrays.equals(before, Files.readAllBytes(new File(profileDir, "backup_before_v274/" + legacy.id + ".json").toPath())),
                    "repair saves byte-identical recovery copy");
            for (Iterator<String> it = prompt.keys(); it.hasNext();) {
                String id = it.next(); JSONObject inputs = prompt.getJSONObject(id).optJSONObject("inputs");
                if (inputs == null) continue;
                for (Iterator<String> keys = inputs.keys(); keys.hasNext();) {
                    Object value = inputs.opt(keys.next());
                    if (value instanceof JSONArray) {
                        JSONArray link = (JSONArray) value;
                        if (link.length() == 2 && link.opt(0) instanceof String && link.opt(1) instanceof Number)
                            if (!prompt.has(link.optString(0))) throw new AssertionError("dangling reference " + id + " -> " + link);
                    }
                }
            }
            check(true, "repaired V45 API graph has no dangling node references");
            WorkflowProfile legacyQwen = WorkflowProfile.create("legacy Qwen", new JSONObject("{\"keep\":{\"class_type\":\"SaveImage\",\"inputs\":{}}}"), qwen);
            legacyQwen.collectionDetectionVersion = 0;
            legacyQwen.variants = variants; legacyQwen.activeVariantId = variants.getJSONObject(0).getString("id");
            legacyQwen.enabledAuxImages.put("optional-image", true); legacyQwen.enabledAuxLoras.put("optional-lora", true);
            WorkflowStore.upsert(context, legacyQwen);
            WorkflowProfile upgradedQwen = WorkflowStore.open(context, legacyQwen.id, "http://127.0.0.1:1");
            check(upgradedQwen.isCollection() && upgradedQwen.variants.length() == 4 && upgradedQwen.promptJson.equals(legacyQwen.promptJson),
                    "unchanged legacy Qwen upgrades offline without replacing compiled prompt");
            check(upgradedQwen.isAuxImageEnabled("optional-image") && upgradedQwen.isAuxLoRAEnabled("optional-lora"), "Qwen auxiliary image and LoRA choices preserved");
            WorkflowProfile failing = WorkflowProfile.create("offline repair", new JSONObject("{}"), v45);
            failing.collectionDetectionVersion = 0; failing.variants = legacy.variants;
            failing.variants = new JSONArray("[{id:'group:5'},{id:'group:8'}]");
            WorkflowStore.upsert(context, failing);
            File failingFile = new File(profileDir, failing.id + ".json");
            byte[] unchanged = Files.readAllBytes(failingFile.toPath()); boolean failed = false;
            try { WorkflowStore.open(context, failing.id, "http://127.0.0.1:1"); } catch (Exception expected) { failed = true; }
            check(failed && Arrays.equals(unchanged, Files.readAllBytes(failingFile.toPath())), "failed offline repair leaves original file byte-identical");
        }
        System.out.println("ALL PASS " + passed);
    }
}
