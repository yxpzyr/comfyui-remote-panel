package com.comfyremote.panel;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Exercises real Android seed controls, queue submission and completed-text copying without an external model/API. */
@RunWith(AndroidJUnit4.class)
public class SeedTextNativeIntegrationTest {
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private Context context() { return instrumentation().getTargetContext(); }
    private Object field(Object object, String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private void set(Object object, String name, Object value) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object,value);
    }
    private void invoke(Object object, String name) throws Exception {
        Method m=object.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(object);
    }
    private Button button(View view, String name) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(name)) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button b=button(((ViewGroup)view).getChildAt(i),name); if(b!=null)return b;
        }
        return null;
    }
    private JSONObject info() throws Exception {
        return new JSONObject("{\"TestSeedSampler\":{\"output_node\":false,\"output\":[\"STRING\"],\"input\":{\"required\":{"
                + "\"seed\":[\"INT\",{\"min\":0,\"max\":9007199254740991,\"default\":123}],\"text\":[\"STRING\",{\"multiline\":true,\"default\":\"\"}]}}},"
                + "\"TE_text_display\":{\"output_node\":true,\"output\":[\"STRING\"],\"input\":{\"required\":{\"text\":[\"STRING\",{\"default\":\"\"}]},"
                + "\"optional\":{\"input_text\":[\"STRING\",{\"forceInput\":true}]}}}}");
    }
    @Test public void nativeDiceAutoSeedTwoSubmissionsAndCopiedTextResults() throws Exception {
        JSONObject schema=info(); List<Long> submitted=new CopyOnWriteArrayList<>();
        String answer="两位朋友在公园讨论旅行。\n镜头缓慢推进，动作自然，保留人物特征。";
        AtomicInteger mediaRequests=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        String path=request.getPath();
                        if(path.equals("/object_info")) return json(schema.toString());
                        if(path.equals("/prompt")) {
                            JSONObject prompt=new JSONObject(request.getBody().readUtf8()).getJSONObject("prompt");
                            long seed=prompt.getJSONObject("1").getJSONObject("inputs").getLong("seed");
                            if(seed<0)return new MockResponse().setResponseCode(400).setBody("negative seed");
                            submitted.add(seed); return json(new JSONObject().put("prompt_id","text-"+submitted.size()).toString());
                        }
                        if(path.startsWith("/history/")) {
                            String id=path.substring("/history/".length());
                            return json(new JSONObject().put(id,new JSONObject()
                                    .put("status",new JSONObject().put("status_str","success").put("completed",true))
                                    .put("outputs",new JSONObject().put("2",new JSONObject().put("text",new JSONArray().put(answer))))).toString());
                        }
                        if(path.equals("/queue"))return json("{\"queue_running\":[],\"queue_pending\":[]}");
                        if(path.startsWith("/view"))mediaRequests.incrementAndGet();
                        return new MockResponse().setResponseCode(404);
                    } catch(Exception error) { return new MockResponse().setResponseCode(500).setBody(error.toString()); }
                }
                MockResponse json(String text) { return new MockResponse().setHeader("Content-Type","application/json").setBody(text); }
            });
            server.start();
            String base=server.url("/").toString();
            context().getSharedPreferences("comfy_remote",0).edit().putString("server",base).putString("generation_jobs_v18","[]").commit();
            WorkflowUiConverter.clearObjectInfoCache();
            JSONObject prompt=new JSONObject("{\"1\":{\"class_type\":\"TestSeedSampler\",\"inputs\":{\"seed\":123,\"text\":\"大致内容\"}},"
                    + "\"2\":{\"class_type\":\"TE_text_display\",\"inputs\":{\"text\":\"\",\"input_text\":[\"1\",0]}}}");
            WorkflowProfile profile=WorkflowProfile.create("种子与提示词测试",prompt,null);
            WorkflowStore.upsert(context(),profile); WorkflowStore.setActiveId(context(),profile.id);
            Intent intent=new Intent(context(),MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity=(MainActivity)instrumentation().startActivitySync(intent);
            try {
                AtomicReference<EditText> seedEditor=new AtomicReference<>();
                instrumentation().runOnMainSync(() -> {
                    try {
                        set(activity,"parameterObjectInfo",schema); set(activity,"parameterMetadataProfileId",profile.id);
                        set(activity,"advancedExpanded",true);
                        ((Set<String>)field(activity,"expandedParameterNodes")).add("1");
                        invoke(activity,"rebuildParameterControls"); invoke(activity,"applyAdvancedVisibility");
                        Button dice=button(activity.getWindow().getDecorView(),"🎲 掷骰子");
                        assertNotNull(dice); assertTrue(dice.isShown());
                        dice.performClick();
                        for(Object binding:(List<?>)field(activity,"fieldBindings")) {
                            WorkflowUtils.FieldChoice choice=(WorkflowUtils.FieldChoice)field(binding,"choice");
                            if(choice.nodeId.equals("1")&&choice.inputName.equals("seed")) seedEditor.set((EditText)field(binding,"editView"));
                        }
                        assertNotNull(seedEditor.get());
                        long fixed=Long.parseLong(seedEditor.get().getText().toString()); assertTrue(fixed>=0);
                        button(activity.getWindow().getDecorView(),"每次随机（-1）").performClick();
                        assertEquals("-1",seedEditor.get().getText().toString());
                        dice.requestRectangleOnScreen(new Rect(0,0,dice.getWidth(),dice.getHeight()),true);
                    } catch(Exception e) { throw new RuntimeException(e); }
                });
                instrumentation().waitForIdleSync();
                instrumentation().runOnMainSync(() -> {
                    Button dice=button(activity.getWindow().getDecorView(),"🎲 掷骰子");
                    dice.requestRectangleOnScreen(new Rect(0,0,dice.getWidth(),dice.getHeight()),true);
                });
                screenshot("seed-controls.png");
                for(int count=1;count<=2;count++) {
                    final int index=count;
                    instrumentation().runOnMainSync(() -> {
                        try { Button submit=(Button)field(activity,"submitButton"); assertTrue(submit.isEnabled()); submit.performClick(); }
                        catch(Exception e) { throw new RuntimeException(e); }
                    });
                    long deadline=SystemClock.elapsedRealtime()+20000; JobRecord completed=null;
                    while(SystemClock.elapsedRealtime()<deadline) {
                        completed=JobStore.latestCompleted(context());
                        if(completed!=null && completed.promptId.equals("text-"+index))break;
                        Thread.sleep(100);
                    }
                    assertNotNull(completed); assertEquals("text-"+index,completed.promptId);
                    assertEquals(JobRecord.COMPLETED,completed.status); assertTrue(completed.hasTextOutput());
                    JSONObject stored=new JSONObject(JobStore.loadSubmission(context(),completed.localId).promptJson);
                    assertEquals(submitted.get(index-1).longValue(),stored.getJSONObject("1").getJSONObject("inputs").getLong("seed"));
                    assertEquals(submitted.get(index-1).longValue(),new JSONObject(completed.resolvedSeedsJson).getLong("1.seed"));
                    assertEquals(answer,TextOutputs.combined(JobStore.loadTextResults(context(),completed.localId).textOutputsJson));
                }
                assertEquals(2,submitted.size()); assertNotEquals(submitted.get(0),submitted.get(1));
                AtomicReference<Button> copy=new AtomicReference<>();
                long deadline=SystemClock.elapsedRealtime()+5000;
                while(copy.get()==null && SystemClock.elapsedRealtime()<deadline) {
                    instrumentation().runOnMainSync(() -> copy.set(button(activity.getWindow().getDecorView(),"复制提示词")));
                    Thread.sleep(50);
                }
                assertNotNull(copy.get());
                instrumentation().runOnMainSync(() -> {
                    assertEquals("-1",seedEditor.get().getText().toString());
                    copy.get().performClick();
                    ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    assertEquals(answer,clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                    copy.get().requestRectangleOnScreen(new Rect(0,0,copy.get().getWidth(),copy.get().getHeight()),true);
                });
                screenshot("text-result.png");
                assertEquals(0,mediaRequests.get());
                android.util.Log.i("SeedTextValidation","dice=true auto_seed_two_jobs=true snapshots_match=true text_copy=true media_requests=0");
            } finally { instrumentation().runOnMainSync(activity::finish); instrumentation().waitForIdleSync(); }
        }
    }
    private void screenshot(String name) throws Exception {
        Thread.sleep(150); Bitmap bitmap=instrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        ContentValues values=new ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ComfyRemoteValidation");
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING,1);
        android.net.Uri uri=context().getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        assertNotNull(uri);
        try(OutputStream out=context().getContentResolver().openOutputStream(uri)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);context().getContentResolver().update(uri,values,null,null);
        bitmap.recycle();
    }
}
