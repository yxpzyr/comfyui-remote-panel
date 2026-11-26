package com.comfyremote.panel;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import okhttp3.mockwebserver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class V3CollectionRepairTest {
    private android.app.Application app(){return RuntimeEnvironment.getApplication();}
    private JSONObject fixture(String name)throws Exception{
        try(InputStream in=getClass().getResourceAsStream("/v3-shared-generation-"+name+".json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>=0)out.write(b,0,n);
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    static JSONArray legacyVariants(){
        try{return new JSONArray().put(new JSONObject().put("id","group:0").put("group_index",0).put("title","main").put("outputs",new JSONArray().put("10")))
                .put(new JSONObject().put("id","group:1").put("group_index",1).put("title","checks").put("outputs",new JSONArray().put("11")));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private WorkflowProfile oldProfile()throws Exception{
        WorkflowProfile p=WorkflowProfile.create("旧版误拆工作流",new JSONObject().put("10",new JSONObject().put("class_type","SaveImage").put("inputs",new JSONObject())),fixture("ui"));
        p.initializeCollection(legacyVariants(),"group:0");p.collectionDetectionVersion=0;
        p.overrides.put(WorkflowProfile.overrideKey("1","seed"),777);p.inputUris.put("left","content://left");p.inputMaskPaths.put("left","/mask/left");
        p.variantStates.put("group:1",new JSONObject().put("prompt_json","{\"stale\":true}").put("overrides",new JSONObject().put(WorkflowProfile.overrideKey("1","seed"),888).put("other",9))
                .put("input_uris",new JSONObject().put("right","content://right")).put("input_mask_paths",new JSONObject().put("right","/mask/right")));
        return p;
    }
    private JSONObject independent()throws Exception{
        JSONObject ui=fixture("ui");JSONArray nodes=ui.getJSONArray("nodes");
        nodes.put(new JSONObject(nodes.getJSONObject(0).toString()).put("id",2).put("pos",new JSONArray().put(520).put(20)));
        ui.getJSONArray("links").getJSONArray(1).put(1,2);return ui;
    }
    private JSONObject withThird()throws Exception{
        JSONObject ui=fixture("ui");ui.getJSONArray("nodes").put(new JSONObject().put("id",20).put("type","SaveImage").put("mode",0)
                .put("pos",new JSONArray().put(1100).put(100)).put("inputs",new JSONArray()).put("outputs",new JSONArray()));
        ui.getJSONArray("groups").put(new JSONObject().put("title","独立功能").put("bounding",new JSONArray().put(1000).put(0).put(400).put(400)));return ui;
    }
    private MockWebServer server(JSONObject schema)throws Exception{
        MockWebServer server=new MockWebServer();server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){
            return r.getPath().equals("/object_info")?new MockResponse().setBody(schema.toString()):new MockResponse().setResponseCode(404);
        }});server.start();WorkflowUiConverter.clearObjectInfoCache();return server;
    }
    @Test public void sharedSamplingAndDiagnosticSavesAreOneWorkflow()throws Exception{assertEquals(0,WorkflowVariants.detect(fixture("ui")).length());}
    @Test public void independentGenerationBranchesRemainACollection()throws Exception{assertEquals(2,WorkflowVariants.detect(independent()).length());}
    @Test public void sharedLoadersAloneDoNotMergeIndependentBranches()throws Exception{
        JSONObject ui=independent();ui.getJSONArray("nodes").put(new JSONObject().put("id",3).put("type","LoadImage").put("mode",0)
                .put("pos",new JSONArray().put(-200).put(0)).put("inputs",new JSONArray()).put("outputs",new JSONArray()));
        ui.getJSONArray("nodes").getJSONObject(0).put("inputs",new JSONArray().put(new JSONObject().put("name","reference").put("type","IMAGE").put("link",3)));
        ui.getJSONArray("nodes").getJSONObject(3).put("inputs",new JSONArray().put(new JSONObject().put("name","reference").put("type","IMAGE").put("link",4)));
        ui.getJSONArray("links").put(new JSONArray().put(3).put(3).put(0).put(1).put(0).put("IMAGE"));
        ui.getJSONArray("links").put(new JSONArray().put(4).put(3).put(0).put(2).put(0).put("IMAGE"));
        assertEquals(2,WorkflowVariants.detect(ui).length());
    }
    @Test public void mergedComponentContainsAllItsOutputGroups()throws Exception{
        JSONArray variants=WorkflowVariants.detect(withThird());assertEquals(2,variants.length());
        assertEquals(2,variants.getJSONObject(0).getJSONArray("group_indices").length());assertEquals(2,variants.getJSONObject(0).getJSONArray("outputs").length());
    }
    @Test public void oldLibraryUpgradeBacksUpBytesAndPreservesInputsMasksAndActiveOverrides()throws Exception{
        WorkflowProfile p=oldProfile();WorkflowStore.upsert(app(),p);File file=new File(app().getFilesDir(),"workflows_v18/"+p.id+".json");byte[] before=Files.readAllBytes(file.toPath());
        assertTrue(WorkflowStore.find(WorkflowStore.loadSummaries(app()),p.id).collectionReviewNeeded);
        try(MockWebServer server=server(fixture("schema"))){
            WorkflowProfile fixed=WorkflowStore.open(app(),p.id,server.url("/").toString());assertFalse(fixed.isCollection());
            assertEquals(777,fixed.overrides.getInt(WorkflowProfile.overrideKey("1","seed")));assertEquals(9,fixed.overrides.getInt("other"));
            assertEquals("content://left",fixed.inputUris.getString("left"));assertEquals("content://right",fixed.inputUris.getString("right"));
            assertEquals("/mask/right",fixed.inputMaskPaths.getString("right"));assertTrue(fixed.variantStates.has("group:1"));
            assertTrue(fixed.promptObject().has("10"));assertTrue(fixed.promptObject().has("11"));
            assertEquals(new HashSet<>(Arrays.asList("10","11")),fixed.selectedOutputNodeIds());
            assertArrayEquals(before,Files.readAllBytes(new File(file.getParentFile(),"backup_before_3_0beta/"+file.getName()).toPath()));
            assertFalse(WorkflowStore.find(WorkflowStore.loadSummaries(app()),p.id).collectionReviewNeeded);
        }
    }
    @Test public void failedRecompileDoesNotRewriteTheOldLibraryRecord()throws Exception{
        WorkflowProfile p=oldProfile();WorkflowStore.upsert(app(),p);File file=new File(app().getFilesDir(),"workflows_v18/"+p.id+".json");byte[] before=Files.readAllBytes(file.toPath());
        try(MockWebServer server=server(new JSONObject())){
            try{WorkflowStore.open(app(),p.id,server.url("/").toString());fail("missing schemas must stop migration");}catch(Exception expected){}
        }
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertTrue(WorkflowStore.loadById(app(),p.id).isCollection());
    }
    @Test public void changedInactiveBranchesInvalidateCachedPromptsWithoutChangingTheActiveIndependentBranch()throws Exception{
        WorkflowProfile p=oldProfile();p.uiJson=withThird().toString();p.variants.put(new JSONObject().put("id","group:2").put("group_index",2).put("outputs",new JSONArray().put("20")));
        p.activeVariantId="group:2";p.promptJson="{\"20\":{\"class_type\":\"SaveImage\",\"inputs\":{}}}";String current=p.promptJson;
        WorkflowCollectionRepair.repair(p,"8188");assertTrue(p.isCollection());assertEquals("group:2",p.activeVariantId);assertEquals(current,p.promptJson);
        assertFalse(p.hasSavedVariantPrompt("group:0"));assertEquals(9,p.variantStates.getJSONObject("group:0").getJSONObject("overrides").getInt("other"));
    }
    @Test public void selectedSecondGroupMovesIntoTheMergedComponentWithAllItsOutputs()throws Exception{
        WorkflowProfile p=oldProfile();p.activateVariant("group:1",new JSONObject());p.overrides.put(WorkflowProfile.overrideKey("1","seed"),999);
        p.uiJson=withThird().toString();p.variants.put(new JSONObject().put("id","group:2").put("group_index",2).put("outputs",new JSONArray().put("20")));
        try(MockWebServer server=server(fixture("schema"))){WorkflowCollectionRepair.repair(p,server.url("/").toString());}
        assertTrue(p.isCollection());assertEquals("group:0",p.activeVariantId);
        assertEquals(999,p.overrides.getInt(WorkflowProfile.overrideKey("1","seed")));
        assertTrue(p.promptObject().has("10"));assertTrue(p.promptObject().has("11"));assertFalse(p.promptObject().has("20"));
    }
}
