package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

/**
 * Recreates all four SaveVideo outputs from the supplied MiniMax H3 composite UI.
 * The user's real graph has node 189 active and 193/258/285 bypassed:
 * all four carry ["video/MiniMax_H3", "auto", "auto"].
 */
public class SaveVideoAllBranchesRegressionTest {
    private static final int[] SINKS = {189, 193, 258, 285};

    private static JSONObject entry(String key) throws Exception {
        return new JSONObject().put("key", key)
                .put("inputs", new JSONObject().put("required", new JSONObject()));
    }

    private static JSONObject nodeSchema(boolean optionalFormat, boolean forceFormatInput) throws Exception {
        JSONObject formats = new JSONObject().put("options", new JSONArray()
                .put("auto").put("mp4").put("mkv").put("webm").put("mov"));
        if (forceFormatInput) formats.put("forceInput", true);
        JSONArray formatSpec = new JSONArray().put("COMBO").put(formats);
        JSONObject required = new JSONObject()
                .put("video", new JSONArray().put("VIDEO").put(new JSONObject()))
                .put("filename_prefix", new JSONArray().put("STRING")
                        .put(new JSONObject().put("default", "video/ComfyUI")));
        JSONObject optional = new JSONObject().put("codec", new JSONArray()
                .put("COMFY_DYNAMICCOMBO_V3").put(new JSONObject()
                    .put("options", new JSONArray().put(entry("auto")).put(entry("h264")))));
        if (optionalFormat) optional.put("format", formatSpec);
        else required.put("format", formatSpec);

        JSONObject schema = new JSONObject().put("input", new JSONObject()
                .put("required", required).put("optional", optional));
        // Also exercise partial hints: format may only appear in optional.
        schema.put("input_order", new JSONObject()
                .put("required", new JSONArray().put("video").put("filename_prefix")
                        .put(optionalFormat ? "codec" : "format"))
                .put("optional", new JSONArray().put("format").put("codec")));
        return schema;
    }

    private static JSONObject definitions(boolean optionalFormat, boolean forceInput) throws Exception {
        return new JSONObject().put("SaveVideo", nodeSchema(optionalFormat, forceInput))
                .put("VideoProducer", new JSONObject()
                        .put("input", new JSONObject().put("required", new JSONObject())));
    }

    private static JSONObject node(int id, boolean saveVideo, int group) throws Exception {
        JSONObject n = new JSONObject().put("id", id)
                .put("type", saveVideo ? "SaveVideo" : "VideoProducer")
                .put("pos", new JSONArray().put(group * 1000 + 150).put(saveVideo ? 210 : 25))
                .put("mode", saveVideo && id != 189 ? 4 : 0);
        if (saveVideo) {
            n.put("inputs", new JSONArray()
                    .put(new JSONObject().put("name", "video").put("type", "VIDEO")
                            .put("link", id + 1000))
                    .put(new JSONObject().put("name", "filename_prefix")
                            .put("widget", new JSONObject().put("name", "filename_prefix")))
                    .put(new JSONObject().put("name", "format")
                            .put("widget", new JSONObject().put("name", "format")))
                    .put(new JSONObject().put("name", "codec")
                            .put("widget", new JSONObject().put("name", "codec"))));
            n.put("widgets_values", new JSONArray()
                    .put("video/MiniMax_H3").put("auto").put("auto"));
        }
        return n;
    }

    private static JSONObject ui() throws Exception {
        JSONObject root = new JSONObject();
        JSONArray groups = new JSONArray();
        JSONArray nodes = new JSONArray();
        JSONArray links = new JSONArray();
        for (int i = 0; i < SINKS.length; i++) {
            int sink = SINKS[i];
            groups.put(new JSONObject()
                    .put("title", "MiniMaxH3-" + new String[]{
                        "图生视频", "文生视频", "首尾帧", "多参视频"}[i])
                    .put("bounding", new JSONArray().put(i * 1000).put(0).put(400).put(400)));
            nodes.put(node(sink + 500, false, i));
            nodes.put(node(sink, true, i));
            links.put(new JSONArray().put(sink + 1000).put(sink + 500)
                    .put(0).put(sink).put(0).put("VIDEO"));
        }
        return root.put("nodes", nodes).put("groups", groups).put("links", links);
    }

