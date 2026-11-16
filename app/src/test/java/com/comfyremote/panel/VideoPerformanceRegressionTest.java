package com.comfyremote.panel;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.widget.ImageView;
import android.widget.ListView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VideoPerformanceRegressionTest {
    @org.junit.After public void resetVideoDecoder() { VideoThumbnailLoader.decoder = VideoThumbnailLoader.NATIVE_DECODER; }
    private Application app() { return RuntimeEnvironment.getApplication(); }
    private ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
    private String repeated(int size) { return "x".repeat(size); }
    private void legacy(JSONArray records) {
        app().getSharedPreferences("comfy_remote", 0).edit()
                .putString("generation_jobs_v18", records.toString()).commit();
    }
    private Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true); return f.get(object);
    }

    @Test public void imageApisRejectVideoWithoutNetwork() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String base = server.url("/").toString();
            ImageRef video = new ImageRef("result.mp4", "video", "output");
            ComfyApiClient api = new ComfyApiClient(base);
            try { api.fetchImage(video); fail("Video must never become an in-memory image download"); }
            catch (IllegalArgumentException expected) {}
            try { api.fetchImagePreview(video, 58, 4096, () -> true); fail("No MP4 image preview"); }
            catch (IllegalArgumentException expected) {}
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void streamingHistoryRetainsVideoAndErrorsWithoutEmbeddedGraphs() throws Exception {
        String raw = "{\"p\":{\"prompt\":[0,\"p\",{\"huge\":\"" + repeated(2_000_000)
                + "\"}],\"status\":{\"status_str\":\"success\",\"completed\":true},"
                + "\"outputs\":{\"189\":{\"gifs\":[{\"filename\":\"clip.mp4\",\"type\":\"output\"}]}}}}";
        JSONObject parsed = ComfyApiClient.readHistoryStatus(input(raw), "p");
        assertFalse(parsed.getJSONObject("p").has("prompt"));
        ComfyApiClient api = new ComfyApiClient("8188");
        assertTrue(api.isHistoryCompleted(parsed, "p"));
        assertTrue(api.parseAllImagesForPrompt(parsed, "p").get(0).isVideo());
        assertTrue(parsed.toString().length() < 500);
        JSONObject failed = ComfyApiClient.readHistoryStatus(input(
                "{\"p\":{\"status\":{\"status_str\":\"error\",\"messages\":["
                + "[\"execution_error\",{\"node_id\":\"189\",\"exception_message\":\"encoder failed\"}]]}}}"), "p");
        assertTrue(api.historyError(failed, "p").contains("encoder failed"));
    }

    @Test public void queueReadsOnlyPromptIdsAndSharesOneRequestAcrossTasks() throws Exception {
        String queue = "{\"queue_running\":[[1,\"running-id\",{\"fake-id\":\"" + repeated(500_000)
                + "\"},{}]],\"queue_pending\":[[2,\"pending-id\",{},{}]]}";
        Map<String, String> states = ComfyApiClient.readQueueStates(input(queue));
        assertEquals(2, states.size());
        assertEquals(ComfyApiClient.QUEUE_RUNNING, states.get("running-id"));
        assertEquals(ComfyApiClient.QUEUE_PENDING, states.get("pending-id"));
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(queue));
            server.start();
            assertEquals(ComfyApiClient.QUEUE_RUNNING,
                    new ComfyApiClient(server.url("/").toString()).getQueueState("running-id"));
            assertEquals(ComfyApiClient.QUEUE_PENDING,
                    new ComfyApiClient(server.url("/").toString()).getQueueState("pending-id"));
            assertEquals(ComfyApiClient.QUEUE_UNKNOWN,
                    new ComfyApiClient(server.url("/").toString()).getQueueState("fake-id"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void mobileSubmissionDisablesUnusedBinaryPreviews() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"prompt_id\":\"p\"}"));
            server.start();
            assertEquals("p", new ComfyApiClient(server.url("/").toString()).queuePrompt(new JSONObject()));
            JSONObject body = new JSONObject(server.takeRequest().getBody().readUtf8());
            assertEquals("none", body.getJSONObject("extra_data").getString("preview_method"));
            assertTrue(body.has("client_id"));
        }
    }

    @Test public void legacyGraphsMigrateWithoutChangingSubmissionOrRewritingPayloadOnProgress() throws Exception {
        JobRecord job = JobRecord.create("wf", "video", "", "", "8188",
                new JSONObject().put("huge", repeated(1_000_000)).toString());
        job.status = JobRecord.UPLOADING;
        JSONArray original = new JSONArray().put(job.toJson());
        legacy(original);
        List<JobRecord> metadata = JobStore.list(app());
        assertEquals(1, metadata.size());
        assertEquals("", metadata.get(0).promptJson);
        assertEquals(JobRecord.LOCAL_QUEUED, metadata.get(0).status);
        assertTrue(metadata.get(0).hasSubmissionPayload());
        assertEquals(job.promptJson, JobStore.loadSubmission(app(), job.localId).promptJson);
        File directory = new File(app().getFilesDir(), "job_payloads_v289");
        assertEquals(original.toString(), new String(Files.readAllBytes(
                new File(directory, "legacy_index_backup.json").toPath()), StandardCharsets.UTF_8));
        File payload = new File(directory, job.localId + ".json");
        assertTrue(payload.setLastModified(1_000_000L));
        JobStore.attachPrompt(app(), job.localId, "p", "client-restored");
        for (int i = 0; i < 100; i++) JobStore.updateProgress(app(), job.localId, i, "42", "running");
        assertEquals(1_000_000L, payload.lastModified());
        assertEquals("client-restored", JobStore.find(app(), job.localId).clientId);
        String compact = app().getSharedPreferences("comfy_remote", 0).getString("generation_jobs_v18", "");
        assertTrue(compact.length() < 2000);
        metadata.get(0).workflowName = "mutated copy";
        assertEquals("video", JobStore.find(app(), job.localId).workflowName);
    }

    @Test public void malformedLegacyHistoryNeverOverwritesOriginalIndex() {
        String malformed = "[{\"local_id\":\"preserve\"},";
        app().getSharedPreferences("comfy_remote", 0).edit().putString("generation_jobs_v18", malformed).commit();
        try { JobStore.list(app()); fail("Surface corruption without erasing history"); }
        catch (IllegalStateException expected) {}
        assertEquals(malformed, app().getSharedPreferences("comfy_remote", 0).getString("generation_jobs_v18", ""));
    }

    @Test public void newJobPayloadIsRecoverableAndMetadataRemainsSmall() {
        legacy(new JSONArray());
        String payload = repeated(1_000_000);
        JobRecord job = JobRecord.create("wf", "video", "", "", "8188", payload);
        JobStore.add(app(), job);
        assertTrue(job.hasSubmissionPayload());
        assertEquals("", JobStore.find(app(), job.localId).promptJson);
        assertEquals(payload, JobStore.loadSubmission(app(), job.localId).promptJson);
        JobStore.updateStatus(app(), job.localId, JobRecord.COMPLETED, "done", 0);
        JobStore.clearFinished(app());
        assertTrue(JobStore.list(app()).isEmpty());
        assertFalse(new File(new File(app().getFilesDir(), "job_payloads_v289"), job.localId + ".json").exists());
    }

    @Test public void largeUiStateUsesSmallBundleAndRestoresExactParameters() throws Exception {
        JSONObject state = new JSONObject().put("workflow", "wf").put("scroll", 1800)
                .put("parameters", new JSONObject().put("prompt", repeated(2_000_000)));
        Bundle out = new Bundle();
        MainSessionState.save(app(), out, state);
        Parcel parcel = Parcel.obtain();
        try { out.writeToParcel(parcel, 0); assertTrue(parcel.dataSize() < 1024); }
        finally { parcel.recycle(); }
        assertEquals(state.toString(), MainSessionState.restore(app(), out).toString());
        assertNull(MainSessionState.restore(app(), new Bundle()));
        Bundle malicious = new Bundle(); malicious.putString("panel_ui_session", "../invalid");
        assertNull(MainSessionState.restore(app(), malicious));
    }

    @Test public void queueLifecycleWith300VideosRecyclesRowsAndDoesNotFetchVideos() throws Exception {
        VideoThumbnailLoader.decoder = (url, wanted) ->
                android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.RGB_565);
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String base = server.url("/").toString();
            app().getSharedPreferences("comfy_remote", Context.MODE_PRIVATE).edit().putString("server", base).commit();
            JSONArray records = new JSONArray();
            for (int i = 0; i < 300; i++) {
                JobRecord job = JobRecord.create("wf", "video " + i, "", "", base, "");
                job.status = JobRecord.COMPLETED;
                job.promptId = "p" + i;
                job.outputRefsJson = new JSONArray().put(new ImageRef("clip" + i + ".mp4", "", "output").toJson()).toString();
                records.put(job.toJson());
            }
            legacy(records);
            ActivityController<QueueActivity> controller = Robolectric.buildActivity(QueueActivity.class).setup();
            QueueActivity activity = controller.get();
            ExecutorService pool = (ExecutorService) field(activity, "listPool");
            pool.submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ListView list = (ListView) field(activity, "listContainer");
            assertEquals(300, list.getAdapter().getCount());
            list.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, 1073741824),
                    android.view.View.MeasureSpec.makeMeasureSpec(1800, 1073741824));
            list.layout(0, 0, 1080, 1800);
            assertTrue("Only visible rows are allocated", list.getChildCount() < 20);
            controller.pause().stop().start().resume();
            pool.submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            controller.pause().stop().destroy();
            assertTrue(pool.isShutdown());
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void uploadReceiptsAreScopedToServerAndChangedInputAndCanBeInvalidated() throws Exception {
        File file = new File(app().getCacheDir(), "input.mp4");
        Files.write(file.toPath(), "video".getBytes(StandardCharsets.UTF_8));
        android.net.Uri uri = android.net.Uri.fromFile(file);
        String key = InputUploadReceipts.key(app(), "server-one", uri, "", true);
        assertFalse(key.isEmpty());
        InputUploadReceipts.remember(app(), key, "uploads/input.mp4");
        assertEquals("uploads/input.mp4", InputUploadReceipts.find(app(), key));
        assertEquals("", InputUploadReceipts.find(app(), InputUploadReceipts.key(app(), "server-two", uri, "", true)));
        Files.write(file.toPath(), "changed content".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(key, InputUploadReceipts.key(app(), "server-one", uri, "", true));
        InputUploadReceipts.forget(app(), key);
        assertEquals("", InputUploadReceipts.find(app(), key));
    }

    @Test public void homeActivityRecreationRestoresUnsavedParametersAndExpansion() throws Exception {
        VideoThumbnailLoader.decoder = (url, wanted) ->
                android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.RGB_565);
        legacy(new JSONArray());
        WorkflowProfile profile = WorkflowProfile.create("recreation test",
                new JSONObject().put("1", new JSONObject().put("class_type", "SaveImage")
                        .put("inputs", new JSONObject().put("filename_prefix", "test"))), null);
        WorkflowStore.upsert(app(), profile);
        WorkflowStore.setActiveId(app(), profile.id);
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity screen = first.get();
        Map<String, Object> values = (Map<String, Object>) field(screen, "transientParameterValues");
        values.put("unsaved-test-value", "keep my prompt");
        JobRecord completed = JobRecord.create(profile.id, "video", "", "", "8188", "");
        completed.status = JobRecord.COMPLETED; completed.promptId = "completed-video";
        completed.outputRefsJson = new JSONArray().put(
                new ImageRef("clip.mp4", "", "output", 1L, completed.promptId, "1", 1L, 1).toJson()).toString();
        java.lang.reflect.Method load = MainActivity.class.getDeclaredMethod("loadJobOutputs", JobRecord.class);
        load.setAccessible(true); load.invoke(screen, completed);
        ((ExecutorService) field(screen, "pool")).submit(() -> {}).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        android.widget.LinearLayout outputs = (android.widget.LinearLayout) field(screen, "outputList");
        for (int attempt = 0; field(screen, "currentOutputJob") != completed && attempt < 200; attempt++) {
            Thread.sleep(10);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertSame(completed, field(screen, "currentOutputJob"));
        android.view.View previousCard = outputs.getChildAt(0);
        assertNotNull(previousCard);
        first.pause().stop().start().resume();
        assertNotSame("Resume restarts any thumbnail work cancelled while hidden", previousCard, outputs.getChildAt(0));
        assertEquals(1, ((List<?>) field(screen, "currentOutputRefs")).size());
        java.lang.reflect.Field advanced = MainActivity.class.getDeclaredField("advancedExpanded");
        advanced.setAccessible(true); advanced.setBoolean(screen, true);
        Bundle state = new Bundle();
        first.pause().saveInstanceState(state).stop().destroy();
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).create(state).start().resume();
        assertEquals("keep my prompt", ((Map<?, ?>) field(second.get(), "transientParameterValues")).get("unsaved-test-value"));
        assertTrue((Boolean) field(second.get(), "advancedExpanded"));
        assertEquals(profile.id, ((WorkflowProfile) field(second.get(), "activeProfile")).id);
        second.pause().stop().destroy();
        assertTrue(((ExecutorService) field(second.get(), "pool")).isShutdown());
    }
}
