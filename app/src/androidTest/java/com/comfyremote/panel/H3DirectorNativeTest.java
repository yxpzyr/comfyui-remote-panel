package com.comfyremote.panel;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class H3DirectorNativeTest {
    private Instrumentation inst(){return InstrumentationRegistry.getInstrumentation();}
    private Context app(){return inst().getTargetContext();}
    private Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private JSONObject fixture(String suffix)throws Exception{
        try(InputStream input=inst().getContext().getAssets().open("h3-director-"+suffix+".json");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    private <T extends View> T find(View view,Class<T> type){
        if(type.isInstance(view))return type.cast(view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){T result=find(group.getChildAt(i),type);if(result!=null)return result;}}
        return null;
    }
    private H3DirectorActivity launch(WorkflowProfile profile,String server){
        return (H3DirectorActivity)inst().startActivitySync(new Intent(app(),H3DirectorActivity.class)
            .putExtra("workflow_id",profile.id).putExtra("server",server).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void capture(String name)throws Exception{
        File dir=new File("/sdcard/Pictures/ComfyRemoteValidation");dir.mkdirs();
        try(OutputStream output=new FileOutputStream(new File(dir,name))){inst().getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,output);}
    }
    private void awaitMode(H3DirectorActivity activity,String mode)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<deadline){
            String[] value={""};inst().runOnMainSync(()->{try{value[0]=((JSONObject)field(activity,"draft")).optString("mode");}catch(Exception error){throw new RuntimeException(error);}});
            if(mode.equals(value[0]))return;Thread.sleep(50);
        }
        fail("Mode did not change");
    }

    @Test public void editedShotCardsAndDurationSurviveReopening()throws Exception{
        WorkflowProfile profile=WorkflowProfile.create("H3 native draft",fixture("api"),null);WorkflowStore.upsert(app(),profile);
        H3DirectorActivity activity=launch(profile,"8188");
        inst().runOnMainSync(()->{
            EditText prompt=activity.getWindow().getDecorView().findViewWithTag("h3-prompt-0");prompt.setText("A saved continuous pond scene");
            EditText seconds=activity.getWindow().getDecorView().findViewWithTag("h3-duration-0");seconds.setText("7.5");
        });
        capture("h3-director-editor.png");
        inst().runOnMainSync(()->{
            ScrollView scroll=find(activity.getWindow().getDecorView(),ScrollView.class);
            scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
        });
        inst().waitForIdleSync();capture("h3-director-shots.png");
        inst().runOnMainSync(activity::finish);inst().waitForIdleSync();
        H3DirectorActivity reopened=launch(profile,"8188");
        try{
            inst().runOnMainSync(()->{
                EditText prompt=reopened.getWindow().getDecorView().findViewWithTag("h3-prompt-0");
                EditText seconds=reopened.getWindow().getDecorView().findViewWithTag("h3-duration-0");
                assertEquals("A saved continuous pond scene",prompt.getText().toString());assertEquals("7.5",seconds.getText().toString());
            });
        }finally{inst().runOnMainSync(reopened::finish);}
    }

    @Test public void nativePickerUploadsOnlyTheChosenReferenceAndGalleryReceivesTheCompleteVideo()throws Exception{
        JSONObject schemas=fixture("schema");List<JSONObject> received=new CopyOnWriteArrayList<>();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){
                MockResponse json(String body){return new MockResponse().setHeader("Content-Type","application/json").setBody(body);}
                @Override public MockResponse dispatch(RecordedRequest request){
                    try{
                        String path=request.getPath();
                        if(path.equals("/object_info"))return json(schemas.toString());
                        if(path.startsWith("/object_info/")){
                            String key=path.substring("/object_info/".length());
                            return json(schemas.has(key)?new JSONObject().put(key,schemas.getJSONObject(key)).toString():"{}");
                        }
                        if(path.equals("/upload/image"))return json("{\"name\":\"native_chosen.png\",\"subfolder\":\"\",\"type\":\"input\"}");
                        if(path.equals("/prompt")){
                            received.add(new JSONObject(request.getBody().readUtf8()).getJSONObject("prompt"));
                            return json("{\"prompt_id\":\"h3-native-complete\"}");
                        }
                        if(path.startsWith("/history")){
                            JSONObject ref=new JSONObject().put("filename","h3_complete.mp4").put("subfolder","comfyremote_h3").put("type","output").put("format","video/mp4");
                            JSONObject history=new JSONObject().put("h3-native-complete",new JSONObject().put("status",new JSONObject()
                                .put("status_str","success").put("completed",true)).put("outputs",new JSONObject().put(H3DirectorPlan.EXPORT_ID,
                                new JSONObject().put("videos",new JSONArray().put(ref)))));
                            return json(history.toString());
                        }
                        if(path.equals("/queue"))return json("{\"queue_running\":[],\"queue_pending\":[]}");
                        return new MockResponse().setResponseCode(404);
                    }catch(Exception error){return new MockResponse().setResponseCode(500).setBody(error.toString());}
                }
            });
            server.start();String address=server.url("/").toString();
            WorkflowProfile profile=WorkflowProfile.create("H3 native reference",fixture("api"),null);WorkflowStore.upsert(app(),profile);
            H3DirectorActivity activity=launch(profile,address);
            try{
                inst().runOnMainSync(()->find(activity.getWindow().getDecorView(),Spinner.class).setSelection(2));awaitMode(activity,"r2v");
                File chosen=new File(app().getCacheDir(),"h3_chosen.png");
                Bitmap image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);image.eraseColor(0xff4488bb);
                try(OutputStream output=new FileOutputStream(chosen)){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();
                Intent result=new Intent().setData(Uri.fromFile(chosen)).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                IntentFilter pickerFilter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
                pickerFilter.addCategory(Intent.CATEGORY_OPENABLE);
                pickerFilter.addDataType("image/*");
                Instrumentation.ActivityMonitor monitor=inst().addMonitor(pickerFilter,new Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,result),true);
                try{
                    inst().runOnMainSync(()->{
                        Button choose=activity.getWindow().getDecorView().findViewWithTag("h3-image-shared-images-0");assertNotNull(choose);choose.performClick();
                    });
                    inst().waitForIdleSync();
                    assertEquals("The test monitor must intercept the image document intent",1,monitor.getHits());
                }finally{inst().removeMonitor(monitor);}
                long deadline=SystemClock.elapsedRealtime()+20000;
                boolean[] selected={false};
                while(SystemClock.elapsedRealtime()<deadline){
                    inst().runOnMainSync(()->{try{selected[0]=!H3DirectorPlan.localUris((JSONObject)field(activity,"draft")).isEmpty();}catch(Exception error){throw new RuntimeException(error);}});
                    if(selected[0])break;Thread.sleep(80);
                }
                assertTrue("Native picker result must reach the public image slot",selected[0]);capture("h3-director-reference.png");
                inst().runOnMainSync(()->((Button)activity.getWindow().getDecorView().findViewWithTag("h3-generate")).performClick());
                while(received.isEmpty()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
                assertEquals(1,received.size());JSONObject prompt=received.get(0);
                JSONObject timeline=new JSONObject(prompt.getJSONObject("5").getJSONObject("inputs").getString("timeline_data"));
                assertEquals("native_chosen.png",timeline.getJSONObject("global").getJSONArray("refs").getJSONObject(0).getString("imageFile"));
                assertTrue(prompt.getJSONObject("1").getJSONObject("inputs").getString("unet_name").contains("ref2va"));
                assertFalse(prompt.toString().contains("file://"));assertEquals(2,timeline.getJSONArray("segments").length());
                JobRecord completed=null;
                while(SystemClock.elapsedRealtime()<deadline){
                    for(JobRecord job:JobStore.list(app()))if(profile.id.equals(job.workflowId)&&JobRecord.COMPLETED.equals(job.status))completed=job;
                    if(completed!=null)break;Thread.sleep(100);
                }
                assertNotNull("Existing queue must collect the standard video output",completed);
                assertFalse(completed.expectsTextOutput);assertEquals(1,completed.outputRefs().size());
                assertEquals("h3_complete.mp4",completed.outputRefs().get(0).filename);
                ContentValues evidence=new ContentValues();
                evidence.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"h3-native-request.json");
                evidence.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json");
                evidence.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/ComfyRemoteValidation");
                Uri log=app().getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,evidence);
                assertNotNull(log);
                try(Writer output=new OutputStreamWriter(app().getContentResolver().openOutputStream(log),StandardCharsets.UTF_8)){output.write(prompt.toString(2));}
            }finally{inst().runOnMainSync(activity::finish);}
        }
    }
}

