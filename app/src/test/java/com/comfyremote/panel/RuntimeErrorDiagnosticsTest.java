package com.comfyremote.panel;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.UUID;

import static org.junit.Assert.*;

/** Regression: large ComfyUI Python tracebacks must not flood queue cards. */
@RunWith(RobolectricTestRunner.class)
public class RuntimeErrorDiagnosticsTest {
    private JSONObject history() throws Exception {
        JSONArray trace = new JSONArray();
        for (int i = 0; i < 80; i++)
            trace.put("File /ComfyUI/nodes.py line " + i + " in execute\\n" + "some Python expression".repeat(12));
        JSONObject event = new JSONObject()
                .put("prompt_id", "video-abc")
                .put("node_id", "209")
                .put("node_type", "CreateVideo")
                .put("exception_message", "Could not encode video: unsupported parameter")
                .put("exception_type", "RuntimeError")
                .put("traceback", trace);
        JSONObject status = new JSONObject()
                .put("status_str", "error")
                .put("completed", false)
                .put("messages", new JSONArray()
                        .put(new JSONArray().put("execution_start").put(new JSONObject()))
                        .put(new JSONArray().put("execution_error").put(event)));
        return new JSONObject().put("video-abc", new JSONObject().put("status", status));
    }

    @Test public void failedNodeAndRootCauseAreReadableWithoutStackDump() throws Exception {
        ComfyApiClient api = new ComfyApiClient("8188");
        JSONObject history = history();
        String summary = api.historyError(history, "video-abc");
        assertTrue(summary, summary.contains("209"));
        assertTrue(summary, summary.contains("CreateVideo"));
        assertTrue(summary, summary.contains("Could not encode video"));
        assertFalse(summary, summary.contains("/ComfyUI/nodes.py"));
        assertTrue("A short message must fit on a phone card", summary.length() < 750);
        String details = api.historyErrorDetails(history, "video-abc");
        assertTrue(details, details.contains("RuntimeError"));
        assertTrue(details, details.contains("Python traceback"));
        assertTrue(details, details.contains("/ComfyUI/nodes.py"));
    }

    @Test public void executionSuccessHasNoError() throws Exception {
        JSONObject status = new JSONObject().put("status_str", "success")
                .put("messages", new JSONArray());
        assertEquals("", ComfyExecutionError.summary(status));
        assertEquals("", ComfyExecutionError.details(status));
    }

    @Test public void fallbackErrorWithoutExceptionEventRemainsUseful() throws Exception {
        JSONObject status = new JSONObject().put("status_str", "error")
                .put("completed", false).put("messages", new JSONArray());
        assertTrue(ComfyExecutionError.summary(status).contains("已报告执行错误"));
        assertTrue(ComfyExecutionError.details(status).contains("原始状态"));
    }

    @Test public void locallyStoredDetailsSurviveReopeningQueue() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String id = UUID.randomUUID().toString();
        String details = ComfyExecutionError.details(history()
                .getJSONObject("video-abc").getJSONObject("status"));
        JobStore.saveErrorDetails(app, id, details);
        String copied = JobStore.readErrorDetails(app, id);
        assertEquals(details, copied);
        assertTrue(copied.contains("节点 ID: 209"));
        assertEquals("", JobStore.readErrorDetails(app, UUID.randomUUID().toString()));
    }
}
