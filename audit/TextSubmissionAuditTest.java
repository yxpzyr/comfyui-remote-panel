package com.comfyremote.panel;

import android.app.Application;
import android.os.Looper;
import android.widget.Button;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.*;
import static org.junit.Assert.*;

/** Uses the same ordinary text and fixed configuration on three unmodified app versions. */
@RunWith(RobolectricTestRunner.class)
public class TextSubmissionAuditTest {
    private Object field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }
    @Test public void captureActualTextSubmission() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        JSONObject expected;
        try (InputStream in = getClass().getResourceAsStream("/text-submission-audit.json")) {
            assertNotNull(in);
            expected = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        AtomicReference<JSONObject> received = new AtomicReference<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        String path = request.getPath();
                        if (path.equals("/prompt")) {
                            received.set(new JSONObject(request.getBody().readUtf8()).getJSONObject("prompt"));
                            return json("{\"prompt_id\":\"ordinary-text-audit\"}");
                        }
                        if (path.equals("/queue")) return json("{\"queue_running\":[],\"queue_pending\":[]}");
                        if (path.startsWith("/history/")) return json("{}");
                        if (path.equals("/object_info")) return json("{}");
                        return new MockResponse().setResponseCode(404);
                    } catch (Exception e) { return new MockResponse().setResponseCode(500).setBody(e.toString()); }
                }
                MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
            });
            server.start();
            app.getSharedPreferences("comfy_remote", 0).edit()
                    .putString("server", server.url("/").toString()).putString("generation_jobs_v18", "[]").commit();
            JSONArray uiNodes = new JSONArray(), uiLinks = new JSONArray();
            int nextLink = 1, index = 0;
            Iterator<String> nodeIds = expected.keys();
            while (nodeIds.hasNext()) {
                String id = nodeIds.next(); JSONObject source = expected.getJSONObject(id);
                JSONArray inputs = new JSONArray();
                JSONObject values = source.getJSONObject("inputs"); Iterator<String> names = values.keys();
                while (names.hasNext()) {
                    String name = names.next(); JSONArray link = values.optJSONArray(name);
                    if (link == null || link.length() != 2) continue;
                    inputs.put(new JSONObject().put("name", name).put("link", nextLink));
                    uiLinks.put(new JSONArray().put(nextLink++).put(Integer.parseInt(link.getString(0)))
                            .put(link.getInt(1)).put(Integer.parseInt(id)).put(inputs.length() - 1).put("STRING"));
                }
                uiNodes.put(new JSONObject().put("id", Integer.parseInt(id)).put("type", source.getString("class_type"))
                        .put("mode", 0).put("inputs", inputs).put("pos", new JSONArray().put(40 + index++ * 150).put(40)));
            }
            uiNodes.put(new JSONObject().put("id", 900).put("type", "SaveImage").put("mode", 0)
                    .put("pos", new JSONArray().put(1200).put(40)).put("inputs", new JSONArray()));
            JSONObject ui = new JSONObject().put("nodes", uiNodes).put("links", uiLinks).put("groups", new JSONArray()
                    .put(new JSONObject().put("title", "Prompt enhancement").put("bounding", new JSONArray().put(0).put(0).put(1000).put(1000)))
                    .put(new JSONObject().put("title", "Other feature").put("bounding", new JSONArray().put(1100).put(0).put(600).put(1000))));
            WorkflowProfile profile = WorkflowProfile.create("Ordinary text submission audit", expected, ui);
            profile.initializeCollection(new JSONArray()
                    .put(new JSONObject().put("id", "group:0").put("group_index", 0).put("title", "Prompt enhancement").put("outputs", new JSONArray().put("239")))
                    .put(new JSONObject().put("id", "group:1").put("group_index", 1).put("title", "Other feature").put("outputs", new JSONArray().put("900"))), "group:0");
            profile.setOutputChoices(Collections.singletonList(new WorkflowUtils.OutputChoice("239", "Text output", "TE_text_display")), true, false);
            WorkflowStore.upsert(app, profile); WorkflowStore.setActiveId(app, profile.id);
            ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
            try {
                Button submit = (Button) field(controller.get(), "submitButton");
                assertTrue("The saved text workflow must be ready", submit.isEnabled());
                submit.performClick();
                long deadline = System.currentTimeMillis() + 15000;
                while (received.get() == null && System.currentTimeMillis() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50);
                }
                JSONObject sent = received.get(); assertNotNull("The app must send the actual request", sent);
                assertEquals(expected.getJSONObject("254").getJSONObject("inputs").getString("主模型"),
                        sent.getJSONObject("254").getJSONObject("inputs").getString("主模型"));
                assertEquals(expected.getJSONObject("240").getJSONObject("inputs").getString("text"),
                        sent.getJSONObject("240").getJSONObject("inputs").getString("text"));
                Path output = Paths.get(System.getenv("AUDIT_OUTPUT"));
                Files.createDirectories(output.getParent()); Files.write(output, sent.toString(2).getBytes(StandardCharsets.UTF_8));
                for (JobRecord record : JobStore.list(app))
                    JobStore.updateStatus(app, record.localId, JobRecord.FAILED, "Read-only audit finished", 0);
            } finally { controller.pause().stop().destroy(); }
        }
    }
}
