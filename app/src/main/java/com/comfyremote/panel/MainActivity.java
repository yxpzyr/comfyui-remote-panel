package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_WORKFLOW = 101;
    private static final int REQ_IMAGE_BASE = 1000;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private SharedPreferences prefs;

    private EditText addressEdit;
    private TextView connectionState, workflowState, statusText;
    private Button generateBtn, stopBtn, saveAllBtn;
    private LinearLayout inputList, parameterList, outputList;

    private JSONObject workflowPrompt;
    private JSONObject rawUiWorkflow;
    private String workflowName = "未选择";
    private volatile boolean cancelPolling = false;

    private final List<ImageBinding> imageBindings = new ArrayList<>();
    private final List<FieldBinding> fieldBindings = new ArrayList<>();
    private final List<OutputBinding> outputBindings = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("comfy_remote", MODE_PRIVATE);
        buildUi();
        restoreState();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(246, 247, 249));
        scroll.addView(root);

        root.addView(text("ComfyUI 远程面板", 25, true));
        TextView subtitle = text("V1.6 · 通用多输入 / 提示词 / 多输出工作流面板", 13, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        LinearLayout connectionCard = card();
        root.addView(connectionCard);
        connectionCard.addView(sectionTitle("ComfyUI 端口地址"));
        LinearLayout connRow = new LinearLayout(this);
        connRow.setOrientation(LinearLayout.HORIZONTAL);
        connRow.setGravity(Gravity.CENTER_VERTICAL);
        addressEdit = new EditText(this);
        addressEdit.setSingleLine(true);
        addressEdit.setHint("8188 或 127.0.0.1:8188");
        addressEdit.setTextSize(15);
        connRow.addView(addressEdit, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button connectBtn = button("连接");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(84), dp(48));
        btnLp.setMargins(dp(8), 0, 0, 0);
        connRow.addView(connectBtn, btnLp);
        connectionCard.addView(connRow);
        connectionState = text("● 未检测", 13, false);
        connectionState.setTextColor(Color.GRAY);
        connectionState.setPadding(0, dp(6), 0, 0);
        connectionCard.addView(connectionState);
        connectBtn.setOnClickListener(v -> testConnection());

        LinearLayout workflowCard = cardWithTopMargin(root);
        workflowCard.addView(sectionTitle("工作流"));
        Button workflowBtn = button("上传工作流 JSON");
        workflowCard.addView(workflowBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        workflowState = text("当前：未选择", 13, false);
        workflowState.setTextColor(Color.DKGRAY);
        workflowState.setPadding(0, dp(8), 0, 0);
        workflowCard.addView(workflowState);
        workflowBtn.setOnClickListener(v -> chooseWorkflow());

        LinearLayout inputCard = cardWithTopMargin(root);
        inputCard.addView(sectionTitle("输入图片"));
        TextView inputTip = text("自动识别 1～N 个图片输入。关闭“替换此输入”时会保留工作流原来的图片设置。", 12, false);
        inputTip.setTextColor(Color.GRAY);
        inputTip.setPadding(0, 0, 0, dp(8));
        inputCard.addView(inputTip);
        inputList = new LinearLayout(this);
        inputList.setOrientation(LinearLayout.VERTICAL);
        inputCard.addView(inputList);
        inputList.addView(text("载入工作流后自动显示输入节点", 13, false));

        LinearLayout paramCard = cardWithTopMargin(root);
        paramCard.addView(sectionTitle("提示词 / 开关"));
        TextView paramTip = text("会自动显示提示词、布尔开关和常见 switch/mode 参数。", 12, false);
        paramTip.setTextColor(Color.GRAY);
        paramTip.setPadding(0, 0, 0, dp(8));
        paramCard.addView(paramTip);
        parameterList = new LinearLayout(this);
        parameterList.setOrientation(LinearLayout.VERTICAL);
        paramCard.addView(parameterList);
        parameterList.addView(text("当前没有可编辑参数", 13, false));

        LinearLayout outputCard = cardWithTopMargin(root);
        outputCard.addView(sectionTitle("输出图片"));
        outputList = new LinearLayout(this);
        outputList.setOrientation(LinearLayout.VERTICAL);
        outputCard.addView(outputList);
        outputList.addView(text("生成后会显示全部输出图", 13, false));
        saveAllBtn = button("全部保存");
        saveAllBtn.setEnabled(false);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        saveLp.setMargins(0, dp(8), 0, 0);
        outputCard.addView(saveAllBtn, saveLp);
        saveAllBtn.setOnClickListener(v -> saveAllOutputs());

        LinearLayout actionCard = cardWithTopMargin(root);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        generateBtn = button("▶ 开始生成");
        stopBtn = button("停止");
        Button galleryBtn = button("图库");
        buttons.addView(generateBtn, weightedButton());
        LinearLayout.LayoutParams mid = weightedButton(); mid.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(stopBtn, mid);
        buttons.addView(galleryBtn, weightedButton());
        actionCard.addView(buttons);
        statusText = text("状态：等待操作", 14, false);
        statusText.setPadding(0, dp(12), 0, 0);
        actionCard.addView(statusText);
        generateBtn.setOnClickListener(v -> startGeneration());
        stopBtn.setOnClickListener(v -> stopGeneration());
        galleryBtn.setOnClickListener(v -> {
            saveAddress();
            startActivity(new Intent(this, GalleryActivity.class));
        });

        TextView tip = text("普通 ComfyUI JSON 与 API JSON 均可使用。多输入图片、提示词、开关和多个输出会按工作流自动显示。", 12, false);
        tip.setTextColor(Color.GRAY);
        tip.setPadding(0, dp(16), 0, 0);
        root.addView(tip);
        setContentView(scroll);
    }

    private LinearLayout cardWithTopMargin(LinearLayout root) {
        LinearLayout c = card();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(14), 0, 0);
        root.addView(c, lp);
        return c;
    }

    private void restoreState() {
        addressEdit.setText(prefs.getString("server", "8188"));
        workflowName = prefs.getString("workflow_name", "未选择");
        String saved = prefs.getString("workflow_json", "");
        String savedUi = prefs.getString("workflow_ui_json", "");
        if (!saved.isEmpty()) {
            try {
                workflowPrompt = new JSONObject(saved);
                rawUiWorkflow = savedUi.isEmpty() ? null : new JSONObject(savedUi);
                workflowState.setText("当前：" + workflowName);
                rebuildDynamicControls();
            } catch (Exception e) {
                workflowPrompt = null;
                rawUiWorkflow = null;
                workflowState.setText("当前：保存的工作流已失效，请重新选择");
            }
        }
    }

    private void testConnection() {
        saveAddress();
        connectionState.setText("● 正在检测…");
        connectionState.setTextColor(Color.rgb(210, 140, 0));
        pool.submit(() -> {
            try {
                new ComfyApiClient(currentServer()).testConnection();
                runOnUiThread(() -> {
                    connectionState.setText("● 已连接  " + ComfyApiClient.normalizeBase(currentServer()));
                    connectionState.setTextColor(Color.rgb(30, 150, 80));
                    toast("ComfyUI 连接成功");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connectionState.setText("● 连接失败");
                    connectionState.setTextColor(Color.rgb(200, 60, 60));
                    showError("连接失败", e);
                });
            }
        });
    }

    private void chooseWorkflow() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(i, REQ_WORKFLOW);
    }

    private void chooseInputImage(ImageBinding binding) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_IMAGE_BASE + binding.index);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        if (requestCode == REQ_WORKFLOW) {
            loadWorkflow(uri);
            return;
        }
        if (requestCode >= REQ_IMAGE_BASE) {
            int idx = requestCode - REQ_IMAGE_BASE;
            if (idx >= 0 && idx < imageBindings.size()) loadInputPreview(imageBindings.get(idx), uri);
        }
    }

    private void loadWorkflow(Uri uri) {
        workflowState.setText("当前：正在读取并解析工作流…");
        final String server = currentServer();
        pool.submit(() -> {
            try {
                String raw = readText(uri, 32 * 1024 * 1024);
                JSONObject json = new JSONObject(raw);
                boolean uiFormat = json.optJSONArray("nodes") != null ||
                        (json.optJSONObject("workflow") != null && json.optJSONObject("workflow").optJSONArray("nodes") != null);
                if (uiFormat) setStatus("正在读取远程节点信息并转换普通工作流…");
                JSONObject prompt = uiFormat ? WorkflowUiConverter.toApiPrompt(json, server) : WorkflowUtils.extractPromptObject(json);
                String name = getDisplayName(uri);
                JSONObject uiCopy = uiFormat ? new JSONObject(json.toString()) : null;
                runOnUiThread(() -> applyWorkflow(prompt, uiCopy, name));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    workflowState.setText("当前：读取失败，请重新选择");
                    showError("工作流读取失败", e);
                });
            }
        });
    }

    private void applyWorkflow(JSONObject prompt, JSONObject uiRaw, String name) {
        workflowPrompt = prompt;
        rawUiWorkflow = uiRaw;
        workflowName = name == null ? "workflow.json" : name;
        persistWorkflow();
        workflowState.setText("当前：" + workflowName);
        rebuildDynamicControls();
        statusText.setText("状态：工作流已载入，可直接设置输入和参数");
        toast("已载入工作流");
    }

    private void persistWorkflow() {
        if (workflowPrompt == null) return;
        SharedPreferences.Editor e = prefs.edit()
                .putString("workflow_json", workflowPrompt.toString())
                .putString("workflow_name", workflowName);
        if (rawUiWorkflow != null) e.putString("workflow_ui_json", rawUiWorkflow.toString());
        else e.remove("workflow_ui_json");
        e.apply();
    }

    private void rebuildDynamicControls() {
        rebuildInputControls();
        rebuildParameterControls();
        clearOutputs();
    }

    private void rebuildInputControls() {
        inputList.removeAllViews();
        imageBindings.clear();
        List<WorkflowUtils.NodeChoice> nodes = workflowPrompt == null ? new ArrayList<>() : WorkflowUtils.findLoadImageNodes(workflowPrompt);
        if (nodes.isEmpty()) {
            inputList.addView(text("这个工作流没有需要从手机替换的图片输入。", 13, false));
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowUtils.NodeChoice n = nodes.get(i);
            ImageBinding b = new ImageBinding(i, n);
            imageBindings.add(b);

            LinearLayout box = miniCard();
            TextView label = text("输入图 " + (i + 1) + " · " + n.title + " · 节点 " + n.id, 13, true);
            box.addView(label);
            b.replaceSwitch = new Switch(this);
            b.replaceSwitch.setText("替换此输入");
            b.replaceSwitch.setChecked(true);
            box.addView(b.replaceSwitch);
            b.preview = previewImage();
            box.addView(b.preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
            b.selectButton = button("选择图片");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            blp.setMargins(0, dp(6), 0, 0);
            box.addView(b.selectButton, blp);
            b.selectButton.setOnClickListener(v -> chooseInputImage(b));
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            boxLp.setMargins(0, 0, 0, dp(10));
            inputList.addView(box, boxLp);
        }
    }

    private void rebuildParameterControls() {
        parameterList.removeAllViews();
        fieldBindings.clear();
        List<WorkflowUtils.FieldChoice> fields = workflowPrompt == null ? new ArrayList<>() : WorkflowUtils.findEditableFields(workflowPrompt);
        if (fields.isEmpty()) {
            parameterList.addView(text("当前没有检测到提示词或开关参数。", 13, false));
            return;
        }
        for (WorkflowUtils.FieldChoice f : fields) {
            FieldBinding b = new FieldBinding(f);
            fieldBindings.add(b);
            LinearLayout box = miniCard();
            TextView label = text(f.label(), 12, true);
            box.addView(label);
            if (f.kind == WorkflowUtils.FieldChoice.BOOL) {
                Switch sw = new Switch(this);
                sw.setText("启用");
                sw.setChecked(Boolean.TRUE.equals(f.value));
                b.switchView = sw;
                box.addView(sw);
            } else {
                EditText ed = new EditText(this);
                ed.setText(String.valueOf(f.value));
                ed.setTextSize(14);
                if (f.kind == WorkflowUtils.FieldChoice.TEXT) {
                    ed.setSingleLine(false);
                    ed.setMinLines(3);
                    ed.setGravity(Gravity.TOP | Gravity.START);
                } else {
                    ed.setSingleLine(true);
                }
                b.editView = ed;
                box.addView(ed, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(8));
            parameterList.addView(box, lp);
        }
    }

    private void loadInputPreview(ImageBinding binding, Uri uri) {
        binding.uri = uri;
        binding.replaceSwitch.setChecked(true);
        binding.selectButton.setText("已选择：" + getDisplayName(uri));
        statusText.setText("状态：已选择输入图 " + (binding.index + 1));
        pool.submit(() -> {
            try {
                Bitmap b = decodeScaled(uri, 1200, 1200);
                runOnUiThread(() -> binding.preview.setImageBitmap(b));
            } catch (Exception e) {
                runOnUiThread(() -> showError("图片读取失败", e));
            }
        });
    }

    private void startGeneration() {
        if (workflowPrompt == null) { toast("请先上传工作流 JSON"); return; }
        saveAddress();
        cancelPolling = false;
        generateBtn.setEnabled(false);
        saveAllBtn.setEnabled(false);
        clearOutputs();
        statusText.setText("状态：正在准备工作流…");
        final String server = currentServer();
        final List<ImageJob> imageJobs = snapshotImageJobs();
        final List<FieldOverride> overrides = snapshotFieldOverrides();

        pool.submit(() -> {
            try {
                ComfyApiClient api = new ComfyApiClient(server);
                JSONObject prompt = rawUiWorkflow != null
                        ? WorkflowUiConverter.toApiPrompt(new JSONObject(rawUiWorkflow.toString()), server)
                        : new JSONObject(workflowPrompt.toString());

                applyFieldOverrides(prompt, overrides);

                for (ImageJob b : imageJobs) {
                    if (!b.replace || b.uri == null) continue;
                    setStatus("正在上传输入图 " + (b.index + 1) + "…");
                    ComfyApiClient.UploadResult uploaded = api.uploadImage(this, b.uri);
                    WorkflowUtils.setInputValue(prompt, b.choice.id, b.choice.inputName, uploaded.workflowFilename());
                }

                setStatus("正在提交工作流…");
                String promptId = api.queuePrompt(prompt);
                setStatus("已提交，生成中… 任务 " + shortId(promptId));

                JSONObject lastHistory = null;
                List<ImageRef> refs = null;
                for (int i = 0; i < 900 && !cancelPolling; i++) {
                    Thread.sleep(2000);
                    try {
                        lastHistory = api.getHistoryForPrompt(promptId);
                        refs = api.parseImagesFromPromptHistory(lastHistory, promptId);
                        if (refs != null && !refs.isEmpty()) break;
                        String err = api.historyError(lastHistory, promptId);
                        if (!err.isEmpty()) throw new Exception("ComfyUI 执行失败：" + err);
                        if (api.isHistoryCompleted(lastHistory, promptId)) {
                            refs = findImageRefsDeepInOutputs(lastHistory, promptId);
                            if (refs == null || refs.isEmpty()) {
                                throw new Exception("工作流已完成，但历史记录中没有发现可读取的图像文件输出。可能是自定义输出节点没有向 history 返回 filename/subfolder/type。");
                            }
                            break;
                        }
                    } catch (java.io.FileNotFoundException ignored) {}
                }
                if (cancelPolling) return;
                if (refs == null || refs.isEmpty()) throw new Exception("等待生成结果超时");

                List<OutputBinding> loaded = new ArrayList<>();
                int limit = Math.min(refs.size(), 20);
                for (int i = 0; i < limit; i++) {
                    ImageRef ref = refs.get(i);
                    try {
                        ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                        Bitmap bmp = decodeScaled(dl.bytes, 1600, 1600);
                        loaded.add(new OutputBinding(ref, dl, bmp));
                    } catch (Exception ignored) {}
                }
                if (loaded.isEmpty()) throw new Exception("已检测到输出记录，但无法下载任何输出图片。");

                runOnUiThread(() -> {
                    renderOutputs(loaded);
                    generateBtn.setEnabled(true);
                    saveAllBtn.setEnabled(true);
                    statusText.setText("状态：生成完成 · 共 " + loaded.size() + " 张输出");
                    toast("生成完成");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    generateBtn.setEnabled(true);
                    statusText.setText("状态：生成失败");
                    showError("生成失败", e);
                });
            }
        });
    }

    private List<ImageJob> snapshotImageJobs() {
        List<ImageJob> out = new ArrayList<>();
        for (ImageBinding b : imageBindings) out.add(new ImageJob(b.index, b.choice, b.uri, b.replaceSwitch.isChecked()));
        return out;
    }

    private List<FieldOverride> snapshotFieldOverrides() {
        List<FieldOverride> out = new ArrayList<>();
        for (FieldBinding b : fieldBindings) {
            WorkflowUtils.FieldChoice f = b.choice;
            Object value;
            if (f.kind == WorkflowUtils.FieldChoice.BOOL) {
                value = b.switchView.isChecked();
            } else {
                String text = b.editView.getText().toString();
                if (f.value instanceof Integer || f.value instanceof Long) {
                    try { value = Long.parseLong(text.trim()); } catch (Exception e) { value = f.value; }
                } else if (f.value instanceof Number) {
                    try { value = Double.parseDouble(text.trim()); } catch (Exception e) { value = f.value; }
                } else value = text;
            }
            out.add(new FieldOverride(f, value));
        }
        return out;
    }

    private void applyFieldOverrides(JSONObject prompt, List<FieldOverride> overrides) throws Exception {
        for (FieldOverride b : overrides) {
            WorkflowUtils.FieldChoice f = b.choice;
            JSONObject node = prompt.optJSONObject(f.nodeId);
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null || !inputs.has(f.inputName)) continue;
            inputs.put(f.inputName, b.value);
        }
    }

    private void renderOutputs(List<OutputBinding> loaded) {
        outputBindings.clear();
        outputBindings.addAll(loaded);
        outputList.removeAllViews();
        for (int i = 0; i < loaded.size(); i++) {
            OutputBinding o = loaded.get(i);
            LinearLayout box = miniCard();
            box.addView(text("输出 " + (i + 1) + " · " + o.ref.filename, 12, true));
            ImageView image = previewImage();
            image.setImageBitmap(o.bitmap);
            box.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
            Button save = button("保存这张");
            box.addView(save, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
            save.setOnClickListener(v -> saveOneOutput(o));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(10));
            outputList.addView(box, lp);
        }
    }

    private void clearOutputs() {
        outputBindings.clear();
        if (outputList != null) {
            outputList.removeAllViews();
            outputList.addView(text("生成后会显示全部输出图", 13, false));
        }
        if (saveAllBtn != null) saveAllBtn.setEnabled(false);
    }

    private void saveOneOutput(OutputBinding o) {
        pool.submit(() -> {
            try {
                MediaSaver.saveImage(this, o.download.bytes, o.ref.filename, o.download.mime);
                runOnUiThread(() -> toast("已保存到 Pictures/ComfyRemote"));
            } catch (Exception e) { runOnUiThread(() -> showError("保存失败", e)); }
        });
    }

    private void saveAllOutputs() {
        if (outputBindings.isEmpty()) { toast("当前没有输出图"); return; }
        pool.submit(() -> {
            int ok = 0;
            for (OutputBinding o : outputBindings) {
                try {
                    MediaSaver.saveImage(this, o.download.bytes, o.ref.filename, o.download.mime);
                    ok++;
                } catch (Exception ignored) {}
            }
            int finalOk = ok;
            runOnUiThread(() -> toast("已保存 " + finalOk + " 张到 Pictures/ComfyRemote"));
        });
    }

    private List<ImageRef> findImageRefsDeepInOutputs(JSONObject history, String promptId) {
        List<ImageRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JSONObject entry = history.optJSONObject(promptId);
        if (entry == null && history.has("outputs")) entry = history;
        if (entry == null) return out;
        Object outputs = entry.opt("outputs");
        collectImageRefs(outputs, out, seen, 0);
        return out;
    }

    private void collectImageRefs(Object value, List<ImageRef> out, Set<String> seen, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 10) return;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            String filename = o.optString("filename", "");
            if (!filename.isEmpty()) {
                ImageRef ref = ImageRef.fromJson(o);
                if (seen.add(ref.key())) out.add(ref);
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) collectImageRefs(o.opt(it.next()), out, seen, depth + 1);
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) collectImageRefs(a.opt(i), out, seen, depth + 1);
        }
    }

    private void stopGeneration() {
        cancelPolling = true;
        statusText.setText("状态：正在请求停止…");
        pool.submit(() -> {
            try {
                new ComfyApiClient(currentServer()).interrupt();
                runOnUiThread(() -> {
                    generateBtn.setEnabled(true);
                    statusText.setText("状态：已发送停止请求");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    generateBtn.setEnabled(true);
                    showError("停止失败", e);
                });
            }
        });
    }

    private void saveAddress() { prefs.edit().putString("server", addressEdit.getText().toString().trim()).apply(); }
    private String currentServer() {
        String s = addressEdit.getText().toString().trim();
        return s.isEmpty() ? "8188" : s;
    }
    private void setStatus(String text) { runOnUiThread(() -> statusText.setText("状态：" + text)); }
    private String shortId(String id) { return id.length() <= 8 ? id : id.substring(0, 8); }

    private String readText(Uri uri, int maxBytes) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("无法打开文件");
            byte[] buf = new byte[32 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) throw new Exception("工作流文件过大");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String getDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    private Bitmap decodeScaled(Uri uri, int maxW, int maxH) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        while (bounds.outWidth / sample > maxW * 2 || bounds.outHeight / sample > maxH * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap b = BitmapFactory.decodeStream(in, null, opts);
            if (b == null) throw new Exception("无法解码图片");
            return b;
        }
    }

    public static Bitmap decodeScaled(byte[] bytes, int maxW, int maxH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > maxW * 2 || bounds.outHeight / sample > maxH * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private void showError(String title, Exception e) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                .setPositiveButton("知道了", null)
                .show();
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        l.setBackground(bg);
        return l;
    }

    private LinearLayout miniCard() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(248, 249, 251));
        bg.setCornerRadius(dp(12));
        l.setBackground(bg);
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 16, true);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(28, 30, 35));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setAllCaps(false);
        return b;
    }
    private ImageView previewImage() {
        ImageView i = new ImageView(this);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setBackgroundColor(Color.rgb(238, 240, 244));
        i.setPadding(dp(4), dp(4), dp(4), dp(4));
        return i;
    }
    private LinearLayout.LayoutParams weightedButton() { return new LinearLayout.LayoutParams(0, dp(50), 1f); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class ImageJob {
        final int index;
        final WorkflowUtils.NodeChoice choice;
        final Uri uri;
        final boolean replace;
        ImageJob(int index, WorkflowUtils.NodeChoice choice, Uri uri, boolean replace) {
            this.index = index; this.choice = choice; this.uri = uri; this.replace = replace;
        }
    }
    private static final class FieldOverride {
        final WorkflowUtils.FieldChoice choice;
        final Object value;
        FieldOverride(WorkflowUtils.FieldChoice choice, Object value) { this.choice = choice; this.value = value; }
    }
    private static final class ImageBinding {
        final int index;
        final WorkflowUtils.NodeChoice choice;
        Uri uri;
        Switch replaceSwitch;
        ImageView preview;
        Button selectButton;
        ImageBinding(int index, WorkflowUtils.NodeChoice choice) { this.index = index; this.choice = choice; }
    }
    private static final class FieldBinding {
        final WorkflowUtils.FieldChoice choice;
        EditText editView;
        Switch switchView;
        FieldBinding(WorkflowUtils.FieldChoice choice) { this.choice = choice; }
    }
    private static final class OutputBinding {
        final ImageRef ref;
        final ComfyApiClient.ImageDownload download;
        final Bitmap bitmap;
        OutputBinding(ImageRef ref, ComfyApiClient.ImageDownload download, Bitmap bitmap) {
            this.ref = ref; this.download = download; this.bitmap = bitmap;
        }
    }
}