    @Test public void allFourBranchesRetainAutoFormatEvenWhenServerCallsItOptional()
            throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(definitions(true, true).toString()));
        server.start();
        try {
            WorkflowUiConverter.clearObjectInfoCache();
            JSONObject original = ui();
            JSONArray variants = WorkflowVariants.detect(original);
            assertEquals(4, variants.length());
            Set<String> compiledIds = new HashSet<>();
            for (int i = 0; i < variants.length(); i++) {
                String id = variants.getJSONObject(i).getString("id");
                JSONObject prompt = WorkflowVariants.compile(original, variants, id, server.url("/").toString());
                String sinkId = String.valueOf(SINKS[i]);
                assertTrue("Selected sink missing: " + sinkId, prompt.has(sinkId));
                assertEquals("auto", prompt.getJSONObject(sinkId)
                        .getJSONObject("inputs").getString("format"));
                assertEquals("video/MiniMax_H3", prompt.getJSONObject(sinkId)
                        .getJSONObject("inputs").getString("filename_prefix"));
                // Submission check must not alter correctly carried values.
                assertEquals(0, SaveVideoFormatGuard.ensureSubmissionFormats(
                        prompt, definitions(true, true)));
                compiledIds.add(sinkId);
            }
            assertEquals(4, compiledIds.size());
        } finally {
            WorkflowUiConverter.clearObjectInfoCache();
            server.shutdown();
        }
    }

    @Test public void legacyImageToVideoSnapshotFor189GetsFormatWithoutReimport() throws Exception {
        JSONObject saved = new JSONObject().put("189", new JSONObject()
                .put("class_type", "SaveVideo").put("inputs", new JSONObject()
                        .put("video", new JSONArray().put("689").put(0))
                        .put("filename_prefix", "video/MiniMax_H3")));
        assertTrue(SaveVideoFormatGuard.hasSaveVideo(saved));
        assertEquals(1, SaveVideoFormatGuard.ensureSubmissionFormats(saved, definitions(true, true)));
        assertEquals("auto", saved.getJSONObject("189").getJSONObject("inputs").getString("format"));
    }

    @Test public void existingCodecChoiceIsNotOverwrittenWhenFormatMissing() throws Exception {
        JSONObject saved = new JSONObject().put("189", new JSONObject()
                .put("class_type", "SaveVideo").put("inputs", new JSONObject()
                        .put("codec", new JSONObject().put("codec", "h264"))));
        assertEquals(1, SaveVideoFormatGuard.ensureSubmissionFormats(saved, definitions(true, false)));
        assertEquals("h264", saved.getJSONObject("189").getJSONObject("inputs")
                .getJSONObject("codec").getString("codec"));
    }

    @Test public void explicitMp4AndNestedFormattingAreNeverReplaced() throws Exception {
        JSONObject saved = new JSONObject().put("189", new JSONObject()
                .put("class_type", "SaveVideo").put("inputs", new JSONObject()
                        .put("format", new JSONObject().put("format", "mp4")
                                .put("codec", new JSONObject().put("codec", "h264")))));
        assertEquals(0, SaveVideoFormatGuard.ensureSubmissionFormats(saved, definitions(true, false)));
        assertEquals("mp4", saved.getJSONObject("189").getJSONObject("inputs")
                .getJSONObject("format").getString("format"));
    }

    @Test public void ambiguousFormatFailsClearlyBeforeExpensiveExecution() throws Exception {
        JSONObject unsupported = definitions(true, false);
        unsupported.getJSONObject("SaveVideo").getJSONObject("input")
                .getJSONObject("optional").getJSONArray("format").getJSONObject(1)
                .put("options", new JSONArray().put("mp4").put("mkv"));
        JSONObject saved = new JSONObject().put("189", new JSONObject()
                .put("class_type", "SaveVideo").put("inputs", new JSONObject()));
        try {
            SaveVideoFormatGuard.ensureSubmissionFormats(saved, unsupported);
            fail("Must not silently select mp4 or mkv");
        } catch (JSONException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("189"));
            assertTrue(e.getMessage(), e.getMessage().contains("format"));
        }
    }

    @Test public void historyShowsExactFormatActuallySubmittedToServer() throws Exception {
        JSONObject submitted = new JSONObject().put("189", new JSONObject()
                .put("class_type", "SaveVideo").put("inputs", new JSONObject()));
        JSONObject history = new JSONObject().put("prompt", new JSONArray()
                .put(100).put("a-prompt-id").put(submitted).put(new JSONObject()));
        assertTrue(SaveVideoFormatGuard.submittedFormats(history).contains("189 format=[缺失]"));
        SaveVideoFormatGuard.ensureSubmissionFormats(submitted, definitions(true, false));
        assertTrue(SaveVideoFormatGuard.submittedFormats(history).contains("189 format=auto"));
    }
}
