package com.comfyremote.panel;

import android.app.Application;
import android.net.Uri;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Tests independent media node discovery and real streaming multipart upload. */
@RunWith(RobolectricTestRunner.class)
public class VideoInputRegressionTest {
    private JSONObject node(String type, String key, Object value) throws Exception {
        return new JSONObject().put("class_type", type)
                .put("inputs", new JSONObject().put(key, value));
    }

    @Test public void detectsVhsAndNativeVideoFilesWithoutMistakingThemForPictures() throws Exception {
        JSONObject graph = new JSONObject()
                .put("17", node("VHS_LoadVideo", "video", "source.mp4"))
                .put("18", node("LoadVideo", "video", "film.webm"))
                .put("19", node("LoadImage", "image", "photo.png"));
        List<WorkflowUtils.NodeChoice> videos = WorkflowUtils.findLoadVideoNodes(graph);
        assertEquals(2, videos.size());
        for (WorkflowUtils.NodeChoice n : videos) {
            assertTrue(n.videoInput);
            assertFalse(n.maskCapable);
            assertEquals("video", n.inputName);
        }
        assertEquals(1, WorkflowUtils.findLoadImageNodes(graph).size());
        assertEquals(3, WorkflowUtils.findLoadMediaNodes(graph).size());
    }

    @Test public void avoidsAccidentallyReplacingRemoteUrlOrAbsolutePath() throws Exception {
        JSONObject graph = new JSONObject()
                .put("1", node("VHS_LoadVideoPath", "video", "D:\\Movies\\x.mp4"))
                .put("2", node("LoadVideo", "video", "https://example.com/x.mp4"))
                .put("3", node("CustomLoadVideoFromURL", "video", "https://example.com/x.mp4"))
                .put("4", node("SaveVideo", "video", "out.mp4"))
                .put("5", node("LoadVideo", "video", "local.mp4"));
        List<WorkflowUtils.NodeChoice> selected = WorkflowUtils.findLoadVideoNodes(graph);
        assertEquals(1, selected.size());
        assertEquals("5", selected.get(0).id);
    }

    @Test public void uploadedVideoIsByteIdenticalAndFilenameCanBeSubmitted() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File file = File.createTempFile("comfy_input_", ".mp4", context.getCacheDir());
        byte[] source = "SIMULATED_MP4_VIDEO_CONTENT_NOT_A_PNG".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), source);
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody(
                "{\"name\":\"mobile_video_test.mp4\",\"subfolder\":\"\",\"type\":\"input\"}")
                .setHeader("Content-Type", "application/json"));
        server.start();
        try {
            ComfyApiClient client = new ComfyApiClient(server.url("/").toString());
            ComfyApiClient.UploadResult uploaded = client.uploadVideo(context, Uri.fromFile(file));
            assertEquals("mobile_video_test.mp4", uploaded.workflowFilename());
            RecordedRequest request = server.takeRequest();
            assertEquals("/upload/image", request.getPath());
            assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data"));
            assertTrue(request.getHeader("Transfer-Encoding") != null);
            assertTrue(request.getBody().readUtf8().contains("SIMULATED_MP4_VIDEO_CONTENT_NOT_A_PNG"));
            JSONObject prompt = new JSONObject().put("17", node("VHS_LoadVideo", "video", "old.mp4"));
            WorkflowUtils.setInputValue(prompt, "17", "video", uploaded.workflowFilename());
            assertEquals("mobile_video_test.mp4",
                    prompt.getJSONObject("17").getJSONObject("inputs").getString("video"));
        } finally {
            server.shutdown();
            file.delete();
        }
    }

    @Test public void rejectsRenamedNonVideoExtensionBeforeNetworkRequest() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File file = File.createTempFile("not_video_", ".png", context.getCacheDir());
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        try {
            new ComfyApiClient("http://127.0.0.1:1").uploadVideo(context, Uri.fromFile(file));
            fail("Expected unsupported filename");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("不支持的视频文件扩展名"));
        } finally { file.delete(); }
    }
}
