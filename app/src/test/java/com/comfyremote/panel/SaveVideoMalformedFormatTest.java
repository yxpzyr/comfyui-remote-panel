package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Exact regression for V2.8.7: SaveVideo#189 format object is missing format key. */
public class SaveVideoMalformedFormatTest {
    private static JSONObject schema() throws Exception {
        JSONObject format = new JSONObject().put("options", new JSONArray()
                .put("auto").put("mp4").put("mkv").put("mov").put("webm"));
        JSONObject required = new JSONObject().put("format",
                new JSONArray().put("COMBO").put(format));
        return new JSONObject().put("SaveVideo", new JSONObject()
                .put("input", new JSONObject().put("required", required)));
    }

    private static JSONObject uiNode(int id, String selected) throws Exception {
        return new JSONObject().put("id", id).put("type", "SaveVideo")
                .put("inputs", new JSONArray()
                    .put(new JSONObject().put("name", "video"))
                    .put(new JSONObject().put("name", "filename_prefix")
                        .put("widget", new JSONObject().put("name", "filename_prefix")))
                    .put(new JSONObject().put("name", "format")
                        .put("widget", new JSONObject().put("name", "format")))
                    .put(new JSONObject().put("name", "codec")
                        .put("widget", new JSONObject().put("name", "codec"))))
                .put("widgets_values", new JSONArray().put("video/MiniMax_H3")
                        .put(selected).put("auto"));
    }

    private static JSONObject broken(int id, Object format) throws Exception {
        return new JSONObject().put(String.valueOf(id), new JSONObject()
                .put("class_type", "SaveVideo").put("inputs",
                        new JSONObject().put("format", format)
                            .put("filename_prefix", "video/MiniMax_H3")));
    }

    @Test public void originalUiRecoversAllFourSaveVideoBranchesWithoutGuessing() throws Exception {
        int[] ids = {189, 193, 258, 285};
        String[] formats = {"auto", "mp4", "mkv", "webm"};
        JSONObject ui = new JSONObject().put("nodes", new JSONArray());
        JSONObject prompt = new JSONObject();
        for (int i = 0; i < ids.length; i++) {
            ui.getJSONArray("nodes").put(uiNode(ids[i], formats[i]));
            JSONObject brokenNode = broken(ids[i], new JSONObject().put("codec", "auto"))
                    .getJSONObject(String.valueOf(ids[i]));
            prompt.put(String.valueOf(ids[i]), brokenNode);
        }
        assertTrue(SaveVideoFormatGuard.needsUiRecovery(prompt));
        assertEquals(4, SaveVideoFormatGuard.repairConvertedBranch(ui, prompt, schema()));
        assertFalse(SaveVideoFormatGuard.needsUiRecovery(prompt));
        assertEquals(0, SaveVideoFormatGuard.ensureSubmissionFormats(prompt, schema()));
        for (int i = 0; i < ids.length; i++) {
            JSONObject inputs = prompt.getJSONObject(String.valueOf(ids[i])).getJSONObject("inputs");
            assertEquals(formats[i], inputs.getString("format"));
            assertEquals("auto", inputs.getJSONObject("codec").getString("codec"));
            assertEquals("video/MiniMax_H3", inputs.getString("filename_prefix"));
        }
    }

    @Test public void malformedCodecObjectDoesNotPreventSubmissionWhenAutoOptionExists() throws Exception {
        JSONObject prompt = broken(189, new JSONObject().put("codec", "auto"));
        assertEquals(1, SaveVideoFormatGuard.ensureSubmissionFormats(prompt, schema()));
        JSONObject inputs = prompt.getJSONObject("189").getJSONObject("inputs");
        assertEquals("auto", inputs.getString("format"));
        assertEquals("auto", inputs.getJSONObject("codec").getString("codec"));
    }

    @Test public void alreadyNestedValidFormatAndCodecMustRemainUnchanged() throws Exception {
        JSONObject nested = new JSONObject().put("format", "mp4")
                .put("codec", new JSONObject().put("codec", "h264"));
        JSONObject prompt = broken(189, nested);
        assertEquals(0, SaveVideoFormatGuard.ensureSubmissionFormats(prompt, schema()));
        JSONObject actual = prompt.getJSONObject("189").getJSONObject("inputs")
                .getJSONObject("format");
        assertEquals(nested.toString(), actual.toString());
    }

    @Test public void explicitKeyWrapperBecomesServerOption() throws Exception {
        JSONObject prompt = broken(189, new JSONObject().put("key", "mkv"));
        assertEquals(1, SaveVideoFormatGuard.ensureSubmissionFormats(prompt, schema()));
        assertEquals("mkv", prompt.getJSONObject("189").getJSONObject("inputs").getString("format"));
    }

    @Test public void whenNoAutoAndNoUiSourceFailClearlyBeforeSubmission() throws Exception {
        JSONObject noDefault = schema();
        JSONArray choices = new JSONArray().put("mp4").put("mkv");
        noDefault.getJSONObject("SaveVideo").getJSONObject("input")
                .getJSONObject("required").getJSONArray("format")
                .getJSONObject(1).put("options", choices);
        try {
            SaveVideoFormatGuard.ensureSubmissionFormats(
                    broken(189, new JSONObject().put("codec", "h264")), noDefault);
            fail("Must not guess a format not declared as default");
        } catch (JSONException e) {
            assertTrue(e.getMessage().contains("189"));
            assertTrue(e.getMessage().contains("format"));
        }
    }

    @Test public void nativeUiConversionOverwritesMisplacedCodecWithSelectedFormat() throws Exception {
        JSONObject inputs = new JSONObject().put("format",
                new JSONObject().put("codec", "auto"));
        SaveVideoFormatGuard.repairUiNode(uiNode(189, "mp4"), inputs,
                schema().getJSONObject("SaveVideo"));
        assertEquals("mp4", inputs.getString("format"));
        assertEquals("auto", inputs.getJSONObject("codec").getString("codec"));
    }

    @Test public void sourceMissingDoesNotInventCustomEncoding() throws Exception {
        JSONObject withoutFormat = broken(189, JSONObject.NULL);
        assertEquals(1, SaveVideoFormatGuard.ensureSubmissionFormats(withoutFormat, schema()));
        assertEquals("auto", withoutFormat.getJSONObject("189")
                .getJSONObject("inputs").getString("format"));
    }
}
