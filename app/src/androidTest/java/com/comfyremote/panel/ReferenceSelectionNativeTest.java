package com.comfyremote.panel;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import okhttp3.mockwebserver.*;

/** Real Activity, image upload and captured requests; no model or personal media. */
@RunWith(AndroidJUnit4.class)
public class ReferenceSelectionNativeTest {
    private Instrumentation inst() { return InstrumentationRegistry.getInstrumentation(); }
    private Context app() { return inst().getTargetContext(); }
    private Object field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj);
    }
    private JSONObject fixture(String name) throws Exception {
        try (InputStream in = inst().getContext().getAssets().open("reference-selection-" + name + ".json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    private Switch branch(MainActivity activity, String id) throws Exception {
        for (Object binding : (List<?>) field(activity, "imageBindings")) {
            WorkflowUtils.NodeChoice choice = (WorkflowUtils.NodeChoice) field(binding, "choice");
            if (!choice.id.equals(id)) continue;
            ViewGroup box = (ViewGroup) ((ImageView) field(binding, "preview")).getParent();
            for (int i = 0; i < box.getChildCount(); i++) {
                View child = box.getChildAt(i);
                if (child instanceof Switch && ((Switch) child).getText().toString().startsWith("启用此参考图分支"))
                    return (Switch) child;
            }
        }
        throw new AssertionError("Missing reference switch " + id);
    }
    private void assertOnlyA(JSONObject graph) throws Exception {
        assertTrue(graph.has("308")); assertFalse(graph.has("295"));
        assertEquals("mobile_A.png", graph.getJSONObject("308").getJSONObject("inputs").getString("image"));
        JSONObject inputs = graph.getJSONObject("316").getJSONObject("inputs");
        assertFalse(inputs.has("ref_images.ref_image_1"));
        assertEquals("308", inputs.getJSONArray("ref_images.ref_image_0").getString(0));
        assertEquals("Edited park scene", inputs.getString("prompt"));
        assertFalse(graph.toString().contains("original_C.png"));
    }
    @Test public void oldGraphAndToggledOffReferenceSubmitOnlyTheSelectedPicture() throws Exception {
        JSONObject schema = fixture("schema"), ui = fixture("ui");
        List<JSONObject> received = new CopyOnWriteArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        String path = request.getPath();
                        if (path.equals("/object_info")) return json(schema.toString());
                        if (path.equals("/upload/image")) return json("{\"name\":\"mobile_A.png\",\"subfolder\":\"\",\"type\":\"input\"}");
                        if (path.equals("/prompt")) {
                            received.add(new JSONObject(request.getBody().readUtf8()).getJSONObject("prompt"));
                            return json("{\"prompt_id\":\"refs-" + received.size() + "\"}");
                        }
                        if (path.startsWith("/history/")) {
                            String id = path.substring(9);
                            return json(new JSONObject().put(id, new JSONObject().put("status", new JSONObject()
                                    .put("status_str", "success").put("completed", true)).put("outputs", new JSONObject())).toString());
                        }
                        if (path.equals("/queue")) return json("{\"queue_running\":[],\"queue_pending\":[]}");
                        return new MockResponse().setResponseCode(404);
                    } catch (Exception e) { return new MockResponse().setResponseCode(500).setBody(e.toString()); }
                }
                MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
            });
            server.start(); String base = server.url("/").toString();
            WorkflowUiConverter.clearObjectInfoCache();
            JSONArray variants = WorkflowVariants.detect(ui);
            JSONObject stale = WorkflowVariants.compile(ui, variants, "group:0", base, new HashSet<>(Arrays.asList("308", "295")));
            stale.getJSONObject("316").getJSONObject("inputs").put("prompt", "Edited park scene");
            WorkflowProfile profile = WorkflowProfile.create("Reference switch regression", stale, ui);
            profile.initializeCollection(variants, "group:0");
            profile.setAuxImageEnabled("308", true); profile.setAuxImageEnabled("295", false);
            File image = new File(app().getCacheDir(), "reference_A.png");
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff3388cc);
            try (OutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            bitmap.recycle();
            profile.setSavedInputUri("308|image", Uri.fromFile(image).toString());
            profile.setSavedReplaceEnabled("308|image", true); profile.setSavedReplaceEnabled("295|image", false);
            app().getSharedPreferences("comfy_remote", 0).edit().putString("server", base)
                    .putString("generation_jobs_v18", "[]").commit();
            WorkflowStore.upsert(app(), profile); WorkflowStore.setActiveId(app(), profile.id);
            MainActivity activity = (MainActivity) inst().startActivitySync(new Intent(app(), MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                for (int round = 1; round <= 2; round++) {
                    if (round == 2) {
                        clickBranch(activity, true); clickBranch(activity, false);
                        // Branch compilation restores UI defaults; retain a normal user parameter edit.
                        inst().runOnMainSync(() -> {
                            try {
                                WorkflowProfile active = (WorkflowProfile) field(activity, "activeProfile");
                                JSONObject graph = active.promptObject();
                                graph.getJSONObject("316").getJSONObject("inputs").put("prompt", "Edited park scene");
                                active.promptJson = graph.toString();
                                Method rebuild = MainActivity.class.getDeclaredMethod("rebuildParameterControls");
                                rebuild.setAccessible(true); rebuild.invoke(activity);
                            } catch (Exception e) { throw new RuntimeException(e); }
                        });
                    }
                    long deadline = SystemClock.elapsedRealtime() + 20000; boolean[] ready = {false};
                    while (SystemClock.elapsedRealtime() < deadline) {
                        inst().runOnMainSync(() -> {
                            try { ready[0] = ((Button) field(activity, "submitButton")).isEnabled(); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        });
                        if (ready[0]) break; Thread.sleep(100);
                    }
                    assertTrue("A upload and branch compilation must complete", ready[0]);
                    inst().runOnMainSync(() -> {
                        try {
                            assertFalse(branch(activity, "295").isChecked());
                            ((Button) field(activity, "submitButton")).performClick();
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    while (received.size() < round && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
                    assertEquals(round, received.size()); assertOnlyA(received.get(round - 1));
                }
                File dir = new File("/sdcard/Pictures/ComfyRemoteValidation"); dir.mkdirs();
                try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(app().getFilesDir(), "reference-requests.json")), StandardCharsets.UTF_8)) {
                    out.write(new JSONArray(received).toString(2));
                }
                try (OutputStream out = new FileOutputStream(new File(dir, "reference-switches.png"))) {
                    inst().getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } finally { inst().runOnMainSync(activity::finish); }
        } finally { WorkflowUiConverter.clearObjectInfoCache(); }
    }
    private void clickBranch(MainActivity activity, boolean enabled) throws Exception {
        inst().runOnMainSync(() -> {
            try { Switch s = branch(activity, "295"); assertNotEquals(enabled, s.isChecked()); s.performClick(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        long deadline = SystemClock.elapsedRealtime() + 20000; boolean[] done = {false};
        while (SystemClock.elapsedRealtime() < deadline) {
            inst().runOnMainSync(() -> {
                try { done[0] = !(Boolean) field(activity, "variantSwitching") && branch(activity, "295").isChecked() == enabled; }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (done[0]) return; Thread.sleep(100);
        }
        fail("Reference switch did not settle to " + enabled);
    }
}
