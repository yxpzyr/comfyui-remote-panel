package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Set;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Video history regressions: large embedded prompt, SaveVideo files, real error visibility. */
@RunWith(RobolectricTestRunner.class)
public class VideoExecutionRegressionTest {
    private JSONObject result(String name) throws Exception {
        JSONObject file = new JSONObject().put("filename", name)
                .put("subfolder", "video").put("type", "output");
        return new JSONObject().put("189", new JSONObject()
                .put("images", new JSONArray().put(file)));
    }

    @Test public void hugeVideoHistoryReturnsMetadataWithoutPromptGraph() throws Exception {
        String huge = new String(new char[3_000_000]).replace('\0', 'Q');
        JSONObject entry = new JSONObject()
                .put("prompt", new JSONArray().put(0).put("test-video")
                        .put(new JSONObject().put("large_node_payload", huge)))
                .put("status", new JSONObject().put("status_str", "success").put("completed", true))
                .put("outputs", result("MiniMax_H3_00001_.mp4"));
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(
                new JSONObject().put("test-video", entry).toString()));
        server.start();
        try {
            ComfyApiClient api = new ComfyApiClient(server.url("/").toString());
            JSONObject history = api.getHistoryForPrompt("test-video");
            assertTrue(api.isHistoryCompleted(history, "test-video"));
            assertFalse(history.toString().contains(huge.substring(0, 100)));
            assertTrue(history.toString().length() < 4000);
            assertEquals(1, api.parseAllImagesForPrompt(history, "test-video", Set.of("189")).size());
            assertTrue(api.parseAllImagesForPrompt(history, "test-video").get(0).isVideo());
            assertEquals("/history/test-video", server.takeRequest().getPath());
        } finally { server.shutdown(); }
    }

    @Test public void videoExecutionErrorReportsExactNodeInsteadOfUnknownFailure() throws Exception {
        JSONObject details = new JSONObject().put("node_id", "189")
                .put("exception_type", "ValueError")
                .put("exception_message", "Unsupported video codec");
        JSONObject status = new JSONObject().put("completed", false).put("status_str", "error")
                .put("messages", new JSONArray().put(new JSONArray()
                        .put("execution_error").put(details)));
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(new JSONObject().put("abc",
                new JSONObject().put("status", status).put("outputs", new JSONObject())).toString()));
        server.start();
        try {
            ComfyApiClient api = new ComfyApiClient(server.url("/").toString());
            String error = api.historyError(api.getHistoryForPrompt("abc"), "abc");
            assertTrue(error, error.contains("189"));
            assertTrue(error, error.contains("Unsupported video codec"));
        } finally { server.shutdown(); }
    }

    @Test public void promptValidationErrorMustNotBeOverwrittenByFallback404() throws Exception {
        MockWebServer server = new MockWebServer();
        String failed = new JSONObject()
                .put("error", new JSONObject().put("message", "Prompt outputs failed validation"))
                .put("node_errors", new JSONObject().put("189", new JSONObject()
                        .put("class_type", "SaveVideo")
                        .put("errors", new JSONArray().put(new JSONObject()
                                .put("message", "Required input is missing: video"))))).toString();
        server.enqueue(new MockResponse().setResponseCode(400).setBody(failed));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        server.start();
        try {
            try {
                new ComfyApiClient(server.url("/").toString()).queuePrompt(new JSONObject());
                fail("Should reject 400");
            } catch (Exception e) {
                assertTrue(e.toString(), e.getMessage().contains("节点 189"));
                assertTrue(e.toString(), e.getMessage().contains("SaveVideo"));
            }
            RecordedRequest req = server.takeRequest();
            assertEquals("/prompt", req.getPath());
            assertEquals(1, server.getRequestCount());
        } finally { server.shutdown(); }
    }

    @Test public void outputFilteringNeverDropsRenamedOrdinaryVideoNodes() throws Exception {
        JSONObject apiPrompt = new JSONObject()
                .put("188", new JSONObject().put("class_type", "CreateVideo")
                        .put("_meta", new JSONObject().put("title", "SaveVideo helper"))
                        .put("inputs", new JSONObject()))
                .put("189", new JSONObject().put("class_type", "SaveVideo")
                        .put("inputs", new JSONObject().put("video", new JSONArray().put("188").put(0))));
        java.util.List<WorkflowUtils.OutputChoice> outputs = WorkflowUtils.findOutputNodes(apiPrompt);
        assertEquals(1, outputs.size());
        assertEquals("189", outputs.get(0).id);
        JSONObject filtered = WorkflowUtils.keepSelectedOutputNodes(apiPrompt, outputs, Set.of("189"));
        assertTrue(filtered.has("188"));
        assertTrue(filtered.has("189"));
    }
}
