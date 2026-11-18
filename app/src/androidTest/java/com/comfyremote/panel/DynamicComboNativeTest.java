package com.comfyremote.panel;

import android.app.Instrumentation;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.widget.Spinner;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DynamicComboNativeTest {
    private Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    @Test public void nativeSpinnerAndStoredFailedSubmissionSendOnlyOffKeys() throws Exception {
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();Context app=inst.getTargetContext();
        JSONObject on=new JSONObject().put("key","on").put("inputs",new JSONObject().put("required",new JSONObject()
                .put("temperature",new JSONArray().put("FLOAT").put(new JSONObject().put("default",0.7)))));
        JSONObject off=new JSONObject().put("key","off").put("inputs",new JSONObject().put("required",new JSONObject()));
        JSONObject schema=new JSONObject().put("TextGenerate",new JSONObject().put("input",new JSONObject().put("required",new JSONObject()
                .put("sampling_mode",new JSONArray().put("COMFY_DYNAMICCOMBO_V3").put(new JSONObject().put("options",new JSONArray().put(on).put(off)))))));
        JSONObject good=new JSONObject();JSONObject bad=new JSONObject();JSONArray nodes=new JSONArray();
        for(int id:new int[]{1901,1902}){
            good.put(""+id,new JSONObject().put("class_type","TextGenerate").put("inputs",new JSONObject().put("sampling_mode","off")));
            bad.put(""+id,new JSONObject().put("class_type","TextGenerate").put("inputs",new JSONObject().put("sampling_mode",on)));
            nodes.put(new JSONObject().put("id",id).put("type","TextGenerate").put("widgets_values_named",new JSONObject().put("sampling_mode","off")));
        }
        AtomicReference<JSONObject> captured=new AtomicReference<>();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                String path=request.getPath();
                if(path.equals("/object_info"))return json(schema.toString());
                if(path.equals("/prompt")){
                    JSONObject p=new JSONObject(request.getBody().readUtf8()).getJSONObject("prompt");captured.set(p);
                    for(String id:new String[]{"1901","1902"}) if(!"off".equals(p.getJSONObject(id).getJSONObject("inputs").opt("sampling_mode")))return new MockResponse().setResponseCode(400).setBody("invalid dynamic selector");
                    return json("{\"prompt_id\":\"dynamic-combo-native\"}");
                }
                if(path.startsWith("/history/"))return json("{\"dynamic-combo-native\":{\"status\":{\"status_str\":\"success\",\"completed\":true},\"outputs\":{\"1906\":{\"text\":[\"readguard-ok\"]}}}}");
                if(path.equals("/queue"))return json("{\"queue_running\":[],\"queue_pending\":[]}");
                return new MockResponse().setResponseCode(404);
            }catch(Exception e){return new MockResponse().setResponseCode(500).setBody(e.toString());}}
            private MockResponse json(String value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value);}});
            server.start();String base=server.url("/").toString();
            app.getSharedPreferences("comfy_remote",0).edit().putString("server",base).putString("generation_jobs_v18","[]").commit();
            WorkflowUiConverter.clearObjectInfoCache();
            WorkflowProfile profile=WorkflowProfile.create("Dynamic choice validation",good,new JSONObject().put("nodes",nodes));
            WorkflowStore.upsert(app,profile);WorkflowStore.setActiveId(app,profile.id);
            MainActivity activity=(MainActivity)inst.startActivitySync(new Intent(app,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                inst.runOnMainSync(()->{try{
                    set(activity,"parameterObjectInfo",schema);set(activity,"parameterMetadataProfileId",profile.id);set(activity,"advancedExpanded",true);
                    ((Set<String>)field(activity,"expandedParameterNodes")).addAll(Arrays.asList("1901","1902"));
                    Method rebuild=MainActivity.class.getDeclaredMethod("rebuildParameterControls");rebuild.setAccessible(true);rebuild.invoke(activity);
                    int count=0;
                    for(Object binding:(List<?>)field(activity,"fieldBindings")){
                        WorkflowUtils.FieldChoice choice=(WorkflowUtils.FieldChoice)field(binding,"choice");
                        if(!choice.inputName.equals("sampling_mode"))continue;
                        Spinner spinner=(Spinner)field(binding,"spinnerView");assertNotNull(spinner);
                        assertEquals("on",spinner.getAdapter().getItem(0));assertEquals("off",spinner.getAdapter().getItem(1));
                        assertEquals("off",spinner.getSelectedItem());assertEquals("off",choice.rawComboValueAt(1));count++;
                    }
                    assertEquals(2,count);
                }catch(Exception e){throw new RuntimeException(e);}});
            }finally{inst.runOnMainSync(activity::finish);}
            JobRecord job=JobRecord.create(profile.id,profile.name,"","",base,bad.toString());job.expectsTextOutput=true;
            JobStore.add(app,job);GenerationManager.enqueueSubmission(app,job.localId);
            long deadline=SystemClock.elapsedRealtime()+25000;JobRecord latest=null;
            while(SystemClock.elapsedRealtime()<deadline){latest=JobStore.find(app,job.localId);if(latest!=null&&latest.isTerminal())break;Thread.sleep(100);}
            assertNotNull(latest);assertEquals(JobRecord.COMPLETED,latest.status);assertNotNull(captured.get());
            JSONObject frozen=new JSONObject(JobStore.loadSubmission(app,job.localId).promptJson);
            for(String id:new String[]{"1901","1902"})assertEquals("off",frozen.getJSONObject(id).getJSONObject("inputs").get("sampling_mode"));
            assertTrue(bad.getJSONObject("1901").getJSONObject("inputs").get("sampling_mode") instanceof JSONObject);
            ContentValues evidence=new ContentValues();evidence.put(MediaStore.MediaColumns.DISPLAY_NAME,"dynamic-combo-native-request.json");
            evidence.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");evidence.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/ComfyRemoteValidation");
            Uri uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,evidence);assertNotNull(uri);
            try(Writer writer=new OutputStreamWriter(app.getContentResolver().openOutputStream(uri),StandardCharsets.UTF_8)){writer.write(captured.get().toString(2));}
        }
    }
}
