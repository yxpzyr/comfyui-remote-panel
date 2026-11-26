package com.comfyremote.panel;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import okhttp3.mockwebserver.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class V3OptimizationNativeTest {
    private Instrumentation inst(){return InstrumentationRegistry.getInstrumentation();}
    private Context app(){return inst().getTargetContext();}
    private Object field(Object target,String name)throws Exception{
        java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private void ready(WorkflowLibraryActivity activity)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+15000;AtomicBoolean loaded=new AtomicBoolean();
        while(!loaded.get()&&SystemClock.elapsedRealtime()<deadline){
            inst().runOnMainSync(()->{try{loaded.set((boolean)field(activity,"loaded"));}catch(Exception e){throw new RuntimeException(e);}});
            if(!loaded.get())Thread.sleep(50);
        }assertTrue("library loaded",loaded.get());inst().waitForIdleSync();
    }
    private void evidence(String name,Bitmap image)throws Exception{
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"image/png");values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/ComfyRemoteValidation");
        Uri uri=app().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);assertNotNull(uri);
        try(OutputStream out=app().getContentResolver().openOutputStream(uri)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
    }
    @Test public void largeLibraryKeepsVisibleCardsAndRestoresFolderAfterRecreation()throws Exception{
        String previous=WorkflowStore.getActiveId(app());WorkflowFolder folder=WorkflowFolderStore.create(app(),"3.0beta 压力测试");
        List<WorkflowProfile> profiles=WorkflowStore.loadSummaries(app());char[] chars=new char[65536];Arrays.fill(chars,'x');
        JSONObject ui=new JSONObject().put("nodes",new JSONArray()).put("metadata",new String(chars));
        for(int i=0;i<400;i++){WorkflowProfile p=WorkflowProfile.create("测试工作流 "+String.format(Locale.ROOT,"%03d",i),new JSONObject(),ui);p.folderId=folder.id;profiles.add(p);}
        WorkflowStore.save(app(),profiles);
        WorkflowLibraryActivity first=(WorkflowLibraryActivity)inst().startActivitySync(new Intent(app(),WorkflowLibraryActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<WorkflowLibraryActivity> current=new AtomicReference<>(first);
        try{
            ready(first);AtomicLong longest=new AtomicLong();
            for(int pass=0;pass<12;pass++){
                inst().runOnMainSync(()->{try{
                    WorkflowLibraryActivity a=current.get();ListView list=(ListView)field(a,"list");boolean found=false;
                    long start=SystemClock.elapsedRealtime();
                    for(int i=0;i<list.getAdapter().getCount();i++){
                        android.view.View row=list.getAdapter().getView(i,null,list);
                        if(((TextView)field(row.getTag(),"name")).getText().toString().contains(folder.name)){row.performClick();found=true;break;}
                    }
                    assertTrue(found);assertEquals(400,list.getAdapter().getCount());longest.set(Math.max(longest.get(),SystemClock.elapsedRealtime()-start));
                }catch(Exception e){throw new RuntimeException(e);}});
                inst().waitForIdleSync();
                inst().runOnMainSync(()->{try{ListView list=(ListView)field(current.get(),"list");assertTrue(list.getChildCount()<30);list.setSelection(250);}catch(Exception e){throw new RuntimeException(e);}});
                inst().waitForIdleSync();
                if(pass<11){inst().runOnMainSync(first::onBackPressed);inst().waitForIdleSync();}
            }
            Bitmap screenshot=inst().getUiAutomation().takeScreenshot();if(screenshot!=null){evidence("v3-large-library.png",screenshot);screenshot.recycle();}
            Instrumentation.ActivityMonitor monitor=inst().addMonitor(WorkflowLibraryActivity.class.getName(),null,false);
            try{inst().runOnMainSync(first::recreate);Activity recreated=inst().waitForMonitorWithTimeout(monitor,12000);
                assertNotNull(recreated);assertNotSame(first,recreated);current.set((WorkflowLibraryActivity)recreated);ready(current.get());
                inst().runOnMainSync(()->{try{
                    assertEquals(folder.id,field(current.get(),"currentFolderId"));
                    ListView list=(ListView)field(current.get(),"list");assertEquals(400,list.getAdapter().getCount());assertTrue(list.getFirstVisiblePosition()>200);
                }catch(Exception e){throw new RuntimeException(e);}});
            }finally{inst().removeMonitor(monitor);}
            assertTrue("navigation must stay responsive",longest.get()<1500);
            android.util.Log.i("V3Validation","library=400 navigation_cycles=12 longest_navigation_ms="+longest.get()+" recreation_restored=true");
        }finally{inst().runOnMainSync(()->current.get().finish());WorkflowStore.setActiveId(app(),previous);}
    }
    @Test public void nativePostDisconnectSendsOnceAndKeepsTheFrozenSubmission()throws Exception{
        AtomicInteger posts=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){
                if("POST".equals(request.getMethod())){posts.incrementAndGet();return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);}
                return new MockResponse().setBody(request.getPath().contains("queue")?"{\"queue_running\":[],\"queue_pending\":[]}":"{}");
            }});server.start();
            JobRecord j=JobRecord.create("wf","3.0beta 断网验证","","",server.url("/").toString(),"{}");JobStore.add(app(),j);
            GenerationManager.enqueueSubmission(app(),j.localId);long deadline=SystemClock.elapsedRealtime()+20000;
            while(SystemClock.elapsedRealtime()<deadline&&!JobRecord.SUBMISSION_UNKNOWN.equals(JobStore.find(app(),j.localId).status))Thread.sleep(50);
            assertEquals(JobRecord.SUBMISSION_UNKNOWN,JobStore.find(app(),j.localId).status);
            for(int i=0;i<3;i++){GenerationManager.resumePending(app(),j.server);Thread.sleep(120);}
            assertEquals(1,posts.get());assertEquals("{}",JobStore.loadSubmission(app(),j.localId).promptJson);
            assertEquals("attempted",JobStore.find(app(),j.localId).submissionPhase);
            android.util.Log.i("V3Validation","lost_response_posts="+posts.get()+" frozen_payload=true");
            JobStore.updateStatus(app(),j.localId,JobRecord.CANCELLED,"模拟器验证结束",0);
        }
    }
}
