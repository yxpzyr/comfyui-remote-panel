package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Regression checks against native SaveVideo's real history shape and legacy image records. */
public class MediaOutputTest {
    private JSONObject file(String filename, String subfolder) throws Exception {
        return new JSONObject().put("filename", filename).put("subfolder", subfolder).put("type", "output");
    }

    private JSONObject history(String nodeId, String key, String filename) throws Exception {
        JSONObject output = new JSONObject().put(key, new JSONArray().put(file(filename, "video")));
        JSONObject entry = new JSONObject().put("outputs", new JSONObject().put(nodeId, output));
        return new JSONObject().put("test-prompt", entry);
    }

    @Test public void nativeSaveVideoUsesLegacyImagesKey() throws Exception {
        List<ImageRef> refs = new ComfyApiClient("8188").parseAllImagesForPrompt(
                history("189", "images", "MiniMax_H3_00001_.mp4"), "test-prompt");
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).isVideo());
        assertEquals("189", refs.get(0).sourceNodeId);
        assertEquals("video/MiniMax_H3_00001_.mp4", refs.get(0).subfolder + "/" + refs.get(0).filename);
    }

    @Test public void alternativeVideosFieldIsSupported() throws Exception {
        List<ImageRef> refs = new ComfyApiClient("8188").parseAllImagesForPrompt(
                history("285", "videos", "sample.webm"), "test-prompt");
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).isVideo());
    }

    @Test public void imagesRemainImagesAndOldRecordsStillRead() throws Exception {
        JSONObject item = file("old_photo.png", "");
        JSONObject entry = new JSONObject().put("outputs", new JSONObject().put("55",
                new JSONObject().put("images", new JSONArray().put(item))));
        List<ImageRef> refs = new ComfyApiClient("8188").parseAllImagesForPrompt(
                new JSONObject().put("test-prompt", entry), "test-prompt");
        assertEquals(1, refs.size());
        assertFalse(refs.get(0).isVideo());
        assertEquals("output", refs.get(0).type);
        assertFalse(ImageRef.fromJson(refs.get(0).toJson()).isVideo());
    }

    @Test public void selectedOutputNodeFilteringDoesNotBreakVideo() throws Exception {
        JSONObject outputs = new JSONObject()
                .put("189", new JSONObject().put("images", new JSONArray().put(file("a.mp4", "video"))))
                .put("193", new JSONObject().put("images", new JSONArray().put(file("b.mp4", "video"))));
        JSONObject history = new JSONObject().put("test-prompt", new JSONObject().put("outputs", outputs));
        List<ImageRef> refs = new ComfyApiClient("8188").parseAllImagesForPrompt(
                history, "test-prompt", Set.of("189"));
        assertEquals(1, refs.size());
        assertEquals("a.mp4", refs.get(0).filename);
    }

    @Test public void newestSavedVideoCanBeFinalMedia() {
        List<ImageRef> refs = new ArrayList<>();
        refs.add(new ImageRef("preview.png", "", "temp", 42L, "p", "1", 100L, 1));
        refs.add(new ImageRef("result.mp4", "video", "output", 43L, "p", "189", 200L, 2));
        ImageRef chosen = ImageRef.chooseFinalSaved(refs);
        assertNotNull(chosen);
        assertEquals("result.mp4", chosen.filename);
        assertTrue(chosen.isVideo());
    }

    @Test public void recognizeSupportedVideoContainers() {
        for (String filename : new String[]{"a.mp4", "a.WEBM", "a.mkv", "a.mov", "a.m4v", "a.avi"})
            assertTrue(filename, new ImageRef(filename, "video", "output").isVideo());
        for (String filename : new String[]{"image.png", "photo.jpg", "photo.webp"})
            assertFalse(filename, new ImageRef(filename, "", "output").isVideo());
    }
}
