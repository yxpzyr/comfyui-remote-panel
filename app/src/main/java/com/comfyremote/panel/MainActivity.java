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
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_WORKFLOW = 101;
    private static final int REQ_IMAGE_BASE = 1000;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private SharedPreferences prefs;

    private EditText addressEdit;
    private TextView connectionState, workflowState, statusText;
    private Button submitButton, queueButton, saveAllButton;
    private LinearLayout workflowStrip, inputList, parameterList, outputList;

    private final List<ImageBinding> imageBindings = new ArrayList<>();
    private final List<FieldBinding> fieldBindings = new ArrayList<>();
    private final List<OutputBinding> outputBindings = new ArrayList<>();
    private final Map<String, Map<String, Uri>> sessionInputUris = new HashMap<>();

    private List<WorkflowProfile> profiles = new ArrayList<>();
    private WorkflowProfile activeProfile;
    private String lastPreviewedPromptId = "";

    private final GenerationManager.JobListener jobListener = job -> runOnUiThread(() -> {
        refreshQueueButton();
        if (job == null) return;
        if (JobRecord.COMPLETED.equals(job.status)) {
            statusText.setText("状态：" + job.workflowName + " 已完成 · " + job.message);
            if (!job.outputRefs().isEmpty() && (!safe(job.promptId).equals(lastPreviewedPromptId) || outputBindings.isEmpty())) {
                loadJobOutputs(job);
            }
        } else if (JobRecord.FAILED.equals(job.status)) {
            statusText.setText("状态：任务失败 · " + job.message);
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);
        prefs = getSharedPreferences("comfy_remote", MODE_PRIVATE);
        profiles = WorkflowStore.load(this);
        resolveActiveProfile();
        buildUi();
        rebuildWorkflowStrip();
        rebuildDynamicControls();
        GenerationManager.resumePending(this, currentServer());
        syncLatestCompletedOutput();
    }

    @Override protected void onStart() {
        super.onStart();
        GenerationManager.addListener(jobListener);
        refreshQueueButton();
        syncLatestCompletedOutput();
    }

    @Override protected void onResume() {
        super.onResume();
        GenerationManager.resumePending(this, currentServer());
        refreshQueueButton();
        syncLatestCompletedOutput();
    }

    @Override protected void onStop() {
        captureSessionInputs();
        saveCurrentOverrides();
        GenerationManager.removeListener(jobListener);
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(ThemeManager.background(this));
        scroll.addView(root);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView mainTitle = text("ComfyUI 远程面板", 25, true);
        Button appearanceBtn = button(ThemeManager.isDark(this) ? "🌙 深色" : "☀ 外观");
        titleRow.addView(mainTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(appearanceBtn, new LinearLayout.LayoutParams(dp(96), dp(44)));
        root.addView(titleRow);
        appearanceBtn.setOnClickListener(v -> showAppearanceMenu());
        TextView subtitle = text("V1.9 · 后台队列 / 实时图库 / 任务进度 / 夜间模式", 13, false);
        subtitle.setTextColor(ThemeManager.secondary(this));
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
        addressEdit.setText(prefs.getString("server", "8188"));
        addressEdit.setTextSize(15);
        connRow.addView(addressEdit, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button connectBtn = button("连接");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(84), dp(48));
        btnLp.setMargins(dp(8), 0, 0, 0);
        connRow.addView(connectBtn, btnLp);
        connectionCard.addView(connRow);
        connectionState = text("● 未检测", 13, false);
        connectionState.setTextColor(ThemeManager.muted(this));
        connectionState.setPadding(0, dp(6), 0, 0);
        connectionCard.addView(connectionState);
        connectBtn.setOnClickListener(v -> testConnection());

        LinearLayout workflowCard = cardWithTopMargin(root);
        workflowCard.addView(sectionTitle("工作流库"));
        LinearLayout workflowButtons = new LinearLayout(this);
        workflowButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button importBtn = button("＋ 导入工作流");
        Button manageBtn = button("管理当前");
        workflowButtons.addView(importBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        manageLp.setMargins(dp(8), 0, 0, 0);
        workflowButtons.addView(manageBtn, manageLp);
        workflowCard.addView(workflowButtons);
        workflowState = text("当前：未选择", 13, false);
        workflowState.setTextColor(ThemeManager.secondary(this));
        workflowState.setPadding(0, dp(8), 0, dp(6));
        workflowCard.addView(workflowState);

        HorizontalScrollView workflowScroll = new HorizontalScrollView(this);
        workflowScroll.setHorizontalScrollBarEnabled(false);
        workflowStrip = new LinearLayout(this);
        workflowStrip.setOrientation(LinearLayout.HORIZONTAL);
        workflowScroll.addView(workflowStrip);
        workflowCard.addView(workflowScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView workflowTip = text("工作流只需导入一次，会保存在 App 中；可同时保存多个并随时切换。", 12, false);
        workflowTip.setTextColor(ThemeManager.muted(this));
        workflowTip.setPadding(0, dp(6), 0, 0);
        workflowCard.addView(workflowTip);
        importBtn.setOnClickListener(v -> chooseWorkflow());
        manageBtn.setOnClickListener(v -> manageCurrentWorkflow());

        LinearLayout inputCard = cardWithTopMargin(root);
        inputCard.addView(sectionTitle("输入图片"));
        TextView inputTip = text("工作流保持常驻。选择图片后会先自动上传；上传完成即可点“开始生成”，点击后立刻可以继续选择下一张图片排队。", 12, false);
        inputTip.setTextColor(ThemeManager.muted(this));
        inputTip.setPadding(0, 0, 0, dp(8));
        inputCard.addView(inputTip);
        inputList = new LinearLayout(this);
        inputList.setOrientation(LinearLayout.VERTICAL);
        inputCard.addView(inputList);

        LinearLayout paramCard = cardWithTopMargin(root);
        paramCard.addView(sectionTitle("提示词 / 开关"));
        TextView paramTip = text("每个工作流会分别记住自己的可编辑参数；切换工作流不会互相覆盖。", 12, false);
        paramTip.setTextColor(ThemeManager.muted(this));
        paramTip.setPadding(0, 0, 0, dp(8));
        paramCard.addView(paramTip);
        parameterList = new LinearLayout(this);
        parameterList.setOrientation(LinearLayout.VERTICAL);
        paramCard.addView(parameterList);

        LinearLayout outputCard = cardWithTopMargin(root);
        outputCard.addView(sectionTitle("最近完成输出"));
        outputList = new LinearLayout(this);
        outputList.setOrientation(LinearLayout.VERTICAL);
        outputCard.addView(outputList);
        outputList.addView(text("任务完成后会自动恢复最新输出，即使曾切后台、锁屏或切换页面；完整历史请打开图库。", 13, false));
        saveAllButton = button("全部保存当前输出");
        saveAllButton.setEnabled(false);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        saveLp.setMargins(0, dp(8), 0, 0);
        outputCard.addView(saveAllButton, saveLp);
        saveAllButton.setOnClickListener(v -> saveAllOutputs());

        LinearLayout actionCard = cardWithTopMargin(root);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        submitButton = button("▶ 开始生成");
        queueButton = button("队列");
        Button galleryBtn = button("图库");
        buttons.addView(submitButton, weightedButton());
        LinearLayout.LayoutParams mid = weightedButton(); mid.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(queueButton, mid);
        buttons.addView(galleryBtn, weightedButton());
        actionCard.addView(buttons);
        statusText = text("状态：等待操作", 14, false);
        statusText.setPadding(0, dp(12), 0, 0);
        actionCard.addView(statusText);
        submitButton.setOnClickListener(v -> submitGeneration());
        queueButton.setOnClickListener(v -> {
            saveAddress();
            startActivity(new Intent(this, QueueActivity.class));
        });
        galleryBtn.setOnClickListener(v -> {
            saveAddress();
            startActivity(new Intent(this, GalleryActivity.class));
        });

        TextView tip = text("提交按钮不会等待上一张生成完成；工作流 A / B / C 可交叉连续排队，真正的 GPU 执行顺序由 ComfyUI 队列负责。", 12, false);
        tip.setTextColor(ThemeManager.muted(this));
        tip.setPadding(0, dp(16), 0, 0);
        root.addView(tip);
        setContentView(scroll);
    }

    private void resolveActiveProfile() {
        String activeId = WorkflowStore.getActiveId(this);
        activeProfile = WorkflowStore.find(profiles, activeId);
        if (activeProfile == null && !profiles.isEmpty()) {
            activeProfile = profiles.get(0);
            WorkflowStore.setActiveId(this, activeProfile.id);
        }
    }

    private void refreshProfiles() {
        profiles = WorkflowStore.load(this);
        resolveActiveProfile();
        rebuildWorkflowStrip();
    }

    private void rebuildWorkflowStrip() {
        if (workflowStrip == null) return;
        workflowStrip.removeAllViews();
        if (profiles.isEmpty()) {
            TextView empty = text("还没有保存的工作流", 13, false);
            empty.setTextColor(ThemeManager.muted(this));
            workflowStrip.addView(empty);
            workflowState.setText("当前：未选择");
            return;
        }
        WorkflowStore.sort(profiles);
        for (WorkflowProfile profile : profiles) {
            boolean active = activeProfile != null && activeProfile.id.equals(profile.id);
            Button b = button((profile.favorite ? "★ " : "") + profile.name);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? ThemeManager.selected(this) : ThemeManager.imagePlaceholder(this));
            bg.setCornerRadius(dp(12));
            if (active) bg.setStroke(dp(2), ThemeManager.accent(this));
            b.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            lp.setMargins(0, 0, dp(8), 0);
            workflowStrip.addView(b, lp);
            b.setOnClickListener(v -> switchWorkflow(profile.id));
        }
        workflowState.setText("当前：" + (activeProfile == null ? "未选择" : activeProfile.name) + " · 已保存 " + profiles.size() + " 个");
    }

    private void switchWorkflow(String profileId) {
        if (activeProfile != null && activeProfile.id.equals(profileId)) return;
        captureSessionInputs();
        saveCurrentOverrides();
        WorkflowProfile next = WorkflowStore.find(profiles, profileId);
        if (next == null) return;
        activeProfile = next;
        WorkflowStore.setActiveId(this, next.id);
        rebuildWorkflowStrip();
        rebuildDynamicControls();
        statusText.setText("状态：已切换到 " + next.name);
    }

    private void chooseWorkflow() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(i, REQ_WORKFLOW);
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
                String desired = getDisplayName(uri);
                List<WorkflowProfile> latest = WorkflowStore.load(this);
                String name = WorkflowStore.uniqueName(latest, desired);
                JSONObject uiCopy = uiFormat ? new JSONObject(json.toString()) : null;
                WorkflowProfile profile = WorkflowProfile.create(name, prompt, uiCopy);
                WorkflowStore.upsert(this, profile);
                WorkflowStore.setActiveId(this, profile.id);
                runOnUiThread(() -> {
                    captureSessionInputs();
                    profiles = WorkflowStore.load(this);
                    activeProfile = WorkflowStore.find(profiles, profile.id);
                    rebuildWorkflowStrip();
                    rebuildDynamicControls();
                    statusText.setText("状态：工作流已保存，以后无需重复加载");
                    toast("已保存工作流：" + profile.name);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    refreshProfiles();
                    showError("工作流读取失败", e);
                });
            }
        });
    }

    private void manageCurrentWorkflow() {
        if (activeProfile == null) { toast("请先导入一个工作流"); return; }
        saveCurrentOverrides();
        String starAction = activeProfile.favorite ? "取消常用置顶" : "★ 设为常用置顶";
        new AlertDialog.Builder(this)
                .setTitle(activeProfile.name)
                .setItems(new String[]{"重命名", starAction, "删除这个工作流"}, (dialog, which) -> {
                    if (which == 0) renameCurrentWorkflow();
                    else if (which == 1) toggleFavorite();
                    else deleteCurrentWorkflow();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void renameCurrentWorkflow() {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(activeProfile.name);
        edit.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("重命名工作流")
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) { toast("名称不能为空"); return; }
                    activeProfile.name = name;
                    WorkflowStore.upsert(this, activeProfile);
                    refreshProfiles();
                })
                .show();
    }

    private void toggleFavorite() {
        activeProfile.favorite = !activeProfile.favorite;
        WorkflowStore.upsert(this, activeProfile);
        refreshProfiles();
        toast(activeProfile.favorite ? "已设为常用" : "已取消常用");
    }

    private void deleteCurrentWorkflow() {
        WorkflowProfile deleting = activeProfile;
        new AlertDialog.Builder(this)
                .setTitle("删除工作流？")
                .setMessage("只删除手机 App 中保存的工作流配置，不会删除电脑上的模型或 ComfyUI 文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    sessionInputUris.remove(deleting.id);
                    WorkflowStore.delete(this, deleting.id);
                    profiles = WorkflowStore.load(this);
                    resolveActiveProfile();
                    rebuildWorkflowStrip();
                    rebuildDynamicControls();
                })
                .show();
    }

    private void rebuildDynamicControls() {
        rebuildInputControls();
        rebuildParameterControls();
        refreshSubmitEnabled();
    }

    private void rebuildInputControls() {
        inputList.removeAllViews();
        imageBindings.clear();
        if (activeProfile == null) {
            inputList.addView(text("先导入或选择一个工作流", 13, false));
            refreshSubmitEnabled();
            return;
        }
        try {
            List<WorkflowUtils.NodeChoice> nodes = WorkflowUtils.findLoadImageNodes(activeProfile.promptObject());
            if (nodes.isEmpty()) {
                inputList.addView(text("这个工作流没有需要从手机替换的文件型图片输入。", 13, false));
                refreshSubmitEnabled();
                return;
            }
            Map<String, Uri> remembered = sessionInputUris.get(activeProfile.id);
            for (int i = 0; i < nodes.size(); i++) {
                WorkflowUtils.NodeChoice n = nodes.get(i);
                ImageBinding b = new ImageBinding(i, n);
                String key = inputKey(n);
                if (remembered != null) b.uri = remembered.get(key);
                imageBindings.add(b);

                LinearLayout box = miniCard();
                TextView label = text("输入图 " + (i + 1) + " · " + n.title + " · 节点 " + n.id, 13, true);
                box.addView(label);
                b.replaceSwitch = new Switch(this);
                b.replaceSwitch.setText("替换此输入");
                b.replaceSwitch.setChecked(true);
                box.addView(b.replaceSwitch);
                b.preview = previewImage();
                box.addView(b.preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(178)));
                b.selectButton = button(b.uri == null ? "选择图片" : "已选择：" + getDisplayName(b.uri));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
                blp.setMargins(0, dp(6), 0, 0);
                box.addView(b.selectButton, blp);
                b.uploadState = text(b.uri == null ? "等待选择图片" : "准备上传…", 11, false);
                b.uploadState.setTextColor(ThemeManager.muted(this));
                b.uploadState.setPadding(0, dp(5), 0, 0);
                box.addView(b.uploadState);
                b.selectButton.setOnClickListener(v -> chooseInputImage(b));
                b.replaceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked && b.uri != null && (b.remoteFilename == null || b.remoteFilename.isEmpty()) && !b.uploading) {
                        startInputUpload(b, b.uri);
                    }
                    refreshSubmitEnabled();
                });
                LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                boxLp.setMargins(0, 0, 0, dp(10));
                inputList.addView(box, boxLp);
                if (b.uri != null) {
                    loadPreviewOnly(b, b.uri);
                    startInputUpload(b, b.uri);
                }
            }
        } catch (Exception e) {
            inputList.addView(text("读取工作流输入失败：" + e.getMessage(), 13, false));
        }
        refreshSubmitEnabled();
    }

    private void chooseInputImage(ImageBinding binding) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_IMAGE_BASE + binding.index);
    }

    private void loadInputPreview(ImageBinding binding, Uri uri) {
        binding.uri = uri;
        binding.remoteFilename = "";
        binding.uploadError = "";
        binding.replaceSwitch.setChecked(true);
        binding.selectButton.setText("已选择：" + getDisplayName(uri));
        if (activeProfile != null) {
            sessionInputUris.computeIfAbsent(activeProfile.id, x -> new HashMap<>()).put(inputKey(binding.choice), uri);
        }
        statusText.setText("状态：已选择输入图 " + (binding.index + 1) + "，正在上传到 ComfyUI…");
        loadPreviewOnly(binding, uri);
        startInputUpload(binding, uri);
    }

    private void startInputUpload(ImageBinding binding, Uri uri) {
        if (binding == null || uri == null) return;
        final long token = ++binding.uploadToken;
        binding.uploading = true;
        binding.remoteFilename = "";
        binding.uploadError = "";
        if (binding.uploadState != null) {
            binding.uploadState.setText("上传中…");
            binding.uploadState.setTextColor(ThemeManager.warning(this));
        }
        refreshSubmitEnabled();
        final String server = currentServer();
        pool.submit(() -> {
            try {
                ComfyApiClient.UploadResult uploaded = new ComfyApiClient(server).uploadImage(this, uri);
                runOnUiThread(() -> {
                    if (token != binding.uploadToken || binding.uri == null || !binding.uri.equals(uri)) return;
                    binding.uploading = false;
                    binding.remoteFilename = uploaded.workflowFilename();
                    if (binding.uploadState != null) {
                        binding.uploadState.setText("✓ 已上传，可开始生成");
                        binding.uploadState.setTextColor(ThemeManager.success(this));
                    }
                    statusText.setText("状态：输入图 " + (binding.index + 1) + " 已上传，可直接开始生成");
                    refreshSubmitEnabled();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (token != binding.uploadToken) return;
                    binding.uploading = false;
                    binding.uploadError = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (binding.uploadState != null) {
                        binding.uploadState.setText("上传失败 · 点“选择图片”重试");
                        binding.uploadState.setTextColor(ThemeManager.error(this));
                    }
                    statusText.setText("状态：输入图片上传失败");
                    refreshSubmitEnabled();
                });
            }
        });
    }

    private void loadPreviewOnly(ImageBinding binding, Uri uri) {
        pool.submit(() -> {
            try {
                Bitmap b = decodeScaled(uri, 1200, 1200);
                runOnUiThread(() -> {
                    if (binding.preview != null) binding.preview.setImageBitmap(b);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("图片预览失败：" + e.getMessage()));
            }
        });
    }

    private void refreshSubmitEnabled() {
        if (submitButton == null) return;
        boolean ready = activeProfile != null;
        if (ready) {
            for (ImageBinding b : imageBindings) {
                if (b.replaceSwitch != null && b.replaceSwitch.isChecked()) {
                    if (b.uri == null || b.uploading || b.remoteFilename == null || b.remoteFilename.isEmpty()) {
                        ready = false;
                        break;
                    }
                }
            }
        }
        submitButton.setEnabled(ready);
    }

    private void captureSessionInputs() {
        if (activeProfile == null || imageBindings.isEmpty()) return;
        Map<String, Uri> map = sessionInputUris.computeIfAbsent(activeProfile.id, x -> new HashMap<>());
        for (ImageBinding b : imageBindings) {
            if (b.uri == null) map.remove(inputKey(b.choice));
            else map.put(inputKey(b.choice), b.uri);
        }
    }

    private String inputKey(WorkflowUtils.NodeChoice n) { return n.id + "|" + n.inputName; }

    private void rebuildParameterControls() {
        parameterList.removeAllViews();
        fieldBindings.clear();
        if (activeProfile == null) {
            parameterList.addView(text("当前没有工作流", 13, false));
            return;
        }
        try {
            List<WorkflowUtils.FieldChoice> fields = WorkflowUtils.findEditableFields(activeProfile.promptObject());
            if (fields.isEmpty()) {
                parameterList.addView(text("当前没有检测到提示词或开关参数。", 13, false));
                return;
            }
            for (WorkflowUtils.FieldChoice f : fields) {
                Object shown = activeProfile.overrides.has(WorkflowProfile.overrideKey(f.nodeId, f.inputName))
                        ? activeProfile.overrides.opt(WorkflowProfile.overrideKey(f.nodeId, f.inputName)) : f.value;
                FieldBinding b = new FieldBinding(f);
                fieldBindings.add(b);
                LinearLayout box = miniCard();
                box.addView(text(f.label(), 12, true));
                if (f.kind == WorkflowUtils.FieldChoice.BOOL) {
                    Switch sw = new Switch(this);
                    sw.setText("启用");
                    sw.setChecked(shown instanceof Boolean ? (Boolean) shown : Boolean.parseBoolean(String.valueOf(shown)));
                    b.switchView = sw;
                    box.addView(sw);
                } else {
                    EditText ed = new EditText(this);
                    ed.setText(String.valueOf(shown == null ? "" : shown));
                    ed.setTextSize(14);
                    if (f.kind == WorkflowUtils.FieldChoice.TEXT) {
                        ed.setSingleLine(false);
                        ed.setMinLines(3);
                        ed.setGravity(Gravity.TOP | Gravity.START);
                    } else ed.setSingleLine(true);
                    b.editView = ed;
                    box.addView(ed, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(8));
                parameterList.addView(box, lp);
            }
        } catch (Exception e) {
            parameterList.addView(text("读取可编辑参数失败：" + e.getMessage(), 13, false));
        }
    }

    private void saveCurrentOverrides() {
        if (activeProfile == null || fieldBindings.isEmpty()) return;
        try {
            for (FieldBinding b : fieldBindings) {
                Object value = readFieldValue(b);
                activeProfile.overrides.put(WorkflowProfile.overrideKey(b.choice.nodeId, b.choice.inputName), value);
            }
            WorkflowStore.upsert(this, activeProfile);
        } catch (Exception ignored) {}
    }

    private Object readFieldValue(FieldBinding b) {
        WorkflowUtils.FieldChoice f = b.choice;
        if (f.kind == WorkflowUtils.FieldChoice.BOOL) return b.switchView.isChecked();
        String raw = b.editView.getText().toString();
        if (f.value instanceof Integer || f.value instanceof Long) {
            try { return Long.parseLong(raw.trim()); } catch (Exception ignored) { return f.value; }
        }
        if (f.value instanceof Number) {
            try { return Double.parseDouble(raw.trim()); } catch (Exception ignored) { return f.value; }
        }
        return raw;
    }

    private List<FieldOverride> snapshotFieldOverrides() {
        List<FieldOverride> out = new ArrayList<>();
        for (FieldBinding b : fieldBindings) out.add(new FieldOverride(b.choice, readFieldValue(b)));
        return out;
    }

    private void submitGeneration() {
        if (activeProfile == null) { toast("请先导入或选择工作流"); return; }
        saveAddress();
        saveCurrentOverrides();
        captureSessionInputs();

        final WorkflowProfile profile = activeProfile;
        final List<ImageJob> imageJobs = snapshotImageJobs();
        final List<FieldOverride> overrides = snapshotFieldOverrides();

        for (ImageJob j : imageJobs) {
            if (!j.replace) continue;
            if (j.uri == null) {
                showError("缺少输入图片", new Exception("输入图 " + (j.index + 1) + " 已开启“替换此输入”，请先选择图片；如果想保留工作流原图，请关闭该开关。"));
                return;
            }
            if (j.uploading || j.remoteFilename == null || j.remoteFilename.isEmpty()) {
                toast(j.uploadError == null || j.uploadError.isEmpty() ? "图片还在上传，请稍候" : "图片上传失败，请重新选择后再试");
                return;
            }
        }

        try {
            JSONObject prompt = new JSONObject(profile.promptJson); // V1.9: loaded workflow stays compiled; no repeated /object_info conversion
            applyFieldOverrides(prompt, overrides);
            for (ImageJob b : imageJobs) {
                if (!b.replace) continue;
                WorkflowUtils.setInputValue(prompt, b.choice.id, b.choice.inputName, b.remoteFilename);
            }

            final String server = currentServer();
            final String previewUri = firstInputUri(imageJobs);
            final JobRecord record = JobRecord.create(profile.id, profile.name, buildInputSummary(imageJobs),
                    previewUri, server, prompt.toString());
            JobStore.add(this, record);
            GenerationManager.notifySubmitted(this, record.localId);
            GenerationManager.enqueueSubmission(this, record.localId);

            // Important: release the UI immediately. The serial submitter preserves click order in background.
            if (activeProfile != null && activeProfile.id.equals(profile.id)) clearCurrentInputSelections();
            statusText.setText("状态：已加入提交队列 · 现在可以立刻选择下一张图片");
            refreshQueueButton();
            refreshSubmitEnabled();
            toast("已加入队列");
        } catch (Exception e) {
            showError("准备任务失败", e);
        }
    }

    private List<ImageJob> snapshotImageJobs() {
        List<ImageJob> out = new ArrayList<>();
        for (ImageBinding b : imageBindings) {
            out.add(new ImageJob(b.index, b.choice, b.uri,
                    b.replaceSwitch != null && b.replaceSwitch.isChecked(),
                    b.remoteFilename, b.uploading, b.uploadError));
        }
        return out;
    }

    private String firstInputUri(List<ImageJob> jobs) {
        for (ImageJob j : jobs) if (j.replace && j.uri != null) return j.uri.toString();
        return "";
    }

    private String buildInputSummary(List<ImageJob> jobs) {
        List<String> names = new ArrayList<>();
        for (ImageJob j : jobs) if (j.replace && j.uri != null) names.add(getDisplayName(j.uri));
        if (names.isEmpty()) return "使用工作流原输入";
        return String.join("、", names);
    }

    private void clearCurrentInputSelections() {
        if (activeProfile == null) return;
        Map<String, Uri> map = sessionInputUris.computeIfAbsent(activeProfile.id, x -> new HashMap<>());
        for (ImageBinding b : imageBindings) {
            b.uploadToken++;
            b.uri = null;
            b.remoteFilename = "";
            b.uploading = false;
            b.uploadError = "";
            map.remove(inputKey(b.choice));
            if (b.preview != null) b.preview.setImageDrawable(null);
            if (b.selectButton != null) b.selectButton.setText("选择下一张图片");
            if (b.uploadState != null) {
                b.uploadState.setText("等待选择图片");
                b.uploadState.setTextColor(ThemeManager.muted(this));
            }
        }
    }

    private void applyFieldOverrides(JSONObject prompt, List<FieldOverride> overrides) throws Exception {
        for (FieldOverride b : overrides) {
            JSONObject node = prompt.optJSONObject(b.choice.nodeId);
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null || !inputs.has(b.choice.inputName)) continue;
            inputs.put(b.choice.inputName, b.value);
        }
    }

    private void syncLatestCompletedOutput() {
        JobRecord latest = JobStore.latestCompleted(this);
        if (latest == null) return;
        if (!safe(latest.promptId).equals(lastPreviewedPromptId) || outputBindings.isEmpty()) loadJobOutputs(latest);
    }

    private void loadJobOutputs(JobRecord job) {
        if (job == null) return;
        final String serverForJob = job.server == null || job.server.isEmpty() ? currentServer() : job.server;
        pool.submit(() -> {
            try {
                ComfyApiClient api = new ComfyApiClient(serverForJob);
                List<ImageRef> refs = new ArrayList<>(job.outputRefs());
                if (refs.isEmpty() && job.promptId != null && !job.promptId.isEmpty()) {
                    try {
                        JSONObject history = api.getHistoryForPrompt(job.promptId);
                        refs = api.parseImagesFromPromptHistory(history, job.promptId);
                        if (refs.isEmpty()) refs = api.parseImagesDeepForPrompt(history, job.promptId);
                    } catch (Exception ignored) {}
                    if (refs.isEmpty()) {
                        try {
                            JSONObject all = api.getAllHistory(300);
                            refs = api.parseImagesFromPromptHistory(all, job.promptId);
                            if (refs.isEmpty()) refs = api.parseImagesDeepForPrompt(all, job.promptId);
                        } catch (Exception ignored) {}
                    }
                }
                if (refs.isEmpty()) {
                    runOnUiThread(() -> {
                        outputList.removeAllViews();
                        outputList.addView(text("任务已完成，但没有可读取的图片记录。", 13, false));
                        saveAllButton.setEnabled(false);
                    });
                    return;
                }
                List<OutputBinding> loaded = new ArrayList<>();
                int limit = Math.min(refs.size(), 12);
                for (int i = 0; i < limit; i++) {
                    ImageRef ref = refs.get(i);
                    try {
                        ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                        Bitmap bmp = decodeScaled(dl.bytes, 1400, 1400);
                        if (bmp != null) loaded.add(new OutputBinding(ref, dl, bmp));
                    } catch (Exception ignored) {}
                }
                if (!loaded.isEmpty()) runOnUiThread(() -> {
                    lastPreviewedPromptId = safe(job.promptId);
                    renderOutputs(loaded);
                });
            } catch (Exception ignored) {}
        });
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
            box.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(235)));
            Button save = button("保存这张");
            box.addView(save, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
            save.setOnClickListener(v -> saveOneOutput(o));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(10));
            outputList.addView(box, lp);
        }
        saveAllButton.setEnabled(!loaded.isEmpty());
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

    private void refreshQueueButton() {
        if (queueButton == null) return;
        int active = 0;
        for (JobRecord job : JobStore.list(this)) if (!job.isTerminal()) active++;
        queueButton.setText(active > 0 ? "队列 (" + active + ")" : "队列");
    }

    private void testConnection() {
        saveAddress();
        connectionState.setText("● 正在检测…");
        connectionState.setTextColor(ThemeManager.warning(this));
        pool.submit(() -> {
            try {
                new ComfyApiClient(currentServer()).testConnection();
                runOnUiThread(() -> {
                    connectionState.setText("● 已连接  " + ComfyApiClient.normalizeBase(currentServer()));
                    connectionState.setTextColor(ThemeManager.success(this));
                    toast("ComfyUI 连接成功");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connectionState.setText("● 连接失败");
                    connectionState.setTextColor(ThemeManager.error(this));
                    showError("连接失败", e);
                });
            }
        });
    }

    private void showAppearanceMenu() {
        String current = ThemeManager.getMode(this);
        int checked = ThemeManager.MODE_LIGHT.equals(current) ? 1 : ThemeManager.MODE_DARK.equals(current) ? 2 : 0;
        String[] items = new String[]{"跟随系统", "浅色模式", "深色模式"};
        new AlertDialog.Builder(this)
                .setTitle("外观模式")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    String mode = which == 1 ? ThemeManager.MODE_LIGHT : which == 2 ? ThemeManager.MODE_DARK : ThemeManager.MODE_SYSTEM;
                    ThemeManager.setMode(this, mode);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String safe(String s) { return s == null ? "" : s; }

    private void saveAddress() {
        prefs.edit().putString("server", addressEdit.getText().toString().trim()).apply();
    }

    private String currentServer() {
        String s = addressEdit == null ? prefs.getString("server", "8188") : addressEdit.getText().toString().trim();
        return s == null || s.isEmpty() ? "8188" : s;
    }

    private void setStatus(String text) { runOnUiThread(() -> statusText.setText("状态：" + text)); }
    private String shortId(String id) { return id == null || id.length() <= 8 ? id : id.substring(0, 8); }

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
        if (uri == null) return "";
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "图片" : uri.getLastPathSegment();
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
        if (bytes == null || bytes.length == 0) return null;
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

    private LinearLayout cardWithTopMargin(LinearLayout root) {
        LinearLayout c = card();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(14), 0, 0);
        root.addView(c, lp);
        return c;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ThemeManager.card(this));
        bg.setCornerRadius(dp(16));
        l.setBackground(bg);
        return l;
    }

    private LinearLayout miniCard() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ThemeManager.miniCard(this));
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
        t.setTextColor(ThemeManager.text(this));
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
        i.setBackgroundColor(ThemeManager.imagePlaceholder(this));
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
        final String remoteFilename;
        final boolean uploading;
        final String uploadError;
        ImageJob(int index, WorkflowUtils.NodeChoice choice, Uri uri, boolean replace,
                 String remoteFilename, boolean uploading, String uploadError) {
            this.index = index; this.choice = choice; this.uri = uri; this.replace = replace;
            this.remoteFilename = remoteFilename == null ? "" : remoteFilename;
            this.uploading = uploading;
            this.uploadError = uploadError == null ? "" : uploadError;
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
        TextView uploadState;
        String remoteFilename = "";
        String uploadError = "";
        boolean uploading = false;
        long uploadToken = 0;
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
