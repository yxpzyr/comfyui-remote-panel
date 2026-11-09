package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Reproduces HTTP 400 from CreateVideo.bit_depth: '8' not in ['auto', 8, 10]. */
public class ComboTypeRegressionTest {
    private static JSONObject schema() throws Exception {
        JSONObject options = new JSONObject().put("options", new JSONArray().put("auto").put(8).put(10));
        JSONObject videoInputs = new JSONObject()
                .put("bit_depth", new JSONArray().put("COMBO").put(options))
                .put("mode", new JSONArray().put(new JSONArray().put("auto").put(8).put(10)))
                .put("quality", new JSONArray().put(new JSONArray().put("8").put("10")))
                .put("prompt", new JSONArray().put("STRING").put(new JSONObject()));
        return new JSONObject().put("CreateVideo", new JSONObject()
                .put("input", new JSONObject().put("required", videoInputs))
                .put("input_order", new JSONObject().put("required", new JSONArray()
                        .put("bit_depth").put("mode").put("quality").put("prompt"))));
    }

    private static JSONObject prompt() throws Exception {
        return new JSONObject().put("209", new JSONObject()
                .put("class_type", "CreateVideo")
                .put("inputs", new JSONObject()
                        .put("bit_depth", "8")
                        .put("mode", "10")
                        .put("quality", "8")
                        .put("prompt", "8")));
    }

    @Test public void fixesScreenshotReproductionAndPreservesTextOptions() throws Exception {
        JSONObject workflow = prompt();
        assertTrue(PromptValueNormalizer.needsComboNormalization(workflow));
        assertEquals(2, PromptValueNormalizer.normalizeComboValues(workflow, schema()));
        JSONObject inputs = workflow.getJSONObject("209").getJSONObject("inputs");
        assertTrue(inputs.opt("bit_depth") instanceof Number);
        assertEquals(8, inputs.getInt("bit_depth"));
        assertTrue(inputs.opt("mode") instanceof Number);
        assertEquals(10, inputs.getInt("mode"));
        assertEquals("8", inputs.getString("quality"));
        assertEquals("8", inputs.getString("prompt"));
        assertEquals(0, PromptValueNormalizer.normalizeComboValues(workflow, schema()));
    }

    @Test public void handlesAutoChoiceAndPreservesLinkedInputs() throws Exception {
        JSONObject workflow = prompt();
        JSONObject inputs = workflow.getJSONObject("209").getJSONObject("inputs");
        inputs.put("bit_depth", "auto");
        inputs.put("mode", new JSONArray().put("33").put(0));
        assertEquals(0, PromptValueNormalizer.normalizeComboValues(workflow, schema()));
        assertEquals("auto", inputs.getString("bit_depth"));
        assertTrue(inputs.opt("mode") instanceof JSONArray);
    }

    @Test public void typedSpinnerOptionsStayTypedEvenWhenDisplayedAsStrings() throws Exception {
        JSONObject workflow = prompt();
        List<WorkflowUtils.FieldChoice> fields = WorkflowUtils.findEditableFields(workflow, schema());
        WorkflowUtils.FieldChoice depth = null;
        WorkflowUtils.FieldChoice quality = null;
        for (WorkflowUtils.FieldChoice f : fields) {
            if ("bit_depth".equals(f.inputName)) depth = f;
            if ("quality".equals(f.inputName)) quality = f;
        }
        assertNotNull(depth);
        assertNotNull(quality);
        assertEquals(WorkflowUtils.FieldChoice.COMBO, depth.kind);
        assertEquals("8", depth.options.get(1));
        assertTrue(depth.rawComboValueAt(1) instanceof Number);
        assertEquals(8, ((Number) depth.rawComboValueAt(1)).intValue());
        assertEquals("8", quality.rawComboValueAt(0));
    }

    @Test public void avoidsRewritingOrdinaryNumericJsonAndFilenameStrings() throws Exception {
        JSONObject graph = new JSONObject().put("1", new JSONObject().put("class_type", "LoadImage")
                .put("inputs", new JSONObject().put("image", "001.png").put("seed", 8)));
        assertFalse(PromptValueNormalizer.needsComboNormalization(graph));
        assertEquals(0, PromptValueNormalizer.normalizeComboValues(graph, schema()));
    }

    @Test public void uiWorkflowConversionAlsoPreservesTypedBitDepth() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        server.enqueue(new okhttp3.mockwebserver.MockResponse()
                .setHeader("Content-Type", "application/json").setBody(schema().toString()));
        server.start();
        try {
            WorkflowUiConverter.clearObjectInfoCache();
            JSONObject ui = new JSONObject()
                    .put("nodes", new JSONArray().put(new JSONObject()
                            .put("id", 209).put("type", "CreateVideo")
                            .put("pos", new JSONArray().put(0).put(0))
                            .put("size", new JSONArray().put(200).put(100))
                            .put("widgets_values", new JSONArray()
                                    .put("8").put("10").put("8").put("8"))))
                    .put("links", new JSONArray());
            JSONObject result = WorkflowUiConverter.toApiPrompt(ui, server.url("/").toString());
            Object bitDepth = result.getJSONObject("209").getJSONObject("inputs").get("bit_depth");
            assertTrue("UI conversion must restore numeric 8", bitDepth instanceof Number);
            assertEquals(8, ((Number) bitDepth).intValue());
            assertEquals("/object_info", server.takeRequest().getPath());
        } finally {
            server.shutdown();
            WorkflowUiConverter.clearObjectInfoCache();
        }
    }

    @Test public void legacyComboAllowsMatchingNumericOrStringCases() throws Exception {
        JSONArray allowed = new JSONArray().put("auto").put(8).put(10);
        assertEquals(8, ((Number) PromptValueNormalizer.canonicalValue("8", allowed)).intValue());
        assertEquals("auto", PromptValueNormalizer.canonicalValue("auto", allowed));
        assertEquals("8", PromptValueNormalizer.canonicalValue("8", new JSONArray().put("8")));
    }
}
