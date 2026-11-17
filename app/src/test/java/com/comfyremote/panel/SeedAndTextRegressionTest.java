package com.comfyremote.panel;

import android.app.Application;
import android.content.ClipboardManager;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import okhttp3.mockwebserver.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SeedAndTextRegressionTest {
    @Before public void before() { WorkflowUiConverter.clearObjectInfoCache(); }
    private Application app() { return RuntimeEnvironment.getApplication(); }
    private JSONObject resource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(in); return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private JSONObject samplerInfo(long max) throws Exception {
        return new JSONObject().put("TestSeedSampler", new JSONObject().put("output_node", false)
                .put("input", new JSONObject().put("required", new JSONObject()
                        .put("seed", new JSONArray().put("INT").put(new JSONObject().put("min", 0).put("max", max).put("default", 123)))))
                .put("output", new JSONArray().put("STRING")));
    }
    private JSONObject sampler(long seed) throws Exception {
        return new JSONObject().put("1", new JSONObject().put("class_type", "TestSeedSampler")
                .put("inputs", new JSONObject().put("seed", seed)));
    }
    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private Button button(android.view.View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { Button found = button(group.getChildAt(i), text); if (found != null) return found; }
        }
        return null;
    }
    @Test public void minusOneResolvesPerJobAndNeverRerollsAnExistingSnapshot() throws Exception {
        JSONObject source = sampler(-1), info = samplerInfo(SeedPolicy.MAX_SAFE_SEED);
        Set<Long> generated = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            JSONObject submission = new JSONObject(source.toString());
            JSONObject seeds = SeedPolicy.resolve(submission, info);
            long value = seeds.getLong("1.seed");
            assertTrue(value >= 0 && value <= SeedPolicy.MAX_SAFE_SEED); generated.add(value);
            assertFalse(SeedPolicy.needsResolution(submission));
            assertEquals(value, SeedPolicy.resolve(submission, null).getLong("1.seed"));
        }
        assertTrue(generated.size() > 1);
        assertEquals(-1, source.getJSONObject("1").getJSONObject("inputs").getLong("seed"));
    }
    @Test public void fixedZeroAndLargeSeedsAndUnrelatedNegativeFieldsArePreserved() throws Exception {
        for (long seed : new long[]{0, 123, 323247156445767L}) {
            JSONObject prompt = sampler(seed);
            prompt.getJSONObject("1").getJSONObject("inputs").put("GPU层数", -1);
            assertEquals(seed, SeedPolicy.resolve(prompt, null).getLong("1.seed"));
            assertEquals(-1, prompt.getJSONObject("1").getJSONObject("inputs").getInt("GPU层数"));
        }
        try { SeedPolicy.resolve(sampler(-2), null); fail(); } catch (IllegalArgumentException expected) {}
        try { SeedPolicy.resolve(sampler(-1), null); fail("A missing schema must not send -1"); } catch (IllegalArgumentException expected) {}
    }
    @Test public void diceHonorsRangesIncludingSingleValueAndSharedSourcesIntersectRanges() throws Exception {
        for (int i = 0; i < 100; i++) { long value = SeedPolicy.roll(5, 9); assertTrue(value >= 5 && value <= 9); }
        assertEquals(7, SeedPolicy.roll(7, 7));
        JSONObject prompt = sampler(-1);
        prompt.getJSONObject("1").put("_meta", new JSONObject().put("panel_seed_sources", new JSONObject().put("seed", "rgthree:186")));
        JSONObject second = new JSONObject(prompt.getJSONObject("1").toString());
        second.put("class_type", "SmallSampler"); prompt.put("2", second);
        JSONObject info = samplerInfo(100);
        info.put("SmallSampler", new JSONObject(samplerInfo(3).getJSONObject("TestSeedSampler").toString()));
        JSONObject seeds = SeedPolicy.resolve(prompt, info);
        assertEquals(seeds.getLong("1.seed"), seeds.getLong("2.seed"));
        assertTrue(seeds.getLong("1.seed") <= 3);
    }
    @Test public void importedRgthreeMinusOneSurvivesConversionAndKeepsSharedIdentity() throws Exception {
        JSONObject ui = new JSONObject().put("nodes", new JSONArray()
                .put(new JSONObject().put("id", 1).put("type", "Seed (rgthree)").put("mode", 0).put("widgets_values", new JSONArray().put(-1)))
                .put(new JSONObject().put("id", 2).put("type", "TestSeedSampler").put("mode", 0).put("widgets_values", new JSONArray())
                        .put("inputs", new JSONArray().put(new JSONObject().put("name", "seed").put("link", 1))))
                .put(new JSONObject().put("id", 3).put("type", "TestSeedSampler").put("mode", 0).put("widgets_values", new JSONArray())
                        .put("inputs", new JSONArray().put(new JSONObject().put("name", "seed").put("link", 2)))))
                .put("links", new JSONArray().put(new JSONArray("[1,1,0,2,0,\"INT\"]")).put(new JSONArray("[2,1,0,3,0,\"INT\"]")));
        try (MockWebServer server = new MockWebServer()) {
            JSONObject info = samplerInfo(100000);
            server.enqueue(new MockResponse().setBody(info.toString())); server.start();
            JSONObject prompt = WorkflowUiConverter.toApiPrompt(ui, server.url("/").toString());
            assertEquals(-1, prompt.getJSONObject("2").getJSONObject("inputs").getLong("seed"));
            JSONObject seeds = SeedPolicy.resolve(prompt, info);
            assertEquals(seeds.getLong("2.seed"), seeds.getLong("3.seed"));
        }
    }
    @Test public void resolvedPayloadAndActualSeedsSurviveStorageWithoutChangingPreset() throws Exception {
        JobRecord job = JobRecord.create("wf", "seed test", "", "", "8188", sampler(-1).toString());
        JobStore.add(app(), job);
        JSONObject submission = new JSONObject(JobStore.loadSubmission(app(), job.localId).promptJson);
        JSONObject seeds = SeedPolicy.resolve(submission, samplerInfo(SeedPolicy.MAX_SAFE_SEED));
        JobStore.freezeSubmission(app(), job.localId, submission, seeds);
        assertEquals(seeds.toString(), JobStore.find(app(), job.localId).resolvedSeedsJson);
        JSONObject restored = new JSONObject(JobStore.loadSubmission(app(), job.localId).promptJson);
        assertEquals(seeds.getLong("1.seed"), restored.getJSONObject("1").getJSONObject("inputs").getLong("seed"));
        JobStore.updateProgress(app(), job.localId, 50, "1", "running");
        assertEquals(restored.toString(), JobStore.loadSubmission(app(), job.localId).promptJson);
    }
    @Test public void actualMinimaxStructureDiscoversFifthTextBranchAndCompilesOnlyItsDependencies() throws Exception {
        JSONObject ui = resource("minimax-text-sanitized.json"), info = resource("minimax-text-schema.json");
        JSONArray variants = WorkflowVariants.detect(ui, info);
        assertEquals(5, variants.length()); assertEquals("提示词增强-1", WorkflowVariants.label(variants, "group:2"));
        assertEquals("239", variants.getJSONObject(2).getJSONArray("outputs").getString(0));
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(info.toString())); server.start();
            JSONObject prompt = WorkflowVariants.compile(ui, variants, "group:2", server.url("/").toString());
            for (String id : new String[]{"239", "240", "252", "253", "254"}) assertTrue(id, prompt.has(id));
            for (String id : new String[]{"189", "193", "258", "285"}) assertFalse(id, prompt.has(id));
            assertEquals("252", prompt.getJSONObject("239").getJSONObject("inputs").getJSONArray("input_text").getString(0));
            assertEquals("253", prompt.getJSONObject("252").getJSONObject("inputs").getJSONArray("提示词").getString(0));
            assertEquals(1, prompt.getJSONObject("252").getJSONObject("inputs").getJSONArray("提示词").getInt(1));
            List<WorkflowUtils.OutputChoice> outputs = WorkflowUtils.findOutputNodes(prompt, info);
            assertEquals(1, outputs.size()); assertEquals("239", outputs.get(0).id);
            assertTrue(WorkflowUtils.findEditableFields(prompt, info).stream().anyMatch(f -> f.nodeId.equals("240") && f.inputName.equals("text")));
            assertTrue(TextOutputs.expectsText(prompt));
        }
    }
    @Test public void existingCollectionGainsTextBranchWithoutLosingSelectionOverridesOrSavedVariants() throws Exception {
        JSONObject ui = resource("minimax-text-sanitized.json");
        JSONArray all = WorkflowVariants.detect(ui), old = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).getString("id").equals("group:2")) old.put(all.getJSONObject(i));
        WorkflowProfile profile = WorkflowProfile.create("existing MiniMax", sampler(99), ui);
        profile.initializeCollection(old, "group:4");
        profile.overrides.put("1:seed", 99); profile.rememberActiveVariant();
        String before = profile.variantStates.toString();
        assertTrue(WorkflowVariants.discoverNewVariants(profile));
        assertEquals(5, profile.variants.length()); assertEquals("group:4", profile.activeVariantId);
        assertEquals(99, profile.overrides.getInt("1:seed")); assertEquals(before, profile.variantStates.toString());
        assertFalse(WorkflowVariants.discoverNewVariants(profile));
    }
    @Test public void selectingFinalTextDisplayRetainsUpstreamEnhancerOutputNode() throws Exception {
        JSONObject prompt = new JSONObject().put("1", new JSONObject().put("class_type", "TE_H3_Prompt_Enhancer").put("inputs", new JSONObject()))
                .put("2", new JSONObject().put("class_type", "TE_text_display").put("inputs", new JSONObject().put("input_text", new JSONArray().put("1").put(0))));
        List<WorkflowUtils.OutputChoice> choices = Arrays.asList(new WorkflowUtils.OutputChoice("1","enhancer","TE_H3_Prompt_Enhancer"),
                new WorkflowUtils.OutputChoice("2","result","TE_text_display"));
        JSONObject selected = WorkflowUtils.keepSelectedOutputNodes(prompt, choices, Collections.singleton("2"));
        assertTrue(selected.has("1")); assertTrue(selected.has("2"));
    }
    @Test public void textResultsParseOnlySelectedNodesAndNeverMediaFilenamesOrPromptGraphs() throws Exception {
        JSONObject history = new JSONObject("{\"p\":{\"status\":{\"completed\":true,\"status_str\":\"success\"},\"outputs\":{"
                + "\"239\":{\"text\":[\"第一行\",\"第二行\"],\"images\":[{\"filename\":\"not-text.png\"}]},"
                + "\"253\":{\"text\":[\"intermediate template\"]}}}}");
        JSONArray texts = TextOutputs.parse(history, "p", Collections.singleton("239"));
        assertEquals(1, texts.length()); assertEquals("第一行\n第二行", texts.getJSONObject(0).getString("text"));
        JobRecord job = JobRecord.create("wf", "text", "", "", "8188", "{}");
        JobStore.add(app(), job); JobStore.complete(app(), job.localId, Collections.emptyList(), texts, "提示词生成完成");
        JobRecord restored = JobStore.find(app(), job.localId);
        assertTrue(restored.hasTextOutput()); assertEquals(JobRecord.COMPLETED, restored.status);
        assertEquals(0, restored.outputCount);
        assertEquals("[]", restored.textOutputsJson);
        assertEquals("第一行\n第二行", TextOutputs.combined(JobStore.loadTextResults(app(),job.localId).textOutputsJson));
    }
    @Test public void seedButtonsSetFixedOrAutoValuesAndAcceptMinusOneDespiteServerMinimumZero() throws Exception {
        WorkflowProfile profile = WorkflowProfile.create("seed UI", sampler(123), null);
        WorkflowStore.upsert(app(), profile); WorkflowStore.setActiveId(app(), profile.id);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            LinearLayout content = new LinearLayout(activity);
            WorkflowUtils.FieldChoice choice = new WorkflowUtils.FieldChoice("1","seed","Seed","TestSeedSampler",
                    WorkflowUtils.FieldChoice.INT,123L,0,10000,1,Collections.emptyList());
            Method add = MainActivity.class.getDeclaredMethod("addParameterField", LinearLayout.class, WorkflowUtils.FieldChoice.class);
            add.setAccessible(true); add.invoke(activity, content, choice);
            List<?> bindings = (List<?>) field(activity, "fieldBindings"); Object binding = bindings.get(bindings.size()-1);
            EditText editor = (EditText) field(binding, "editView");
            button(content, "🎲 掷骰子").performClick();
            long rolled = Long.parseLong(editor.getText().toString()); assertTrue(rolled >= 0 && rolled <= 10000);
            button(content, "每次随机（-1）").performClick(); assertEquals("-1", editor.getText().toString());
            Method read = MainActivity.class.getDeclaredMethod("readFieldValue", binding.getClass()); read.setAccessible(true);
            assertEquals(-1L, read.invoke(activity, binding));
            editor.setText("-2"); try { read.invoke(activity, binding); fail(); } catch (InvocationTargetException expected) {}
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void completedTextAppearsOnHomeAndCopiesFullResultWithoutMediaDownload() throws Exception {
        JobRecord job = JobRecord.create("wf", "prompt", "", "", "8188", "{}"); job.promptId="text-complete";
        job.status=JobRecord.COMPLETED; job.textOutputsJson=new JSONArray().put(new JSONObject().put("text","完整 H3 提示词")).toString();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            Method load = MainActivity.class.getDeclaredMethod("loadJobOutputs", JobRecord.class); load.setAccessible(true); load.invoke(activity, job);
            LinearLayout output = (LinearLayout) field(activity, "outputList");
            Button copy = button(output, "复制提示词"); assertNotNull(copy); copy.performClick();
            ClipboardManager clipboard=(ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            assertEquals("完整 H3 提示词", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            assertTrue(((List<?>)field(activity,"currentOutputRefs")).isEmpty());
        } finally { controller.pause().stop().destroy(); }
    }
}
