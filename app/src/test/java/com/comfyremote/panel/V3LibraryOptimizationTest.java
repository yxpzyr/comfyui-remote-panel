package com.comfyremote.panel;

import android.app.Application;
import android.view.View;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class V3LibraryOptimizationTest {
    private Application app(){return RuntimeEnvironment.getApplication();}
    private static String read(java.nio.file.Path path)throws Exception{return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);}
    private static void write(java.nio.file.Path path,String value)throws Exception{Files.write(path,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private Object field(Object target,String name)throws Exception{
        java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{
        java.lang.reflect.Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
    private void drain(Object activity)throws Exception{
        ((ExecutorService)field(activity,"pool")).submit(()->{}).get(10,TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    @Test public void warmIndexDoesNotParseAnyGraphsAndOnlyChangedFilesAreRead()throws Exception{
        File directory=new File(app().getFilesDir(),"metadata-stress");assertTrue(directory.mkdirs());
        String padding="x".repeat(65536);
        for(int i=0;i<600;i++){
            WorkflowProfile p=WorkflowProfile.create("大型工作流 "+i,new JSONObject(),null);p.id="item-"+i;p.uiJson=padding;
            write(new File(directory,p.id+".json").toPath(),p.toJson().toString());
        }
        AtomicInteger reads=new AtomicInteger();
        java.util.function.Function<File,WorkflowProfile> reader=file->{try{
            reads.incrementAndGet();WorkflowProfile p=WorkflowProfile.fromJson(new JSONObject(read(file.toPath())));
            p.promptJson="{}";p.uiJson=null;p.metadataOnly=true;return p;
        }catch(Exception e){throw new IllegalStateException(e);}};
        long cold=System.nanoTime();assertEquals(600,WorkflowMetadataIndex.load(directory,reader).size());cold=System.nanoTime()-cold;
        assertEquals(600,reads.get());reads.set(0);
        long warm=System.nanoTime();List<WorkflowProfile> rows=WorkflowMetadataIndex.load(directory,reader);warm=System.nanoTime()-warm;
        assertEquals(600,rows.size());assertEquals(0,reads.get());
        for(WorkflowProfile p:rows){assertTrue(p.metadataOnly);assertNull(p.uiJson);assertEquals("{}",p.promptJson);}
        File changed=new File(directory,"item-17.json");JSONObject json=new JSONObject(read(changed.toPath()));
        json.put("name","修改后的工作流名称");write(changed.toPath(),json.toString());
        assertEquals(600,WorkflowMetadataIndex.load(directory,reader).size());assertEquals(1,reads.get());
        assertTrue(new File(directory,"item-12.json").delete());
        assertEquals(599,WorkflowMetadataIndex.load(directory,reader).size());assertEquals(1,reads.get());
        // A damaged cache can be thrown away; every source workflow is still readable.
        File index=new File(directory,"index_v3/library.json");write(index.toPath(),"broken");reads.set(0);
        assertFalse(WorkflowMetadataIndex.ready(directory));assertEquals(599,WorkflowMetadataIndex.load(directory,reader).size());
        assertEquals(599,reads.get());
        System.out.println("V3_INDEX files=600 graph_bytes=39321600 cold_ms="+cold/1000000+" warm_ms="+warm/1000000+" warm_graph_reads=0");
    }
    @Test public void failedWriteIsReportedBeforeBulkSaveCanDeleteOtherWorkflows()throws Exception{
        WorkflowProfile original=WorkflowProfile.create("原工作流",new JSONObject().put("keep",true),null);
        WorkflowProfile other=WorkflowProfile.create("保留工作流",new JSONObject(),null);
        WorkflowStore.upsert(app(),original);WorkflowStore.upsert(app(),other);
        File dir=new File(app().getFilesDir(),"workflows_v18");File source=new File(dir,original.id+".json");
        byte[] before=Files.readAllBytes(source.toPath());File blocker=new File(dir,original.id+".json.tmp");assertTrue(blocker.mkdir());
        write(new File(blocker,"occupied").toPath(),"block");
        original.name="保存不应成功";
        try{WorkflowStore.save(app(),Collections.singletonList(original));fail("must report write failure");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("保存失败"));}
        assertArrayEquals(before,Files.readAllBytes(source.toPath()));assertNotNull(WorkflowStore.loadById(app(),other.id));
    }
    @Test public void largeFolderAndSearchKeepOnlyVisibleCardsAndPreserveAllResults()throws Exception{
        List<WorkflowProfile> profiles=new ArrayList<>();
        for(int i=0;i<500;i++)profiles.add(WorkflowProfile.create("工作流 "+i,new JSONObject(),null));
        WorkflowStore.save(app(),profiles);
        ActivityController<WorkflowLibraryActivity> c=Robolectric.buildActivity(WorkflowLibraryActivity.class).setup();
        try{
            WorkflowLibraryActivity a=c.get();drain(a);ListView list=(ListView)field(a,"list");
            list.getAdapter().getView(1,null,list).performClick();
            list.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
            list.layout(0,0,500,800);
            assertEquals(500,list.getAdapter().getCount());assertTrue("only viewport cards are created",list.getChildCount()<35);
            ListView search=new ListView(a);TextView meta=new TextView(a);
            call(a,"renderSearchResults",new Class[]{ListView.class,TextView.class,String.class,android.app.AlertDialog.class},search,meta,"工作流",new android.app.AlertDialog.Builder(a).create());
            assertEquals(500,search.getAdapter().getCount());assertTrue(meta.getText().toString().contains("500"));
            View row=list.getAdapter().getView(0,null,list);assertSame(row,list.getAdapter().getView(499,row,list));
            System.out.println("V3_LIBRARY workflows=500 visible_cards="+list.getChildCount()+" searchable_results="+search.getAdapter().getCount());
        }finally{c.pause().stop().destroy();}
    }
    @Test public void collapsedParameterNodesCreateNoEditorsAndRetainSeedAndTemporaryValues()throws Exception{
        JSONObject prompt=new JSONObject();
        for(int i=1;i<=160;i++)prompt.put(""+i,new JSONObject().put("class_type","TestSampler").put("_meta",new JSONObject().put("title","测试节点 "+i))
                .put("inputs",new JSONObject().put("seed",123).put("steps",20)));
        WorkflowProfile p=WorkflowProfile.create("160 节点",prompt,null);p.overrides.put(WorkflowProfile.overrideKey("1","seed"),-1L);
        WorkflowStore.upsert(app(),p);WorkflowStore.setActiveId(app(),p.id);
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            MainActivity a=c.get();List<?> bindings=(List<?>)field(a,"fieldBindings");assertEquals(320,bindings.size());
            Map<String,Object> values=(Map<String,Object>)field(a,"transientParameterValues");values.put(WorkflowProfile.overrideKey("1","steps"),"35");
            for(Object b:bindings){assertNull(field(b,"editView"));WorkflowUtils.FieldChoice f=(WorkflowUtils.FieldChoice)field(b,"choice");
                Object value=call(a,"readFieldValue",new Class[]{b.getClass()},b);
                if(f.nodeId.equals("1")&&f.inputName.equals("seed"))assertEquals(-1L,value);
                if(f.nodeId.equals("1")&&f.inputName.equals("steps"))assertEquals(35L,value);
            }
            LinearLayout parameters=(LinearLayout)field(a,"parameterList");LinearLayout node=(LinearLayout)parameters.getChildAt(0);
            node.getChildAt(0).performClick();LinearLayout content=(LinearLayout)node.getChildAt(1);assertEquals(2,content.getChildCount());
            node.getChildAt(0).performClick();node.getChildAt(0).performClick();assertEquals(2,content.getChildCount());
            assertEquals(320,((List<?>)field(a,"fieldBindings")).size());
            long materialized=0;for(Object b:(List<?>)field(a,"fieldBindings"))if(field(b,"editView")!=null)materialized++;
            assertEquals(2,materialized);System.out.println("V3_PARAMETERS fields=320 initial_editors=0 expanded_editors="+materialized);
        }finally{c.pause().stop().destroy();}
    }
}
