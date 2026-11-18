package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class H3DirectorPlanTest {
    private JSONObject fixture(String suffix) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/h3-director-" + suffix + ".json")) {
            assertNotNull(input); return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private JSONObject draft(JSONObject original) throws Exception { return H3DirectorPlan.fromPrompt(original, "workflow:variant"); }
    private JSONObject compile(JSONObject source, JSONObject draft) throws Exception {
        return H3DirectorPlan.compile(source, draft, Collections.emptyMap(), fixture("schema"), "request-a");
    }
    private JSONObject timeline(JSONObject prompt) throws Exception {
        return new JSONObject(prompt.getJSONObject("5").getJSONObject("inputs").getString("timeline_data"));
    }

    @Test public void compilesTwoShotsAndOnlyKeepsTheVideoOutputBranch() throws Exception {
        JSONObject source=fixture("api");
        source.put("text",new JSONObject().put("class_type","TE_text_display").put("inputs",new JSONObject()));
        String before=source.toString(); JSONObject draft=draft(source), result=compile(source,draft), timeline=timeline(result);
        assertEquals(2,timeline.getJSONArray("segments").length());
        assertEquals(248,timeline.getInt("totalFrames"));
        assertFalse(result.has("text"));
        assertEquals(H3DirectorPlan.EXPORT_TYPE,result.getJSONObject(H3DirectorPlan.EXPORT_ID).getString("class_type"));
        assertFalse(TextOutputs.expectsText(result));
        assertEquals(before,source.toString());
        assertTrue(timeline.getJSONObject("output").getBoolean("continuityEnabled"));
        assertEquals("segments",timeline.getJSONObject("output").getString("exportMode"));
    }

    @Test public void referenceModeSelectsMatchingWeightsAndTheUploadedPictureOnly() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);
        draft.put("mode","r2v").put("steps",4);
        JSONObject slot=H3DirectorPlan.imageSlot(draft,null,"images",0);
        slot.put("imageFile","old_C.png");H3DirectorPlan.selectImage(slot,"content://new-picture");
        Map<String,String> uploaded=new HashMap<>();uploaded.put("content://new-picture","phone_selected_A.png");
        JSONObject result=H3DirectorPlan.compile(source,draft,uploaded,fixture("schema"),"new-request");
        assertTrue(result.getJSONObject("1").getJSONObject("inputs").getString("unet_name").contains("ref2va"));
        assertTrue(result.getJSONObject("12").getJSONObject("inputs").getString("lora_name").contains("ref2v_turbo_4step"));
        JSONArray refs=timeline(result).getJSONObject("global").getJSONArray("refs");
        assertEquals(1,refs.length());assertEquals("phone_selected_A.png",refs.getJSONObject(0).getString("imageFile"));
        assertFalse(result.toString().contains("old_C.png"));assertFalse(result.toString().contains("content://"));
    }

    @Test public void clearingAnImportedSecondPictureDoesNotRestoreItsOriginalValue() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);draft.put("mode","r2v").put("steps",4);
        H3DirectorPlan.imageSlot(draft,null,"images",0).put("imageFile","selected_A.png");
        JSONObject removed=H3DirectorPlan.imageSlot(draft,null,"images",1);removed.put("imageFile","unselected_C.png");
        H3DirectorPlan.clearImage(removed);
        JSONObject result=compile(source,draft);
        assertEquals(1,timeline(result).getJSONObject("global").getJSONArray("refs").length());
        assertFalse(result.toString().contains("unselected_C.png"));
    }

    @Test public void clearingAllReferenceImagesFailsInsteadOfSilentlyGeneratingTextVideo() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);draft.put("mode","r2v");
        JSONObject slot=H3DirectorPlan.imageSlot(draft,null,"images",0);slot.put("imageFile","old.png");H3DirectorPlan.clearImage(slot);
        try { compile(source,draft); fail("Missing reference must be reported"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("缺少参考图")); }
    }

    @Test public void textModeNeitherUploadsNorSerializesHiddenReferencePictures() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);
        H3DirectorPlan.selectImage(H3DirectorPlan.imageSlot(draft,null,"images",0),"content://hidden-reference");
        assertTrue(H3DirectorPlan.localUris(draft).isEmpty());
        assertFalse(compile(source,draft).toString().contains("hidden-reference"));
    }

    @Test public void missingUploadCannotFallBackToAnOldServerPicture() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);draft.put("mode","r2v");
        JSONObject slot=H3DirectorPlan.imageSlot(draft,null,"images",0);
        slot.put("localUri","content://not-uploaded").put("imageFile","old_fallback.png");
        try { compile(source,draft);fail("Must wait for the selected image upload"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("尚未上传")); }
    }

    @Test public void firstFrameModeMergesCommonPromptOnceAndHonorsTheSecondShotSwitch() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);draft.put("mode","fl2v").put("common_prompt","Shared pond appearance");
        JSONArray shots=draft.getJSONArray("shots");
        shots.getJSONObject(0).put("prompt","First action");shots.getJSONObject(1).put("prompt","Continue action").put("continuity",false);
        String id=shots.getJSONObject(0).getString("id");
        H3DirectorPlan.imageSlot(draft,id,"first",0).put("imageFile","first_frame.png");
        JSONObject result=compile(source,draft);JSONArray plans=timeline(result).getJSONArray("shots");
        assertEquals("Shared pond appearance\nFirst action",plans.getJSONObject(0).getString("prompt"));
        assertEquals("first_frame.png",plans.getJSONObject(0).getJSONObject("startImage").getString("imageFile"));
        assertFalse(plans.getJSONObject(1).getBoolean("continuityFromPrev"));
        assertEquals(0,plans.getJSONObject(1).getJSONObject("startImage").length());
    }

    @Test public void pictureTargetsFollowShotIdsWhenCardsAreReordered() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);JSONArray shots=draft.getJSONArray("shots");
        JSONObject first=shots.getJSONObject(0),second=shots.getJSONObject(1);
        String target=first.getString("id");shots.put(0,second);shots.put(1,first);
        H3DirectorPlan.selectImage(H3DirectorPlan.imageSlot(draft,target,"first",0),"content://chosen-for-first");
        assertEquals("content://chosen-for-first",shots.getJSONObject(1).getJSONObject("first").getString("localUri"));
        assertEquals(0,shots.getJSONObject(0).getJSONObject("first").length());
        shots.remove(1);
        try { H3DirectorPlan.imageSlot(draft,target,"first",0);fail("Deleted target cannot map to a different card"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("已被删除")); }
    }

    @Test public void queuedSnapshotIsIsolatedFromLaterDraftEdits() throws Exception {
        JSONObject source=fixture("api"),draft=draft(source);
        draft.getJSONArray("shots").getJSONObject(0).put("prompt","Captured action");
        JSONObject snapshot=new JSONObject(draft.toString());
        draft.getJSONArray("shots").getJSONObject(0).put("prompt","Later edit");
        JSONObject result=compile(source,snapshot);
        assertEquals("Captured action",timeline(result).getJSONArray("segments").getJSONObject(0).getString("prompt"));
        assertFalse(result.toString().contains("Later edit"));
    }

    @Test public void variantsWithTheSameWorkflowPrefixUseDifferentCaches() throws Exception {
        JSONObject source=fixture("api");
        JSONObject a=H3DirectorPlan.fromPrompt(source,"same-long-workflow-identifier:variant-a");
        JSONObject b=H3DirectorPlan.fromPrompt(source,"same-long-workflow-identifier:variant-b");
        assertNotEquals(a.getString("cache"),b.getString("cache"));
    }

    @Test public void externalGroupsAreNotSilentlyIgnored() throws Exception {
        JSONObject source=fixture("api");source.getJSONObject("5").getJSONObject("inputs").put("r2v_groups",new JSONArray().put("external").put(0));
        try { draft(source);fail("External groups override the editor and must be rejected"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("外接分组")); }
    }

    @Test public void durationsUseTheRealH3FrameGrid() throws Exception {
        assertEquals(124,H3DirectorPlan.alignedFrames(5));assertEquals(243,H3DirectorPlan.alignedFrames(10));
        JSONObject source=fixture("api"),draft=draft(source);draft.getJSONArray("shots").getJSONObject(0).put("seconds",6);
        assertEquals(282,timeline(compile(source,draft)).getInt("totalFrames"));
        try { H3DirectorPlan.alignedFrames(Double.NaN);fail(); } catch (Exception expected) { assertTrue(expected.getMessage().contains("时长")); }
    }
}
