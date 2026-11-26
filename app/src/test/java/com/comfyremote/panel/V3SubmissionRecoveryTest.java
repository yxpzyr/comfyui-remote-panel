package com.comfyremote.panel;

import android.app.Application;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import okhttp3.mockwebserver.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class V3SubmissionRecoveryTest {
    private Application app(){return RuntimeEnvironment.getApplication();}
    private String read(String json,String client,boolean history)throws Exception{
        return SubmissionReceipt.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),client,history);
    }
    private void waitFor(java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(18);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(30);
        assertTrue("expected state before timeout",condition.getAsBoolean());
    }
    @Test public void receiptParserSkipsGraphsAndMatchesOnlyTheExactClient()throws Exception{
        String graph=new JSONObject().put("large","x".repeat(2_000_000)).toString();
        String tuple="[1,\"match\","+graph+",{\"client_id\":\"phone-one\"},[]]";
        assertEquals("match",read("{\"queue_running\":["+tuple+"],\"queue_pending\":[]}","phone-one",false));
        assertEquals("",read("{\"queue_running\":["+tuple+"],\"queue_pending\":[]}","other-phone",false));
        assertEquals("match",read("{\"match\":{\"prompt\":"+tuple+",\"outputs\":{}}}","phone-one",true));
        try{read("{\"queue_running\":[[1,\"too-large\",{\"x\":\""+"x".repeat(9_000_000)+"\"},{\"client_id\":\"phone-one\"}]]}","phone-one",false);fail();}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("流量上限"));}
    }
    @Test public void interruptedSubmissionRecoversReceiptWithoutPostingAgain()throws Exception{
        AtomicInteger posts=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){
                if(r.getMethod().equals("POST")){posts.incrementAndGet();return new MockResponse().setResponseCode(500);}
                if(r.getPath().equals("/queue"))return new MockResponse().setBody("{\"queue_running\":[[1,\"recovered\",{}, {\"client_id\":\"recover-client\"},[]]],\"queue_pending\":[]}");
                if(r.getPath().startsWith("/history/"))return new MockResponse().setBody("{\"recovered\":{\"status\":{\"completed\":true,\"status_str\":\"success\"},\"outputs\":{\"1\":{\"text\":[\"recovered-ok\"]}}}}");
                return new MockResponse().setResponseCode(404);
            }});server.start();
            JobRecord j=JobRecord.create("wf","recover","","",server.url("/").toString(),"{}");j.status=JobRecord.SUBMITTING;
            j.clientId="recover-client";j.submissionPhase="attempted";j.expectsTextOutput=true;JobStore.add(app(),j);
            GenerationManager.resumePending(app(),j.server);
            waitFor(()->JobRecord.COMPLETED.equals(JobStore.find(app(),j.localId).status));
            assertEquals("recovered",JobStore.find(app(),j.localId).promptId);assertEquals(0,posts.get());
        }
    }
    @Test public void lostPostResponseNeverRetriesAndLegacyUncertainTasksAlsoStayUnsubmitted()throws Exception{
        AtomicInteger posts=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){
                if(r.getMethod().equals("POST")){posts.incrementAndGet();return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);}
                if(r.getPath().contains("queue"))return new MockResponse().setBody("{\"queue_running\":[],\"queue_pending\":[]}");
                return new MockResponse().setBody("{}");
            }});server.start();
            JobRecord j=JobRecord.create("wf","drop","","",server.url("/").toString(),"{}");JobStore.add(app(),j);
            GenerationManager.enqueueSubmission(app(),j.localId);
            waitFor(()->JobRecord.SUBMISSION_UNKNOWN.equals(JobStore.find(app(),j.localId).status));
            for(int i=0;i<3;i++){GenerationManager.resumePending(app(),j.server);Thread.sleep(100);}
            assertEquals(1,posts.get());assertEquals("attempted",JobStore.find(app(),j.localId).submissionPhase);
            JobRecord legacy=JobRecord.create("wf","legacy","","",j.server,"{}");legacy.status=JobRecord.SUBMITTING;JobStore.add(app(),legacy);
            GenerationManager.resumePending(app(),j.server);
            waitFor(()->JobRecord.SUBMISSION_UNKNOWN.equals(JobStore.find(app(),legacy.localId).status));assertEquals(1,posts.get());
        }
    }
    @Test public void explicitValidationRejectionRemainsAFailureWithOriginalDetails()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"bad seed\"}}"));server.start();
            JobRecord j=JobRecord.create("wf","reject","","",server.url("/").toString(),"{}");JobStore.add(app(),j);
            GenerationManager.enqueueSubmission(app(),j.localId);
            waitFor(()->JobRecord.FAILED.equals(JobStore.find(app(),j.localId).status));
            assertTrue(JobStore.find(app(),j.localId).message.contains("bad seed"));assertEquals(1,server.getRequestCount());
        }
    }
}
