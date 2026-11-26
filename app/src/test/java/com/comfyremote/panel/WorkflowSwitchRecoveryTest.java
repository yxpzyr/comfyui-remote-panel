package com.comfyremote.panel;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import okhttp3.mockwebserver.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class WorkflowSwitchRecoveryTest {
    private Application app(){return RuntimeEnvironment.getApplication();}
    private Object field(Object target,String name)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private Button button(View view,String label){
        if(view instanceof Button&&label.equals(((Button)view).getText().toString()))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button result=button(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;
    }
    private WorkflowProfile old(String name)throws Exception{
        JSONObject prompt=new JSONObject().put("1",new JSONObject().put("class_type","KSamplerSimple").put("inputs",new JSONObject().put("seed",123)))
                .put("10",new JSONObject().put("class_type","SaveImage").put("inputs",new JSONObject().put("images",new JSONArray().put("1").put(0)).put("filename_prefix","saved")));
        JSONObject ui=new JSONObject(new String(getClass().getResourceAsStream("/v3-shared-generation-ui.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        WorkflowProfile p=WorkflowProfile.create(name,prompt,ui);p.initializeCollection(V3CollectionRepairTest.legacyVariants(),"group:0");p.collectionDetectionVersion=0;
        p.overrides.put(WorkflowProfile.overrideKey("1","seed"),-1);p.inputUris.put("remembered","content://kept");p.inputMaskPaths.put("remembered","/kept-mask");
        p.setOutputChoices(WorkflowUtils.findOutputNodes(prompt),true,false);return p;
    }
    private void awaitSelected(MainActivity activity,String id)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();WorkflowProfile p=(WorkflowProfile)field(activity,"activeProfile");if(p!=null&&id.equals(p.id))return;Thread.sleep(10);}
        assertEquals("shortcut must select the local profile",id,((WorkflowProfile)field(activity,"activeProfile")).id);
    }
    @Test public void shortcutSelectsOldCollectionOfflineWithoutRecompilingOrChangingSavedData()throws Exception{
        AtomicInteger requests=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){requests.incrementAndGet();return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);}});server.start();
            app().getSharedPreferences("comfy_remote",0).edit().putString("server",server.url("/").toString()).commit();
            WorkflowProfile a=WorkflowProfile.create("本地A",new JSONObject(),null),b=old("离线旧工作流B");WorkflowStore.upsert(app(),a);WorkflowStore.upsert(app(),b);WorkflowStore.setActiveId(app(),a.id);
            File file=new File(app().getFilesDir(),"workflows_v18/"+b.id+".json");byte[] before=Files.readAllBytes(file.toPath());
            ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
            try{MainActivity activity=c.get();Button shortcut=button((View)field(activity,"workflowStrip"),b.name);assertNotNull(shortcut);shortcut.performClick();awaitSelected(activity,b.id);
                assertTrue(((TextView)field(activity,"workflowState")).getText().toString().contains(b.name));
                WorkflowProfile active=(WorkflowProfile)field(activity,"activeProfile");assertTrue(active.isCollection());assertEquals(-1,active.overrides.getInt(WorkflowProfile.overrideKey("1","seed")));
                assertEquals("content://kept",active.inputUris.getString("remembered"));assertEquals("/kept-mask",active.inputMaskPaths.getString("remembered"));
                assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals("local selection sends no requests",0,requests.get());
            }finally{c.pause().stop().destroy();}
        }
    }
    @Test public void slowBackgroundNetworkWorkCannotBlockShortcutSelection()throws Exception{
        WorkflowProfile a=WorkflowProfile.create("网络阻塞A",new JSONObject(),null),b=old("网络阻塞B");WorkflowStore.upsert(app(),a);WorkflowStore.upsert(app(),b);WorkflowStore.setActiveId(app(),a.id);
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(6);
        try{MainActivity activity=c.get();ExecutorService network=(ExecutorService)field(activity,"pool");
            for(int i=0;i<6;i++)network.execute(()->{started.countDown();try{release.await(6,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});
            assertTrue(started.await(3,TimeUnit.SECONDS));button((View)field(activity,"workflowStrip"),b.name).performClick();awaitSelected(activity,b.id);
        }finally{release.countDown();c.pause().stop().destroy();}
    }
    @Test public void rapidShortcutTapsKeepTheLastSelection()throws Exception{
        WorkflowProfile a=WorkflowProfile.create("快速A",new JSONObject(),null),b=old("快速B"),last=old("快速C");WorkflowStore.upsert(app(),a);WorkflowStore.upsert(app(),b);WorkflowStore.upsert(app(),last);WorkflowStore.setActiveId(app(),a.id);
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        try{MainActivity activity=c.get();View strip=(View)field(activity,"workflowStrip");button(strip,b.name).performClick();button(strip,last.name).performClick();awaitSelected(activity,last.id);
            Thread.sleep(80);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(last.id,WorkflowStore.getActiveId(app()));assertEquals(last.id,((WorkflowProfile)field(activity,"activeProfile")).id);
        }finally{c.pause().stop().destroy();}
    }
    @Test public void connectionFailureIncludesTheDestinationAndActionableReason()throws Exception{
        MockWebServer server=new MockWebServer();server.start();String base=server.url("/").toString();server.close();
        try{new ComfyApiClient(base).testConnection();fail();}catch(Exception expected){assertTrue(expected.getMessage().contains(new java.net.URL(base).getHost()));assertTrue(expected.getMessage().contains("拒绝连接"));}
    }
}
