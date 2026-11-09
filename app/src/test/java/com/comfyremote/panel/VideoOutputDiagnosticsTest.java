package com.comfyremote.panel;

import android.app.Application;
import android.util.JsonReader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VideoOutputDiagnosticsTest {
    private static final String PID = "video-prompt-123";

    @Test public void largeComfyHistoryDropsPromptGraphButPreservesVideoAndSuccess() throws Exception {
        String largeGraph = "x".repeat(3_000_000);
        JSONObject video = new JSONObject().put("filename", "output.mp4")
                .put("subfolder", "videos").put("type", "output");
        JSONObject entry = new JSONObject()
                .put("prompt", new JSONArray().put(0).put(PID)
                        .put(new JSONObject().put("huge", largeGraph)))
                .put("status", new JSONObject().put("status_str", "success").put("completed", true))
                .put("outputs", new JSONObject().put("91",
                        new JSONObject().put("videos", new JSONArray().put(video))));
        String json = new JSONObject().put(PID, entry).toString();
        ComfyApiClient api = new ComfyApiClient("http://127.0.0.1:8188");
        JSONObject history = ComfyApiClient.parseCompactHistory(new JsonReader(new StringReader(json)), PID);
        assertFalse(history.toString().contains(largeGraph.substring(0, 100)));
        assertTrue(history.toString().length() < 3000);
        assertTrue(api.isHistoryCompleted(history, PID));
        List<ImageRef> items = api.parseAllImagesForPrompt(history, PID);
        assertEquals(1, items.size());
        assertTrue(items.get(0).isVideo());
        assertEquals("output.mp4", items.get(0).filename);
    }

    @Test public void alternativeVideoSaveNodeAppearsWhenSelectedFilterIsOutdated() throws Exception {
        JSONObject entry = new JSONObject().put("status", new JSONObject()
                .put("completed", true).put("status_str", "success"))
                .put("outputs", new JSONObject().put("SaveVideo_200",
                        new JSONObject().put("videos", new JSONArray().put(new JSONObject()
                                .put("filename", "completed.mkv").put("type", "output")))));
        JSONObject history = new JSONObject().put(PID, entry);
        ComfyApiClient api = new ComfyApiClient("8188");
        assertEquals(0, api.parseAllImagesForPrompt(history, PID, java.util.Set.of("outdated-id")).size());
        assertEquals(1, api.parseAllImagesForPrompt(history, PID, null).size());
    }

    @Test public void preserveActualComfyNodeErrorMessage() throws Exception {
        JSONObject detail = new JSONObject().put("node_id", "138")
                .put("node_type", "SaveVideo")
                .put("exception_type", "ValueError")
                .put("exception_message", "Invalid video output format");
        JSONObject status = new JSONObject().put("status_str", "error")
                .put("completed", false)
                .put("messages", new JSONArray().put(new JSONArray()
                        .put("execution_error").put(detail)));
        JSONObject history = new JSONObject().put(PID, new JSONObject().put("status", status));
        String error = new ComfyApiClient("8188").historyError(history, PID);
        assertTrue(error.contains("138"));
        assertTrue(error.contains("SaveVideo"));
        assertTrue(error.contains("Invalid video output format"));
    }

    @Test public void badPromptReturnsOriginalNodeValidationErrorInsteadOfFallback404() throws Exception {
        MockWebServer server = new MockWebServer();
        JSONObject response = new JSONObject().put("error",
                        new JSONObject().put("type", "prompt_outputs_failed_validation"))
                .put("node_errors", new JSONObject().put("138", new JSONObject()
                        .put("errors", new JSONArray().put(
                                new JSONObject().put("message", "Invalid output video")))));
        server.enqueue(new MockResponse().setResponseCode(400).setBody(response.toString()));
        server.start();
        try {
            ComfyApiClient api = new ComfyApiClient(server.url("/").toString());
            try {
                api.queuePrompt(new JSONObject().put("138", new JSONObject()
                        .put("class_type", "SaveVideo").put("inputs", new JSONObject())));
                fail("Expected HTTP 400");
            } catch (Exception e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Invalid output video"));
                assertFalse(e.getMessage(), e.getMessage().contains("404"));
            }
            RecordedRequest request = server.takeRequest();
            assertEquals("/prompt", request.getPath());
            assertEquals(1, server.getRequestCount());
        } finally { server.shutdown(); }
    }

    @Test public void preserveFailuresAfterTaskQueueIsCleared() {
        Application app = RuntimeEnvironment.getApplication();
        FailureDiagnostics.clear(app);
        JobRecord job = JobRecord.create("p", "视频清晰度修复工作流", "输入测试视频", "", "8188", "{}");
        job.status = JobRecord.FAILED;
        job.message = "提交失败：节点 138 [SaveVideo] 缺少 video 输入";
        job.promptId = PID;
        JobStore.add(app, job);
        FailureDiagnostics.record(app, job);
        JobStore.clearFinished(app);
        String text = FailureDiagnostics.display(app);
        assertTrue(text, text.contains("节点 138"));
        assertTrue(text, text.contains(PID));
        FailureDiagnostics.clear(app);
    }
}
