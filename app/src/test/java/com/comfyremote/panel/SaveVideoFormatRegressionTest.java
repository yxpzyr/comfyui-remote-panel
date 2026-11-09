package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

/**
 * Regression for real ComfyUI history:
 * SaveVideo.execute() missing 1 required positional argument: 'format' (node 193).
 * V3 SaveVideo.format uses COMFY_DYNAMICCOMBO_V3, not the older COMBO widget.
 */
public class SaveVideoFormatRegressionTest {
    private static JSONObject option(String name) throws Exception {
        return new JSONObject().put("key", name)
                .put("inputs", new JSONObject().put("required", new JSONObject()));
    }

    private static JSONObject schema() throws Exception {
        JSONObject required = new JSONObject()
                .put("video", new JSONArray().put("VIDEO").put(new JSONObject()))
                .put("filename_prefix", new JSONArray().put("STRING")
                        .put(new JSONObject().put("default", "video/ComfyUI")))
                .put("format", new JSONArray().put("COMFY_DYNAMICCOMBO_V3")
                        .put(new JSONObject().put("options", new JSONArray()
                                .put(option("auto")).put(option("mp4"))
                                .put(option("mkv")).put(option("webm")).put(option("mov"))));
        JSONObject saveVideo = new JSONObject().put("input", new JSONObject().put("required", required))
                .put("input_order", new JSONObject().put("required", new JSONArray()
                        .put("video").put("filename_prefix").put("format")));
        JSONObject source = new JSONObject().put("input",
                new JSONObject().put("required", new JSONObject()));
        return new JSONObject().put("SaveVideo", saveVideo).put("MockVideoSource", source);
    }

    private static JSONObject apiPrompt() throws Exception {
        return new JSONObject().put("193", new JSONObject()
                .put("class_type", "SaveVideo")
                .put("inputs", new JSONObject().put("video", new JSONArray().put("11").put(0))
                        .put("filename_prefix", "videos/test")));
    }

    private static JSONObject uiPrompt(Object... widgets) throws Exception {
        JSONObject source = new JSONObject().put("id", 11).put("type", "MockVideoSource")
                .put("pos", new JSONArray().put(0).put(0));
        JSONObject saver = new JSONObject().put("id", 193).put("type", "SaveVideo")
                .put("pos", new JSONArray().put(100).put(0))
                .put("inputs", new JSONArray().put(new JSONObject()
                        .put("name", "video").put("link", 7)))
                .put("widgets_values", new JSONArray(widgets));
        return new JSONObject().put("nodes", new JSONArray().put(source).put(saver))
                .put("links", new JSONArray().put(new JSONArray()
                        .put(7).put(11).put(0).put(193).put(0).put("VIDEO")));
    }

    private static JSONObject convert(JSONObject ui) throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(schema().toString()));
        server.start();
        try {
            WorkflowUiConverter.clearObjectInfoCache();
            JSONObject converted = WorkflowUiConverter.toApiPrompt(ui, server.url("/").toString());
            assertEquals("/object_info", server.takeRequest().getPath());
            return converted;
        } finally {
            server.shutdown();
            WorkflowUiConverter.clearObjectInfoCache();
        }
    }

    @Test public void existingMp4ChoiceIsPreserved() throws Exception {
        JSONObject result = convert(uiPrompt("videos/render", "mp4"));
        JSONObject inputs = result.getJSONObject("193").getJSONObject("inputs");
        assertEquals("mp4", inputs.getString("format"));
        assertEquals("videos/render", inputs.getString("filename_prefix"));
        assertEquals("11", inputs.getJSONArray("video").getString(0));
    }

    @Test public void missingUiFormatGetsSchemaAdvertisedAuto() throws Exception {
        JSONObject result = convert(uiPrompt("videos/render"));
        assertEquals("auto", result.getJSONObject("193").getJSONObject("inputs")
                .getString("format"));
    }

    @Test public void nestedCodecChoiceSurvivesUiConversion() throws Exception {
        JSONObject selection = new JSONObject().put("format", "mkv")
                .put("codec", new JSONObject().put("codec", "h264"));
        JSONObject result = convert(uiPrompt("videos/render", selection));
        JSONObject actual = result.getJSONObject("193").getJSONObject("inputs")
                .getJSONObject("format");
        assertEquals("mkv", actual.getString("format"));
        assertEquals("h264", actual.getJSONObject("codec").getString("codec"));
    }

    @Test public void oldSavedApiPromptRepairsMissingFormatWithoutReimport() throws Exception {
        JSONObject old = apiPrompt();
        assertTrue(PromptValueNormalizer.needsRequiredDynamicInspection(old));
        assertEquals(1, PromptValueNormalizer.ensureRequiredDynamicInputs(old, schema()));
        assertEquals("auto", old.getJSONObject("193").getJSONObject("inputs")
                .getString("format"));
        assertEquals(0, PromptValueNormalizer.ensureRequiredDynamicInputs(old, schema()));
    }

    @Test public void existingFormatNeverOverridden() throws Exception {
        JSONObject api = apiPrompt();
        api.getJSONObject("193").getJSONObject("inputs").put("format", "mov");
        assertEquals(0, PromptValueNormalizer.ensureRequiredDynamicInputs(api, schema()));
        assertEquals("mov", api.getJSONObject("193").getJSONObject("inputs")
                .getString("format"));
    }

    @Test public void ambiguousDynamicChoiceFailsInsteadOfInventingACodec() throws Exception {
        JSONObject sc = schema();
        JSONArray choices = new JSONArray().put(option("mp4")).put(option("mkv"));
        sc.getJSONObject("SaveVideo").getJSONObject("input").getJSONObject("required")
                .getJSONArray("format").getJSONObject(1).put("options", choices);
        try {
            PromptValueNormalizer.ensureRequiredDynamicInputs(apiPrompt(), sc);
            fail("Must not choose a container/codec without a default or auto option");
        } catch (JSONException e) {
            assertTrue(e.getMessage().contains("193"));
            assertTrue(e.getMessage().contains("format"));
        }
    }
}
