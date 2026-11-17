package com.comfyremote.panel;

import org.json.*;
import org.junit.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.mockwebserver.*;
import static org.junit.Assert.*;

public class ReferenceSelectionRegressionTest {
    private JSONObject fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/reference-selection-" + name + ".json")) {
            assertNotNull(in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private Set<String> images(String... ids) { return new HashSet<>(Arrays.asList(ids)); }
    private MockWebServer server() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(fixture("schema").toString()));
        server.start(); WorkflowUiConverter.clearObjectInfoCache(); return server;
    }
    private void assertOnlyA(JSONObject graph) throws Exception {
        assertTrue(graph.has("308"));
        assertFalse("Disabled loader must be absent", graph.has("295"));
        JSONObject refs = graph.getJSONObject("316").getJSONObject("inputs");
        assertEquals("308", refs.getJSONArray("ref_images.ref_image_0").getString(0));
        assertFalse("Disabled reference connection must be absent", refs.has("ref_images.ref_image_1"));
        assertFalse(graph.toString().contains("original_C.png"));
        assertTrue(graph.has("285"));
    }
    @After public void clearCache() { WorkflowUiConverter.clearObjectInfoCache(); }

    @Test public void disabledLoaderInsideSelectedGroupDoesNotReturnAsDefaultPicture() throws Exception {
        JSONObject ui = fixture("ui"); String original = ui.toString();
        try (MockWebServer server = server()) {
            JSONArray variants = WorkflowVariants.detect(ui);
            assertEquals(2, variants.length());
            JSONObject graph = WorkflowVariants.compile(ui, variants, "group:0", server.url("/").toString(), images("308"));
            assertOnlyA(graph);
            assertEquals("Imported workflow must be preserved", original, ui.toString());
        }
    }
    @Test public void turningSecondReferenceOnOffOnChangesActualConnections() throws Exception {
        JSONObject ui = fixture("ui"); JSONArray variants = WorkflowVariants.detect(ui);
        try (MockWebServer server = server()) {
            String base = server.url("/").toString();
            JSONObject both = WorkflowVariants.compile(ui, variants, "group:0", base, images("308", "295"));
            assertTrue(both.has("295"));
            assertEquals("295", both.getJSONObject("316").getJSONObject("inputs")
                    .getJSONArray("ref_images.ref_image_1").getString(0));
            assertOnlyA(WorkflowVariants.compile(ui, variants, "group:0", base, images("308")));
            assertTrue(WorkflowVariants.compile(ui, variants, "group:0", base, images("308", "295")).has("295"));
        }
    }
    @Test public void legacySavedGraphIsRepairedWithoutReimportOrLosingEditedParameters() throws Exception {
        JSONObject ui = fixture("ui"); JSONArray variants = WorkflowVariants.detect(ui);
        try (MockWebServer server = server()) {
            String base = server.url("/").toString();
            JSONObject stale = WorkflowVariants.compile(ui, variants, "group:0", base, images("308", "295"));
            stale.getJSONObject("316").getJSONObject("inputs").put("prompt", "Keep this edited park scene");
            WorkflowProfile profile = WorkflowProfile.create("Saved reference workflow", stale, ui);
            profile.initializeCollection(variants, "group:0"); profile.setAuxImageEnabled("308", true);
            profile.setAuxImageEnabled("295", false);
            WorkflowProfile frozen = profile.submissionSnapshot();
            profile.setAuxImageEnabled("295", true);
            JSONObject graph = frozen.submissionPrompt(base);
            assertOnlyA(graph);
            assertEquals("Keep this edited park scene", graph.getJSONObject("316").getJSONObject("inputs").getString("prompt"));
            assertTrue("Repair must not rewrite stored/history graphs", frozen.promptObject().has("295"));
            assertFalse(frozen.isAuxImageEnabled("295"));
        }
    }
    @Test public void ordinaryWorkflowDoesNotNeedRecompileOrNetwork() throws Exception {
        WorkflowProfile profile = WorkflowProfile.create("Ordinary", new JSONObject()
                .put("1", new JSONObject().put("class_type", "SaveImage").put("inputs", new JSONObject())), null);
        assertEquals(profile.promptJson, profile.submissionPrompt("http://127.0.0.1:1").toString());
    }
}
