package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

/** Verifies video-group sink discovery and accurate ComfyUI submission diagnostics. */
public class VideoGenerationRegressionTest {
    private static JSONObject node(int id, String type, int x, int y) throws Exception {
        return new JSONObject().put("id", id).put("type", type)
                .put("pos", new JSONArray().put(x).put(y)).put("mode", id == 21 ? 4 : 0);
    }

    private static JSONObject group(String title, int x) throws Exception {
        return new JSONObject().put("title", title)
                .put("bounding", new JSONArray().put(x).put(0).put(400).put(400));
    }

    @Test public void recognizesVhsAndNativeVideoOutputGroups() throws Exception {
        JSONObject ui = new JSONObject().put("nodes", new JSONArray()
                .put(node(21, "VHS_VideoCombine", 100, 100))
                .put(node(22, "SaveVideo", 600, 100)))
                .put("groups", new JSONArray()
                        .put(group("图生视频", 0))
                        .put(group("文生视频", 500)))
                .put("links", new JSONArray());
        JSONArray variants = WorkflowVariants.detect(ui);
        assertEquals(2, variants.length());
        assertEquals("group:0", variants.getJSONObject(0).getString("id"));
        assertEquals("21", variants.getJSONObject(0).getJSONArray("outputs").getString(0));
        assertEquals("22", variants.getJSONObject(1).getJSONArray("outputs").getString(0));
        assertEquals("文生视频", WorkflowVariants.label(variants, "group:1"));
    }

    @Test public void findsVideoOutputNodesFromExecutablePrompt() throws Exception {
        JSONObject graph = new JSONObject().put("21", new JSONObject()
                        .put("class_type", "VHS_VideoCombine").put("inputs", new JSONObject()))
                .put("22", new JSONObject()
                        .put("class_type", "SaveVideo").put("inputs", new JSONObject()));
        List<WorkflowUtils.OutputChoice> selected = WorkflowUtils.findOutputNodes(graph);
        assertEquals(2, selected.size());
        assertEquals("21", selected.get(0).id);
        assertEquals("22", selected.get(1).id);
    }

    @Test public void doesNotResubmitRejectedVideoPromptAndPreservesError() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"type\":\"prompt_outputs_failed_validation\","+
                        "\"message\":\"Prompt outputs failed validation\"},"+
                        "\"node_errors\":{\"189\":{\"class_type\":\"SaveVideo\","+
                        "\"errors\":[{\"type\":\"value_not_in_list\","+
                        "\"message\":\"Value not in list\","+
                        "\"details\":\"codec: unsupported\"}]}}}"));
        server.start();
        try {
            ComfyApiClient api = new ComfyApiClient(server.url("/").toString());
            try {
                api.queuePrompt(new JSONObject());
                fail("Validation must be reported");
            } catch (Exception e) {
                String message = e.getMessage();
                assertTrue(message, message.contains("HTTP 400"));
                assertTrue(message, message.contains("SaveVideo"));
                assertTrue(message, message.contains("189"));
                assertTrue(message, message.contains("codec: unsupported"));
            }
            assertEquals("Do not retry a rejected generation on another endpoint", 1, server.getRequestCount());
            assertEquals("/prompt", server.takeRequest().getPath());
        } finally { server.shutdown(); }
    }

    @Test public void fallbackIsAllowedOnlyForMissingPromptEndpoint() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"prompt_id\":\"video-task-123\"}"));
        server.start();
        try {
            ComfyApiClient api = new ComfyApiClient(server.url("/").toString());
            assertEquals("video-task-123", api.queuePrompt(new JSONObject()));
            assertEquals(2, server.getRequestCount());
            assertEquals("/prompt", server.takeRequest().getPath());
            assertEquals("/api/prompt", server.takeRequest().getPath());
        } finally { server.shutdown(); }
    }

    @Test public void historyReportsVhsMp4OutputAsVideo() throws Exception {
        JSONObject hist = new JSONObject().put("t", new JSONObject()
                .put("status", new JSONObject().put("status_str", "success").put("completed", true))
                .put("outputs", new JSONObject().put("21", new JSONObject()
                    .put("gifs", new JSONArray().put(new JSONObject()
                        .put("filename", "upscaled.mp4")
                        .put("subfolder", "videos")
                        .put("type", "output"))))));
        ComfyApiClient api = new ComfyApiClient("8188");
        assertTrue(api.isHistoryCompleted(hist, "t"));
        assertEquals("", api.historyError(hist, "t"));
        List<ImageRef> refs = api.parseAllImagesForPrompt(hist, "t");
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).isVideo());
    }
}
