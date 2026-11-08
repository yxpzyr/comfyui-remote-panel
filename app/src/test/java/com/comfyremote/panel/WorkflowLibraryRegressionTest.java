package com.comfyremote.panel;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import java.util.List;

import static org.junit.Assert.*;

/** Exercises real Activity lifecycle and metadata-only disk reads with oversized workflows. */
@RunWith(RobolectricTestRunner.class)
public class WorkflowLibraryRegressionTest {
    @Test public void libraryCanOpenWithoutLoadingAllLargeGraphsAndRenamePreservesData() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < 140_000; i++) padding.append('x');
        String largeUi = new JSONObject().put("nodes", new JSONArray())
                .put("large_custom_node_metadata", padding.toString()).toString();
        String firstId = null;
        for (int i = 0; i < 12; i++) {
            WorkflowProfile item = WorkflowProfile.create("测试大型工作流 " + i,
                    new JSONObject().put("1", new JSONObject().put("class_type", "SaveImage")),
                    new JSONObject(largeUi));
            item.folderId = "test-folder";
            WorkflowStore.upsert(app, item);
            if (i == 0) firstId = item.id;
        }
        List<WorkflowProfile> summaries = WorkflowStore.loadSummaries(app);
        assertEquals(12, summaries.size());
        for (WorkflowProfile p : summaries) {
            assertTrue(p.metadataOnly);
            assertEquals("{}", p.promptJson);
            assertNull(p.uiJson);
        }
        WorkflowProfile edited = WorkflowStore.find(summaries, firstId);
        assertNotNull(edited);
        edited.favorite = true;
        edited.name = "测试大型工作流 已重命名";
        edited.folderId = "";
        WorkflowStore.upsert(app, edited);
        WorkflowProfile restored = WorkflowStore.loadById(app, firstId);
        assertEquals(largeUi, restored.uiJson);
        assertFalse(restored.metadataOnly);
        assertTrue(restored.favorite);
        assertEquals("测试大型工作流 已重命名", restored.name);
        assertEquals("", restored.folderId);
        assertEquals("SaveImage", restored.promptObject().getJSONObject("1").getString("class_type"));

        ActivityController<WorkflowLibraryActivity> activity =
                Robolectric.buildActivity(WorkflowLibraryActivity.class).setup();
        assertNotNull(activity.get().getWindow().getDecorView());
        activity.pause().stop().destroy();
    }

    @Test public void savedCollectionVariantRestoresPromptWithoutRecompile() throws Exception {
        WorkflowProfile profile = WorkflowProfile.create("合集",
                new JSONObject().put("nodeA", new JSONObject().put("class_type", "SaveImage")), null);
        JSONArray variants = new JSONArray()
                .put(new JSONObject().put("id", "first"))
                .put(new JSONObject().put("id", "second"));
        profile.initializeCollection(variants, "first");
        profile.rememberActiveVariant();
        assertTrue(profile.hasSavedVariantPrompt("first"));
        assertFalse(profile.hasSavedVariantPrompt("second"));
        JSONObject second = new JSONObject().put("nodeB", new JSONObject().put("class_type", "SaveVideo"));
        profile.activateVariant("second", second);
        assertEquals("SaveVideo", profile.promptObject().getJSONObject("nodeB").getString("class_type"));
        assertTrue(profile.hasSavedVariantPrompt("first"));
        // Existing branch must be restored even when compiler input is empty.
        profile.activateVariant("first", new JSONObject());
        assertEquals("SaveImage", profile.promptObject().getJSONObject("nodeA").getString("class_type"));
    }
}
