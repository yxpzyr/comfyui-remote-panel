package com.comfyremote.panel;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DynamicComboRegressionTest {
    static JSONObject option(String key) throws Exception {
        JSONObject required = new JSONObject();
        if (key.equals("on")) required.put("temperature", new JSONArray().put("FLOAT")
                .put(new JSONObject().put("default", 0.7))).put("seed", new JSONArray().put("INT")
                .put(new JSONObject().put("default", 0)));
        return new JSONObject().put("key", key).put("inputs", new JSONObject().put("required", required));
    }
    static JSONObject info() throws Exception {
        return new JSONObject().put("TextGenerate", new JSONObject().put("input", new JSONObject()
                .put("required", new JSONObject().put("sampling_mode", new JSONArray().put("COMFY_DYNAMICCOMBO_V3")
                        .put(new JSONObject().put("options", new JSONArray().put(option("on")).put(option("off"))))))));
    }
    static JSONObject prompt(Object mode) throws Exception {
        JSONObject prompt = new JSONObject();
        for (String id : new String[]{"1901", "1902"}) prompt.put(id, new JSONObject().put("class_type", "TextGenerate")
                .put("inputs", new JSONObject().put("sampling_mode", mode)));
        return prompt;
    }
    static JSONObject ui() throws Exception {
        JSONArray nodes = new JSONArray();
        for (int id : new int[]{1901,1902}) nodes.put(new JSONObject().put("id", id).put("type", "TextGenerate")
                .put("widgets_values_named", new JSONObject().put("sampling_mode", "off")));
        return new JSONObject().put("nodes", nodes);
    }
    @Test public void parameterPanelUsesKeysNotSchemaObjects() throws Exception {
        List<WorkflowUtils.FieldChoice> choices = WorkflowUtils.findEditableFields(prompt("off"), info());
        assertEquals(2, choices.size());
        for (WorkflowUtils.FieldChoice f : choices) {
            assertEquals(Arrays.asList("on", "off"), f.options);
            assertEquals("on", f.rawComboValueAt(0)); assertEquals("off", f.rawComboValueAt(1));
            assertEquals("off", f.value);
        }
    }
    @Test public void cachedOptionDefinitionsAreRepairedWithoutMutatingTheirLists() throws Exception {
        List<Object> raw = new ArrayList<>(Arrays.asList(option("on"), option("off")));
        List<String> display = new ArrayList<>(Arrays.asList(raw.get(0).toString(),raw.get(1).toString()));
        WorkflowUtils.FieldChoice f = new WorkflowUtils.FieldChoice("1901","sampling_mode","Reader","TextGenerate",
                WorkflowUtils.FieldChoice.COMBO,"off",Double.NaN,Double.NaN,Double.NaN,display,raw);
        assertEquals(Arrays.asList("on","off"), f.options);
        assertEquals("off",f.rawComboValueAt(1)); assertTrue(raw.get(0) instanceof JSONObject);
    }
    @Test public void bothFailedReadersRecoverOriginalOffAndLeaveSnapshotsUnchanged() throws Exception {
        JSONObject bad = prompt(option("on")); String before = bad.toString(); JSONObject originalUi = ui();
        assertTrue(PromptValueNormalizer.needsRequiredDynamicInspection(bad));
        assertTrue(DynamicComboGuard.needsUiRecovery(bad, info()));
        JSONObject copy = new JSONObject(before);
        assertEquals(2, DynamicComboGuard.repair(copy, info(), DynamicComboGuard.configuredFromUi(originalUi)));
        for(String id:new String[]{"1901","1902"}) assertEquals("off",copy.getJSONObject(id).getJSONObject("inputs").get("sampling_mode"));
        assertEquals(before,bad.toString()); assertEquals("off",originalUi.getJSONArray("nodes").getJSONObject(0).getJSONObject("widgets_values_named").getString("sampling_mode"));
    }
    @Test public void actualOnSelectionSurvivesAndRequiredBranchDefaultsAreScalar() throws Exception {
        JSONObject p=prompt("on");p.getJSONObject("1901").getJSONObject("inputs").put("sampling_mode.temperature",1.25);
        DynamicComboGuard.repair(p,info(),DynamicComboGuard.configuredFromUi(ui()));
        JSONObject values=p.getJSONObject("1901").getJSONObject("inputs");
        assertEquals("on",values.getString("sampling_mode"));assertEquals(1.25,values.getDouble("sampling_mode.temperature"),0);
        assertEquals(0,values.getInt("sampling_mode.seed"));
        assertEquals(0.7,p.getJSONObject("1902").getJSONObject("inputs").getDouble("sampling_mode.temperature"),0);
    }
    @Test public void nestedRuntimeSettingsAreFlattenedButSchemaDefinitionsAreNotSent() throws Exception {
        JSONObject p=prompt(new JSONObject().put("sampling_mode","on").put("temperature",0.4));
        DynamicComboGuard.repair(p,info(),DynamicComboGuard.configuredFromUi(ui()));
        JSONObject values=p.getJSONObject("1901").getJSONObject("inputs");
        assertEquals("on",values.getString("sampling_mode"));assertEquals(0.4,values.getDouble("sampling_mode.temperature"),0);
        assertFalse(values.has("inputs"));
    }
    @Test public void missingChoiceRecoversFromUiAndOffDoesNotCreateOnParameters() throws Exception {
        JSONObject p=prompt("off");p.getJSONObject("1901").getJSONObject("inputs").remove("sampling_mode");
        DynamicComboGuard.repair(p,info(),DynamicComboGuard.configuredFromUi(ui()));
        JSONObject values=p.getJSONObject("1901").getJSONObject("inputs");
        assertEquals("off",values.get("sampling_mode"));assertFalse(values.has("sampling_mode.temperature"));
        assertEquals(0,PromptValueNormalizer.ensureRequiredDynamicInputs(p,info()));
    }
    @Test public void unrelatedObjectsAndDedicatedSaveVideoGuardAreUntouched() throws Exception {
        JSONObject p=prompt("off");JSONObject video=new JSONObject().put("class_type","SaveVideo")
                .put("inputs",new JSONObject().put("format",new JSONObject().put("codec","h264")));
        p.put("video",video);p.getJSONObject("1901").getJSONObject("inputs").put("custom",new JSONObject().put("keep",true));
        String before=video.toString();DynamicComboGuard.repair(p,info(),null);
        assertEquals(before,video.toString());assertTrue(p.getJSONObject("1901").getJSONObject("inputs").getJSONObject("custom").getBoolean("keep"));
    }
    @Test public void uiWidgetArrayFallbackReadsConvertedWidgetsInOrder() throws Exception {
        JSONObject node=new JSONObject().put("id",1901).put("type","TextGenerate")
                .put("inputs",new JSONArray().put(new JSONObject().put("name","prompt").put("widget",new JSONObject()))
                        .put(new JSONObject().put("name","max_length").put("widget",new JSONObject()))
                        .put(new JSONObject().put("name","sampling_mode").put("widget",new JSONObject())))
                .put("widgets_values",new JSONArray().put("read image").put(320).put("off"));
        assertEquals("off",DynamicComboGuard.configuredFromUi(new JSONObject().put("nodes",new JSONArray().put(node)))
                .getJSONObject("1901").getJSONObject("inputs").getString("sampling_mode"));
    }
    @Test(expected=JSONException.class) public void unknownSelectionFailsBeforeQueueing() throws Exception {
        DynamicComboGuard.repair(prompt(new JSONObject().put("key","invalid").put("inputs",new JSONObject())),info(),null);
    }
}
