package com.comfyremote.panel;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import okhttp3.mockwebserver.*;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WorkflowSwitchNativeTest {
    private Instrumentation inst(){return InstrumentationRegistry.getInstrumentation();}
    private Context app(){return inst().getTargetContext();}
    private Object field(Object target,String name)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private Button button(View root,String name){
        if(root instanceof Button&&name.equals(((Button)root).getText().toString()))return (Button)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button b=button(((ViewGroup)root).getChildAt(i),name);if(b!=null)return b;}return null;
    }
    private WorkflowProfile old(String name)throws Exception{
        String ui;try(InputStream in=inst().getContext().getAssets().open("v3-shared-generation-ui.json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>=0)out.write(b,0,n);ui=out.toString("UTF-8");
        }
        JSONObject prompt=new JSONObject().put("1",new JSONObject().put("class_type","KSamplerSimple").put("inputs",new JSONObject().put("seed",123)))
                .put("10",new JSONObject().put("class_type","SaveImage").put("inputs",new JSONObject().put("images",new JSONArray().put("1").put(0)).put("filename_prefix","cached")));
        WorkflowProfile p=WorkflowProfile.create(name,prompt,new JSONObject(ui));JSONArray variants=new JSONArray();
        for(int i=0;i<2;i++)variants.put(new JSONObject().put("id","group:"+i).put("group_index",i).put("outputs",new JSONArray().put(""+(10+i))));
        p.initializeCollection(variants,"group:0");p.collectionDetectionVersion=0;p.overrides.put(WorkflowProfile.overrideKey("1","seed"),-1);
        p.setOutputChoices(WorkflowUtils.findOutputNodes(prompt),true,false);return p;
    }
    private void await(java.util.function.BooleanSupplier check)throws Exception{
        long until=SystemClock.elapsedRealtime()+5000;while(!check.getAsBoolean()&&SystemClock.elapsedRealtime()<until)Thread.sleep(30);assertTrue(check.getAsBoolean());
    }
    private void awaitSelected(MainActivity activity,String id)throws Exception{
        AtomicBoolean selected=new AtomicBoolean();await(()->{inst().runOnMainSync(()->{try{
            WorkflowProfile p=(WorkflowProfile)field(activity,"activeProfile");selected.set(p!=null&&id.equals(p.id));
        }catch(Exception e){throw new RuntimeException(e);}});return selected.get();});inst().waitForIdleSync();
    }
    private void tapShortcut(MainActivity activity,String label)throws Exception{
        AtomicReference<Button> target=new AtomicReference<>();
        inst().runOnMainSync(()->{try{
            Button b=button((View)field(activity,"workflowStrip"),label);assertNotNull(b);target.set(b);
            ((ScrollView)field(activity,"mainScroll")).scrollTo(0,0);
            ((HorizontalScrollView)b.getParent().getParent()).scrollTo(b.getLeft(),0);
        }catch(Exception e){throw new RuntimeException(e);}});inst().waitForIdleSync();
        Rect rect=new Rect();inst().runOnMainSync(()->assertTrue(target.get().getGlobalVisibleRect(rect)));
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY(),0);
        MotionEvent up=MotionEvent.obtain(now,now+70,MotionEvent.ACTION_UP,rect.centerX(),rect.centerY(),0);
        try{inst().sendPointerSync(down);inst().sendPointerSync(up);}finally{down.recycle();up.recycle();}inst().waitForIdleSync();
    }
    @Test public void realShortcutTouchStillSelectsSavedWorkflowAfterConnectionFailure()throws Exception{
        String previous=WorkflowStore.getActiveId(app()),previousServer=app().getSharedPreferences("comfy_remote",0).getString("server","8188");AtomicInteger requests=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){requests.incrementAndGet();return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);}});server.start();
            app().getSharedPreferences("comfy_remote",0).edit().putString("server",server.url("/").toString()).commit();
            WorkflowProfile a=WorkflowProfile.create("触摸起点A",new JSONObject(),null),b=old("离线目标B");WorkflowStore.upsert(app(),a);WorkflowStore.upsert(app(),b);WorkflowStore.setActiveId(app(),a.id);
            File file=new File(app().getFilesDir(),"workflows_v18/"+b.id+".json");byte[] before=Files.readAllBytes(file.toPath());
            MainActivity activity=(MainActivity)inst().startActivitySync(new Intent(app(),MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                inst().runOnMainSync(()->{try{java.lang.reflect.Method connect=MainActivity.class.getDeclaredMethod("testConnection");connect.setAccessible(true);connect.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
                AtomicBoolean failed=new AtomicBoolean();await(()->{inst().runOnMainSync(()->{try{failed.set(((TextView)field(activity,"connectionState")).getText().toString().contains("连接失败"));}catch(Exception e){throw new RuntimeException(e);}});return failed.get();});
                inst().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);inst().waitForIdleSync();assertFalse(activity.isFinishing());int requestsBefore=requests.get();
                tapShortcut(activity,b.name);awaitSelected(activity,b.id);
                assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(requestsBefore,requests.get());
                inst().runOnMainSync(()->{try{WorkflowProfile active=(WorkflowProfile)field(activity,"activeProfile");assertTrue(active.isCollection());assertEquals(-1,active.overrides.getInt(WorkflowProfile.overrideKey("1","seed")));}catch(Exception e){throw new RuntimeException(e);}});
                android.util.Log.i("SwitchRecovery","real_touch=true offline_switch=true saved_seed=-1 original_bytes_unchanged=true");
            }finally{inst().runOnMainSync(activity::finish);}
        }finally{WorkflowStore.setActiveId(app(),previous);app().getSharedPreferences("comfy_remote",0).edit().putString("server",previousServer).commit();}
    }
    @Test public void coldLaunchOfOldWorkflowDoesNotRequireServerSchemas()throws Exception{
        String previous=WorkflowStore.getActiveId(app()),previousServer=app().getSharedPreferences("comfy_remote",0).getString("server","8188");
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);}});server.start();
            app().getSharedPreferences("comfy_remote",0).edit().putString("server",server.url("/").toString()).commit();
            WorkflowProfile p=old("离线启动目标");WorkflowStore.upsert(app(),p);WorkflowStore.setActiveId(app(),p.id);
            MainActivity activity=(MainActivity)inst().startActivitySync(new Intent(app(),MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{awaitSelected(activity,p.id);assertEquals(0,server.getRequestCount());}finally{inst().runOnMainSync(activity::finish);}
        }finally{WorkflowStore.setActiveId(app(),previous);app().getSharedPreferences("comfy_remote",0).edit().putString("server",previousServer).commit();}
    }
}
