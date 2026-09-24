package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_WORKFLOW = 101;
    private static final int REQ_BACKGROUND = 102;
    private static final int REQ_SPLASH = 103;
    private static final int REQ_IMAGE_BASE = 1000;
    private static final int REQ_MULTI_IMAGE_BASE = 2000;
    private static final int REQ_MASK_EDITOR_BASE = 3000;
    private static final String FOLDER_ALL = "__all__";
    private static final String FOLDER_FAVORITES = "__favorites__";
    private static final String PREF_FOLDER_FILTER = "workflow_folder_filter_v24";

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    // Keep batch uploads/submissions strictly ordered.
    private final ExecutorService batchPool = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private EditText addressEdit;
    private TextView connectionState, workflowState, statusText;
    private Button submitButton, queueButton, saveAllButton, advancedToggleButton, folderFilterButton;
    private LinearLayout workflowStrip, inputList, parameterList, outputSelectorList, outputList;
    private LinearLayout connectionCardView, parameterCardView, outputSelectCardView;
    private ScrollView mainScroll;
    private boolean advancedExpanded = false;
    private volatile boolean workflowImporting = false;
    private boolean refreshWorkflowsOnResume = false;

    private final List<ImageBinding> imageBindings = new ArrayList<>();
    private final List<FieldBinding> fieldBindings = new ArrayList<>();
    private final List<OutputNodeBinding> outputNodeBindings = new ArrayList<>();
    private final List<OutputBinding> outputBindings = new ArrayList<>();
    // V2.3 keeps full task output metadata but downloads only the final image on the home screen.
    private final List<ImageRef> currentOutputRefs = new ArrayList<>();
    private JobRecord currentOutputJob;
    private final Map<String, Map<String, Uri>> sessionInputUris = new HashMap<>();
    private final Set<String> outputRefreshInFlight = new HashSet<>();
    private final Object objectInfoLock = new Object();
    private JSONObject cachedObjectInfo;
    private String cachedObjectInfoServer = "";
    private long cachedObjectInfoAt = 0L;

    private List<WorkflowProfile> profiles = new ArrayList<>();
    private List<WorkflowFolder> workflowFolders = new ArrayList<>();
    private String workflowFolderFilter = FOLDER_ALL;
    private WorkflowProfile activeProfile;
    private String lastPreviewedPromptId = "";

    private final GenerationManager.JobListener jobListener = job -> runOnUiThread(() -> {
        refreshQueueButton();
        if (job == null) return;
        if (JobRecord.COMPLETED.equals(job.status)) {
            statusText.setText("状态：" + job.workflowName + " 已完成 · " + job.message);
            if (!job.outputRefs().isEmpty() && (!safe(job.promptId).equals(lastPreviewedPromptId) || currentOutputRefs.isEmpty())) {
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
        workflowFolders = WorkflowFolderStore.load(this);
        workflowFolderFilter = prefs.getString(PREF_FOLDER_FILTER, FOLDER_ALL);
        normalizeFolderFilter();
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
        if (refreshWorkflowsOnResume) {
            refreshWorkflowsOnResume = false;
            workflowFolders = WorkflowFolderStore.load(this);
            profiles = WorkflowStore.load(this);
            resolveActiveProfile();
            rebuildWorkflowStrip();
            rebuildDynamicControls();
        }
        GenerationManager.resumePending(this, currentServer());
        refreshQueueButton();
        syncLatestCompletedOutput();
    }

    @Override protected void onStop() {
        captureSessionInputs();
        saveCurrentOverrides();
        saveOutputSelection();
        GenerationManager.removeListener(jobListener);
        super.onStop();
    }

    private void buildUi() {
        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(ThemeManager.background(this));
        mainScroll.addView(root);
        applyCustomBackground(mainScroll, root);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView mainTitle = text("ComfyUI 远程面板", 25, true);
        Button appearanceBtn = button(ThemeManager.isDark(this) ? "🌙 外观" : "☀ 外观");
        titleRow.addView(mainTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(appearanceBtn, new LinearLayout.LayoutParams(dp(96), dp(44)));
        root.addView(titleRow);
        appearanceBtn.setOnClickListener(v -> showAppearanceMenu());
        TextView subtitle = text("V2.4.1 · 文件夹式工作流库 / 整个文件夹导入 / 蒙版双指缩放", 13, false);
        subtitle.setTextColor(ThemeManager.secondary(this));
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        LinearLayout workflowCard = cardWithTopMargin(root);
        workflowCard.addView(sectionTitle("工作流"));
        LinearLayout workflowButtons = new LinearLayout(this);
        workflowButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button importBtn = button("＋ 导入");
        Button listBtn = button("工作流库");
        Button manageBtn = button("管理当前");
        workflowButtons.addView(importBtn, weightedButton());
        LinearLayout.LayoutParams listLp = weightedButton(); listLp.setMargins(dp(8), 0, dp(8), 0);
        workflowButtons.addView(listBtn, listLp);
        workflowButtons.addView(manageBtn, weightedButton());
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
        TextView workflowTip = text("横向按钮显示当前工作流所在文件夹；点“工作流库”进入真正的文件夹视图，可新建文件夹、移动工作流或导入整个手机文件夹。", 12, false);
        workflowTip.setTextColor(ThemeManager.muted(this));
        workflowTip.setPadding(0, dp(6), 0, 0);
        workflowCard.addView(workflowTip);
        importBtn.setOnClickListener(v -> showWorkflowImportMenu());
        listBtn.setOnClickListener(v -> openWorkflowLibrary(null));
        manageBtn.setOnClickListener(v -> manageCurrentWorkflow());

        advancedToggleButton = button("⚙ 展开高级设置（连接 / 参数 / 输出节点）");
        LinearLayout.LayoutParams advLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        advLp.setMargins(0, dp(12), 0, 0);
        root.addView(advancedToggleButton, advLp);
        advancedToggleButton.setOnClickListener(v -> {
            advancedExpanded = !advancedExpanded;
            applyAdvancedVisibility();
        });

        connectionCardView = cardWithTopMargin(root);
        connectionCardView.addView(sectionTitle("ComfyUI 端口地址"));
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
        connectionCardView.addView(connRow);
        connectionState = text("● 未检测", 13, false);
        connectionState.setTextColor(ThemeManager.muted(this));
        connectionState.setPadding(0, dp(6), 0, 0);
        connectionCardView.addView(connectionState);
        connectBtn.setOnClickListener(v -> testConnection());

        LinearLayout inputCard = cardWithTopMargin(root);
        inputCard.addView(sectionTitle("输入图片"));
        TextView inputTip = text("V2.4 继续自动识别支持 MASK 的图片输入节点并显示“编辑蒙版遮罩”。蒙版写入 PNG Alpha 后与原图一起提交；普通图片节点不会显示该功能。", 12, false);
        inputTip.setTextColor(ThemeManager.muted(this));
        inputTip.setPadding(0, 0, 0, dp(8));
        inputCard.addView(inputTip);
        inputList = new LinearLayout(this);
        inputList.setOrientation(LinearLayout.VERTICAL);
        inputCard.addView(inputList);
        Button clearInputsBtn = button("清空本工作流已记住的输入图");
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        clearLp.setMargins(0, dp(4), 0, 0);
        inputCard.addView(clearInputsBtn, clearLp);
        clearInputsBtn.setOnClickListener(v -> clearPersistedInputs());

        parameterCardView = cardWithTopMargin(root);
        parameterCardView.addView(sectionTitle("提示词 / 开关"));
        TextView paramTip = text("每个工作流分别记住自己的可编辑参数；切换工作流不会互相覆盖。", 12, false);
        paramTip.setTextColor(ThemeManager.muted(this));
        paramTip.setPadding(0, 0, 0, dp(8));
        parameterCardView.addView(paramTip);
        parameterList = new LinearLayout(this);
        parameterList.setOrientation(LinearLayout.VERTICAL);
        parameterCardView.addView(parameterList);

        outputSelectCardView = cardWithTopMargin(root);
        outputSelectCardView.addView(sectionTitle("输出节点"));
        TextView outputSelectTip = text("工作流有多个输出点时，可独立开启/关闭。未勾选的输出节点不会参与本次 ComfyUI 执行；至少保留一个输出。", 12, false);
        outputSelectTip.setTextColor(ThemeManager.muted(this));
        outputSelectTip.setPadding(0, 0, 0, dp(8));
        outputSelectCardView.addView(outputSelectTip);
        outputSelectorList = new LinearLayout(this);
        outputSelectorList.setOrientation(LinearLayout.VERTICAL);
        outputSelectCardView.addView(outputSelectorList);
        Button refreshOutputBtn = button("重新检测输出节点");
        LinearLayout.LayoutParams outputRefreshLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        outputRefreshLp.setMargins(0, dp(6), 0, 0);
        outputSelectCardView.addView(refreshOutputBtn, outputRefreshLp);
        refreshOutputBtn.setOnClickListener(v -> refreshOutputMetadataAsync(true));

        LinearLayout outputCard = cardWithTopMargin(root);
        outputCard.addView(sectionTitle("最近完成输出 · 最终图"));
        outputList = new LinearLayout(this);
        outputList.setOrientation(LinearLayout.VERTICAL);
        outputCard.addView(outputList);
        outputList.addView(text("V2.4 主页只加载最后生成的最终图；多图任务点 📁 可查看全部过程图。", 13, false));
        saveAllButton = button("全部保存当前输出");
        saveAllButton.setEnabled(false);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        saveLp.setMargins(0, dp(8), 0, 0);
        outputCard.addView(saveAllButton, saveLp);
        saveAllButton.setOnClickListener(v -> saveAllOutputs());

        LinearLayout actionCard = cardWithTopMargin(root);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        submitButton = button("▶ 生成当前图");
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

        TextView tip = text("批量图片会先顺序上传，再按同一顺序进入手机提交队列；ComfyUI 最终仍按自身队列执行。", 12, false);
        tip.setTextColor(ThemeManager.muted(this));
        tip.setPadding(0, dp(16), 0, 0);
        root.addView(tip);
        applyAdvancedVisibility();
        setContentView(mainScroll);
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
        workflowFolders = WorkflowFolderStore.load(this);
        normalizeFolderFilter();
        profiles = WorkflowStore.load(this);
        resolveActiveProfile();
        rebuildWorkflowStrip();
    }

    private void rebuildWorkflowStrip() {
        if (workflowStrip == null) return;
        workflowStrip.removeAllViews();
        List<WorkflowProfile> visible = profilesForHomeStrip();
        if (visible.isEmpty()) {
            TextView empty = text(profiles.isEmpty() ? "还没有保存的工作流" : "当前分类没有工作流", 13, false);
            empty.setTextColor(ThemeManager.muted(this));
            workflowStrip.addView(empty);
        } else {
            WorkflowStore.sort(visible);
            for (WorkflowProfile profile : visible) {
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
        }
        if (profiles.isEmpty()) workflowState.setText("当前：未选择");
        else {
            String current = activeProfile == null ? "未选择" : activeProfile.name;
            String folder = activeProfile == null ? "未分类" : folderNameForProfile(activeProfile);
            workflowState.setText("当前：" + current + " · 📁 " + folder + " · 本文件夹 " + visible.size() + " / 总计 " + profiles.size());
        }
    }

    private List<WorkflowProfile> profilesForHomeStrip() {
        List<WorkflowProfile> out = new ArrayList<>();
        if (activeProfile == null) {
            out.addAll(profiles);
            return out;
        }
        String activeFolder = effectiveFolderId(activeProfile);
        for (WorkflowProfile p : profiles) if (activeFolder.equals(effectiveFolderId(p))) out.add(p);
        return out;
    }

    private void showWorkflowImportMenu() {
        if (workflowImporting) { toast("工作流正在导入，请稍候"); return; }
        new AlertDialog.Builder(this)
                .setTitle("导入工作流")
                .setItems(new String[]{"导入工作流文件（可多选）", "📂 导入整个文件夹", "打开工作流库"}, (d, which) -> {
                    if (which == 0) chooseWorkflow();
                    else if (which == 1) openWorkflowLibrary(WorkflowLibraryActivity.ACTION_IMPORT_FOLDER);
                    else openWorkflowLibrary(null);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openWorkflowLibrary(String autoAction) {
        Intent i = new Intent(this, WorkflowLibraryActivity.class);
        if (autoAction != null && !autoAction.isEmpty()) i.putExtra(WorkflowLibraryActivity.EXTRA_AUTO_ACTION, autoAction);
        refreshWorkflowsOnResume = true;
        startActivity(i);
    }

    private void showWorkflowList() {
        if (profiles == null || profiles.isEmpty()) { toast("还没有保存的工作流"); return; }
        List<WorkflowProfile> visible = profilesForCurrentFilter();
        if (visible.isEmpty()) { toast("当前分类没有工作流"); return; }
        WorkflowStore.sort(visible);
        String[] names = new String[visible.size()];
        int checked = -1;
        for (int i = 0; i < visible.size(); i++) {
            WorkflowProfile p = visible.get(i);
            String folderPrefix = FOLDER_ALL.equals(workflowFolderFilter) ? "〔" + folderNameForProfile(p) + "〕 " : "";
            names[i] = folderPrefix + (p.favorite ? "★ " : "") + p.name;
            if (activeProfile != null && activeProfile.id.equals(p.id)) checked = i;
        }
        final int initial = checked;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("工作流库 · " + folderFilterLabel())
                .setSingleChoiceItems(names, checked, null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            String profileId = visible.get(position).id;
            dialog.dismiss();
            if (position != initial || activeProfile == null || !profileId.equals(activeProfile.id)) switchWorkflow(profileId);
        }));
        dialog.show();
    }

    private List<WorkflowProfile> profilesForCurrentFilter() {
        List<WorkflowProfile> out = new ArrayList<>();
        for (WorkflowProfile p : profiles) {
            String folderId = effectiveFolderId(p);
            if (FOLDER_ALL.equals(workflowFolderFilter)) out.add(p);
            else if (FOLDER_FAVORITES.equals(workflowFolderFilter)) { if (p.favorite) out.add(p); }
            else if (safe(workflowFolderFilter).equals(folderId)) out.add(p);
        }
        return out;
    }

    private String effectiveFolderId(WorkflowProfile p) {
        if (p == null || p.folderId == null || p.folderId.isEmpty()) return "";
        return WorkflowFolderStore.find(workflowFolders, p.folderId) == null ? "" : p.folderId;
    }

    private String folderNameForProfile(WorkflowProfile p) {
        String id = effectiveFolderId(p);
        if (id.isEmpty()) return "未分类";
        WorkflowFolder f = WorkflowFolderStore.find(workflowFolders, id);
        return f == null ? "未分类" : f.name;
    }

    private String folderFilterLabel() {
        if (FOLDER_ALL.equals(workflowFolderFilter)) return "全部工作流";
        if (FOLDER_FAVORITES.equals(workflowFolderFilter)) return "★ 常用";
        if (workflowFolderFilter == null || workflowFolderFilter.isEmpty()) return "未分类";
        WorkflowFolder f = WorkflowFolderStore.find(workflowFolders, workflowFolderFilter);
        return f == null ? "未分类" : f.name;
    }

    private void normalizeFolderFilter() {
        if (workflowFolderFilter == null || workflowFolderFilter.isEmpty() ||
                FOLDER_ALL.equals(workflowFolderFilter) || FOLDER_FAVORITES.equals(workflowFolderFilter)) return;
        if (WorkflowFolderStore.find(workflowFolders, workflowFolderFilter) == null) workflowFolderFilter = FOLDER_ALL;
    }

    private void setFolderFilter(String id) {
        workflowFolderFilter = id == null ? FOLDER_ALL : id;
        normalizeFolderFilter();
        prefs.edit().putString(PREF_FOLDER_FILTER, workflowFolderFilter).apply();
        rebuildWorkflowStrip();
    }

    private void showFolderFilterMenu() {
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add("全部工作流"); ids.add(FOLDER_ALL);
        labels.add("★ 常用"); ids.add(FOLDER_FAVORITES);
        labels.add("未分类"); ids.add("");
        for (WorkflowFolder f : workflowFolders) { labels.add(f.name); ids.add(f.id); }
        new AlertDialog.Builder(this)
                .setTitle("选择工作流分类")
                .setItems(labels.toArray(new String[0]), (d, which) -> setFolderFilter(ids.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFolderManager() {
        if (workflowImporting) { toast("工作流正在导入，请等待完成后再管理文件夹"); return; }
        List<String> labels = new ArrayList<>();
        labels.add("＋ 新建文件夹");
        for (WorkflowFolder f : workflowFolders) labels.add("📁 " + f.name);
        new AlertDialog.Builder(this)
                .setTitle("管理工作流文件夹")
                .setMessage("文件夹是逻辑分类。删除文件夹不会删除里面的工作流，工作流会自动移回“未分类”。")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) createFolderDialog();
                    else manageFolder(workflowFolders.get(which - 1));
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void createFolderDialog() {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint("例如：动漫转真人");
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) { toast("文件夹名称不能为空"); return; }
                    WorkflowFolder f = WorkflowFolderStore.create(this, name);
                    workflowFolders = WorkflowFolderStore.load(this);
                    setFolderFilter(f.id);
                    toast("已创建文件夹：" + f.name);
                }).show();
    }

    private void manageFolder(WorkflowFolder folder) {
        if (folder == null) return;
        new AlertDialog.Builder(this)
                .setTitle(folder.name)
                .setItems(new String[]{"进入这个文件夹", "重命名", "删除文件夹"}, (d, which) -> {
                    if (which == 0) setFolderFilter(folder.id);
                    else if (which == 1) renameFolderDialog(folder);
                    else deleteFolderConfirm(folder);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void renameFolderDialog(WorkflowFolder folder) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(folder.name);
        edit.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("重命名文件夹")
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) { toast("文件夹名称不能为空"); return; }
                    WorkflowFolderStore.rename(this, folder.id, name);
                    workflowFolders = WorkflowFolderStore.load(this);
                    rebuildWorkflowStrip();
                }).show();
    }

    private void deleteFolderConfirm(WorkflowFolder folder) {
        int count = 0;
        for (WorkflowProfile p : profiles) if (folder.id.equals(effectiveFolderId(p))) count++;
        new AlertDialog.Builder(this)
                .setTitle("删除文件夹？")
                .setMessage("不会删除工作流。这个文件夹里的 " + count + " 个工作流会移回“未分类”。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    List<WorkflowProfile> latest = WorkflowStore.load(this);
                    for (WorkflowProfile p : latest) {
                        if (!folder.id.equals(safe(p.folderId))) continue;
                        p.folderId = "";
                        p.name = WorkflowStore.uniqueName(latest, p.name, "", p.id);
                        WorkflowStore.upsert(this, p);
                    }
                    WorkflowFolderStore.delete(this, folder.id);
                    workflowFolders = WorkflowFolderStore.load(this);
                    profiles = WorkflowStore.load(this);
                    if (folder.id.equals(workflowFolderFilter)) workflowFolderFilter = "";
                    prefs.edit().putString(PREF_FOLDER_FILTER, workflowFolderFilter).apply();
                    resolveActiveProfile();
                    rebuildWorkflowStrip();
                    rebuildDynamicControls();
                    toast("文件夹已删除，工作流已移到未分类");
                }).show();
    }

    private void applyAdvancedVisibility() {
        int visibility = advancedExpanded ? View.VISIBLE : View.GONE;
        if (connectionCardView != null) connectionCardView.setVisibility(visibility);
        if (parameterCardView != null) parameterCardView.setVisibility(visibility);
        if (outputSelectCardView != null) outputSelectCardView.setVisibility(visibility);
        if (advancedToggleButton != null) {
            advancedToggleButton.setText(advancedExpanded
                    ? "⚙ 收起高级设置（连接 / 参数 / 输出节点）"
                    : "⚙ 展开高级设置（连接 / 参数 / 输出节点）");
        }
    }

    private void applyCustomBackground(ScrollView scroll, LinearLayout root) {
        String uriText = CustomizationStore.getBackgroundUri(this);
        if (uriText == null || uriText.isEmpty()) return;
        try {
            Bitmap bitmap = decodeScaled(Uri.parse(uriText), 1800, 2400);
            BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
            drawable.setGravity(Gravity.FILL);
            scroll.setBackground(drawable);
            root.setBackgroundColor(Color.TRANSPARENT);
        } catch (Exception ignored) {
            CustomizationStore.setBackgroundUri(this, "");
        }
    }

    private void clearPersistedInputs() {
        if (activeProfile == null) { toast("请先选择工作流"); return; }
        File dir = new File(getFilesDir(), "masks_v22");
        File[] files = dir.listFiles();
        if (files != null) {
            String prefix = activeProfile.id.replaceAll("[^A-Za-z0-9._-]", "_") + "_";
            for (File f : files) if (f.getName().startsWith(prefix)) f.delete();
        }
        activeProfile.inputUris = new JSONObject();
        activeProfile.inputMaskPaths = new JSONObject();
        WorkflowStore.upsert(this, activeProfile);
        sessionInputUris.remove(activeProfile.id);
        rebuildInputControls();
        statusText.setText("状态：已清空当前工作流记住的输入图和蒙版");
    }

    private void switchWorkflow(String profileId) {
        if (activeProfile != null && activeProfile.id.equals(profileId)) return;
        captureSessionInputs();
        saveCurrentOverrides();
        saveOutputSelection();
        WorkflowProfile next = WorkflowStore.find(profiles, profileId);
        if (next == null) return;
        activeProfile = next;
        WorkflowStore.setActiveId(this, next.id);
        rebuildWorkflowStrip();
        rebuildDynamicControls();
        statusText.setText("状态：已切换到 " + next.name);
    }

    private void chooseWorkflow() {
        if (workflowImporting) { toast("工作流正在导入，请稍候"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_WORKFLOW);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode >= REQ_MASK_EDITOR_BASE && requestCode < REQ_MASK_EDITOR_BASE + 1000) {
            int idx = requestCode - REQ_MASK_EDITOR_BASE;
            if (idx < 0 || idx >= imageBindings.size()) return;
            String path = data.getStringExtra(MaskEditorActivity.EXTRA_OUTPUT_PATH);
            if (path != null && !path.isEmpty()) onMaskEdited(imageBindings.get(idx), path);
            return;
        }

        if (requestCode >= REQ_MULTI_IMAGE_BASE && requestCode < REQ_MASK_EDITOR_BASE) {
            int idx = requestCode - REQ_MULTI_IMAGE_BASE;
            if (idx < 0 || idx >= imageBindings.size()) return;
            List<Uri> uris = collectUris(data);
            if (uris.isEmpty()) return;
            for (Uri uri : uris) takeReadPermission(uri);
            if (uris.size() == 1) loadInputPreview(imageBindings.get(idx), uris.get(0));
            else confirmBatchGeneration(imageBindings.get(idx), uris);
            return;
        }

        if (requestCode == REQ_WORKFLOW) {
            List<Uri> uris = collectUris(data);
            if (uris.isEmpty()) return;
            for (Uri workflowUri : uris) takeReadPermission(workflowUri);
            showImportFolderChooser(uris);
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;
        takeReadPermission(uri);
        if (requestCode == REQ_BACKGROUND) {
            CustomizationStore.setBackgroundUri(this, uri.toString());
            toast("主界面背景已保存");
            recreate();
            return;
        }
        if (requestCode == REQ_SPLASH) {
            CustomizationStore.setSplashUri(this, uri.toString());
            toast("启动页图片已保存，下次启动生效");
            return;
        }
        if (requestCode >= REQ_IMAGE_BASE && requestCode < REQ_MULTI_IMAGE_BASE) {
            int idx = requestCode - REQ_IMAGE_BASE;
            if (idx >= 0 && idx < imageBindings.size()) loadInputPreview(imageBindings.get(idx), uri);
        }
    }

    private void takeReadPermission(Uri uri) {
        if (uri == null) return;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    }

    private List<Uri> collectUris(Intent data) {
        List<Uri> out = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !out.contains(uri)) out.add(uri);
            }
        }
        Uri single = data.getData();
        if (single != null && !out.contains(single)) out.add(single);
        return out;
    }

    private void showImportFolderChooser(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add("未分类"); ids.add("");
        for (WorkflowFolder f : workflowFolders) { labels.add(f.name); ids.add(f.id); }
        int checked = 0;
        if (!FOLDER_ALL.equals(workflowFolderFilter) && !FOLDER_FAVORITES.equals(workflowFolderFilter)) {
            for (int i = 0; i < ids.size(); i++) if (safe(workflowFolderFilter).equals(ids.get(i))) checked = i;
        }
        final int[] chosen = new int[]{checked};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle((uris.size() > 1 ? "批量导入 " + uris.size() + " 个工作流" : "导入工作流") + "到…")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> chosen[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导入", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String folderId = ids.get(Math.max(0, Math.min(chosen[0], ids.size() - 1)));
            dialog.dismiss();
            startWorkflowImport(uris, folderId);
        }));
        dialog.show();
    }

    private void startWorkflowImport(List<Uri> uris, String folderId) {
        if (workflowImporting) { toast("已经有一批工作流正在导入"); return; }
        workflowImporting = true;
        final List<Uri> batch = new ArrayList<>(uris);
        final String destination = folderId == null ? "" : folderId;
        final String previousActiveId = activeProfile == null ? "" : activeProfile.id;
        final String serverForImport = currentServer();
        workflowState.setText("当前：准备导入 " + batch.size() + " 个工作流…");
        pool.submit(() -> {
            List<WorkflowProfile> latest = WorkflowStore.load(this);
            List<String> failures = new ArrayList<>();
            List<WorkflowProfile> imported = new ArrayList<>();
            JSONObject objectInfo = null;
            try { objectInfo = getObjectInfoCached(serverForImport); } catch (Exception ignored) {}
            final JSONObject detectedObjectInfo = objectInfo;
            for (int i = 0; i < batch.size(); i++) {
                Uri uri = batch.get(i);
                int current = i + 1;
                runOnUiThread(() -> workflowState.setText("当前：正在导入 " + current + " / " + batch.size() + " · " + getDisplayName(uri)));
                try {
                    WorkflowProfile profile = importWorkflowProfile(uri, destination, latest, detectedObjectInfo, serverForImport);
                    imported.add(profile);
                    latest.add(profile);
                } catch (Exception e) {
                    failures.add(getDisplayName(uri) + "：" + safe(e.getMessage()));
                }
            }

            if (previousActiveId.isEmpty() && !imported.isEmpty()) WorkflowStore.setActiveId(this, imported.get(0).id);
            else if (!previousActiveId.isEmpty()) WorkflowStore.setActiveId(this, previousActiveId);

            runOnUiThread(() -> {
                workflowFolders = WorkflowFolderStore.load(this);
                profiles = WorkflowStore.load(this);
                resolveActiveProfile();
                rebuildWorkflowStrip();
                rebuildDynamicControls();
                workflowImporting = false;
                int ok = imported.size();
                int failed = failures.size();
                statusText.setText("状态：工作流导入完成 · 成功 " + ok + " · 失败 " + failed);
                if (failed == 0) {
                    toast((batch.size() > 1 ? "批量" : "") + "导入完成：" + ok + " 个");
                } else {
                    StringBuilder msg = new StringBuilder("成功 ").append(ok).append("，失败 ").append(failed).append("。\n\n");
                    int show = Math.min(12, failures.size());
                    for (int i = 0; i < show; i++) msg.append("• ").append(failures.get(i)).append("\n");
                    if (failures.size() > show) msg.append("…另有 ").append(failures.size() - show).append(" 个失败项");
                    new AlertDialog.Builder(this)
                            .setTitle("导入完成")
                            .setMessage(msg.toString())
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        });
    }

    private WorkflowProfile importWorkflowProfile(Uri uri, String folderId, List<WorkflowProfile> latest, JSONObject objectInfo, String server) throws Exception {
        String raw = readText(uri, 32 * 1024 * 1024);
        JSONObject json = new JSONObject(raw);
        boolean uiFormat = json.optJSONArray("nodes") != null ||
                (json.optJSONObject("workflow") != null && json.optJSONObject("workflow").optJSONArray("nodes") != null);
        JSONObject prompt = uiFormat ? WorkflowUiConverter.toApiPrompt(json, server) : WorkflowUtils.extractPromptObject(json);
        String name = WorkflowStore.uniqueName(latest, getDisplayName(uri), folderId);
        JSONObject uiCopy = uiFormat ? new JSONObject(json.toString()) : null;
        WorkflowProfile profile = WorkflowProfile.create(name, prompt, uiCopy);
        profile.folderId = folderId == null ? "" : folderId;
        if (objectInfo != null) profile.setOutputChoices(WorkflowUtils.findOutputNodes(prompt, objectInfo), true, false);
        else profile.setOutputChoices(WorkflowUtils.findOutputNodes(prompt), false, false);
        WorkflowStore.upsert(this, profile);
        return profile;
    }

    private void manageCurrentWorkflow() {
        if (activeProfile == null) { toast("请先导入一个工作流"); return; }
        saveCurrentOverrides();
        String starAction = activeProfile.favorite ? "取消常用置顶" : "★ 设为常用置顶";
        new AlertDialog.Builder(this)
                .setTitle(activeProfile.name + " · " + folderNameForProfile(activeProfile))
                .setItems(new String[]{"重命名", "移动到文件夹", starAction, "删除这个工作流"}, (dialog, which) -> {
                    if (which == 0) renameCurrentWorkflow();
                    else if (which == 1) showMoveCurrentWorkflowDialog();
                    else if (which == 2) toggleFavorite();
                    else deleteCurrentWorkflow();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showMoveCurrentWorkflowDialog() {
        if (activeProfile == null) return;
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add("未分类"); ids.add("");
        for (WorkflowFolder f : workflowFolders) { labels.add(f.name); ids.add(f.id); }
        String currentFolder = effectiveFolderId(activeProfile);
        int checked = 0;
        for (int i = 0; i < ids.size(); i++) if (currentFolder.equals(ids.get(i))) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("移动工作流到…")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    String target = ids.get(which);
                    dialog.dismiss();
                    if (target.equals(effectiveFolderId(activeProfile))) return;
                    List<WorkflowProfile> latest = WorkflowStore.load(this);
                    String renamed = WorkflowStore.uniqueName(latest, activeProfile.name, target, activeProfile.id);
                    boolean changedName = !renamed.equals(activeProfile.name);
                    activeProfile.name = renamed;
                    activeProfile.folderId = target;
                    WorkflowStore.upsert(this, activeProfile);
                    workflowFolderFilter = target;
                    prefs.edit().putString(PREF_FOLDER_FILTER, workflowFolderFilter).apply();
                    refreshProfiles();
                    if (changedName) toast("已移动；目标文件夹存在同名工作流，自动重命名为 " + renamed);
                    else toast("已移动到 " + folderNameForProfile(activeProfile));
                })
                .setNegativeButton("取消", null)
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
                    String unique = WorkflowStore.uniqueName(WorkflowStore.load(this), name, effectiveFolderId(activeProfile), activeProfile.id);
                    activeProfile.name = unique;
                    WorkflowStore.upsert(this, activeProfile);
                    refreshProfiles();
                    if (!unique.equals(name)) toast("同一文件夹已有同名工作流，已改为 " + unique);
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
                    File maskDir = new File(getFilesDir(), "masks_v22");
                    File[] maskFiles = maskDir.listFiles();
                    if (maskFiles != null) {
                        String prefix = deleting.id.replaceAll("[^A-Za-z0-9._-]", "_") + "_";
                        for (File f : maskFiles) if (f.getName().startsWith(prefix)) f.delete();
                    }
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
        rebuildOutputNodeControls();
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
                String persisted = activeProfile.savedInputUri(key);
                if (persisted != null && !persisted.isEmpty()) {
                    try { b.uri = Uri.parse(persisted); } catch (Exception ignored) {}
                }
                if (b.uri == null && remembered != null) b.uri = remembered.get(key);
                b.maskSupported = n.maskCapable;
                b.maskPath = activeProfile.savedMaskPath(key);
                if (!b.maskPath.isEmpty() && !new File(b.maskPath).isFile()) {
                    b.maskPath = "";
                    activeProfile.setSavedMaskPath(key, "");
                }
                imageBindings.add(b);

                LinearLayout box = miniCard();
                TextView label = text("输入图 " + (i + 1) + " · " + n.title + " · 节点 " + n.id, 13, true);
                box.addView(label);
                b.replaceSwitch = new Switch(this);
                b.replaceSwitch.setText("替换此输入");
                b.replaceSwitch.setChecked(activeProfile.savedReplaceEnabled(key, true));
                box.addView(b.replaceSwitch);
                b.preview = previewImage();
                box.addView(b.preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(178)));

                LinearLayout pickRow = new LinearLayout(this);
                pickRow.setOrientation(LinearLayout.HORIZONTAL);
                b.selectButton = button(b.uri == null ? "选择单张" : "单张：" + getDisplayName(b.uri));
                b.batchButton = button("批量选择");
                pickRow.addView(b.selectButton, weightedButton());
                LinearLayout.LayoutParams batchLp = weightedButton();
                batchLp.setMargins(dp(8), 0, 0, 0);
                pickRow.addView(b.batchButton, batchLp);
                LinearLayout.LayoutParams pickRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                pickRowLp.setMargins(0, dp(6), 0, 0);
                box.addView(pickRow, pickRowLp);

                b.maskRow = new LinearLayout(this);
                b.maskRow.setOrientation(LinearLayout.HORIZONTAL);
                b.maskButton = button("🎨 编辑蒙版遮罩");
                b.clearMaskButton = button("清除蒙版");
                b.maskRow.addView(b.maskButton, weightedButton());
                LinearLayout.LayoutParams maskClearLp = weightedButton();
                maskClearLp.setMargins(dp(8), 0, 0, 0);
                b.maskRow.addView(b.clearMaskButton, maskClearLp);
                LinearLayout.LayoutParams maskRowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                maskRowLp.setMargins(0, dp(6), 0, 0);
                box.addView(b.maskRow, maskRowLp);
                b.maskState = text("", 11, false);
                b.maskState.setPadding(0, dp(4), 0, 0);
                box.addView(b.maskState);
                b.maskButton.setOnClickListener(v -> openMaskEditor(b));
                b.clearMaskButton.setOnClickListener(v -> clearMaskForBinding(b, true));
                updateMaskUi(b);

                b.uploadState = text(b.uri == null ? "等待选择图片" : (b.replaceSwitch.isChecked() ? "准备上传…" : "已记住图片，但当前保留工作流原输入"), 11, false);
                b.uploadState.setTextColor(ThemeManager.muted(this));
                b.uploadState.setPadding(0, dp(5), 0, 0);
                box.addView(b.uploadState);
                b.selectButton.setOnClickListener(v -> chooseInputImage(b));
                b.batchButton.setOnClickListener(v -> chooseInputImages(b));
                b.replaceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (activeProfile != null) {
                        activeProfile.setSavedReplaceEnabled(inputKey(b.choice), isChecked);
                        WorkflowStore.upsert(this, activeProfile);
                    }
                    if (isChecked && b.uri != null && (b.remoteFilename == null || b.remoteFilename.isEmpty()) && !b.uploading) {
                        startInputUpload(b, b.uri);
                    } else if (!isChecked && b.uploadState != null) {
                        b.uploadState.setText(b.uri == null ? "保留工作流原输入" : "已记住图片，但当前保留工作流原输入");
                        b.uploadState.setTextColor(ThemeManager.muted(this));
                    }
                    refreshSubmitEnabled();
                });
                LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                boxLp.setMargins(0, 0, 0, dp(10));
                inputList.addView(box, boxLp);
                if (b.uri != null) {
                    loadPreviewOnly(b, b.uri);
                    if (b.replaceSwitch.isChecked()) startInputUpload(b, b.uri);
                }
            }
            refreshMaskCapabilitiesAsync(activeProfile.id);
        } catch (Exception e) {
            inputList.addView(text("读取工作流输入失败：" + e.getMessage(), 13, false));
        }
        refreshSubmitEnabled();
    }

    private void chooseInputImage(ImageBinding binding) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.setType("image/*");
        startActivityForResult(i, REQ_IMAGE_BASE + binding.index);
    }

    private void chooseInputImages(ImageBinding binding) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_MULTI_IMAGE_BASE + binding.index);
    }

    private void loadInputPreview(ImageBinding binding, Uri uri) {
        // A mask is spatially tied to its source image; never reuse it after image replacement.
        clearMaskForBinding(binding, false);
        binding.uri = uri;
        binding.remoteFilename = "";
        binding.uploadError = "";
        binding.replaceSwitch.setChecked(true);
        binding.selectButton.setText("单张：" + getDisplayName(uri));
        if (activeProfile != null) {
            String key = inputKey(binding.choice);
            sessionInputUris.computeIfAbsent(activeProfile.id, x -> new HashMap<>()).put(key, uri);
            activeProfile.setSavedInputUri(key, uri.toString());
            activeProfile.setSavedReplaceEnabled(key, true);
            WorkflowStore.upsert(this, activeProfile);
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
                ComfyApiClient api = new ComfyApiClient(server);
                File masked = binding.maskPath == null || binding.maskPath.isEmpty() ? null : new File(binding.maskPath);
                ComfyApiClient.UploadResult uploaded = masked != null && masked.isFile()
                        ? api.uploadImageFile(masked) : api.uploadImage(this, uri);
                runOnUiThread(() -> {
                    if (token != binding.uploadToken || binding.uri == null || !binding.uri.equals(uri)) return;
                    binding.uploading = false;
                    binding.remoteFilename = uploaded.workflowFilename();
                    if (binding.uploadState != null) {
                        binding.uploadState.setText(binding.maskPath == null || binding.maskPath.isEmpty() ? "✓ 已上传，可开始生成" : "✓ 原图 + 蒙版已上传，可开始生成");
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
        if (ready && !outputNodeBindings.isEmpty() && countEnabledOutputs() == 0) ready = false;
        submitButton.setEnabled(ready);
    }

    private void captureSessionInputs() {
        if (activeProfile == null || imageBindings.isEmpty()) return;
        Map<String, Uri> map = sessionInputUris.computeIfAbsent(activeProfile.id, x -> new HashMap<>());
        for (ImageBinding b : imageBindings) {
            String key = inputKey(b.choice);
            if (b.uri == null) {
                map.remove(key);
                activeProfile.setSavedInputUri(key, "");
            } else {
                map.put(key, b.uri);
                activeProfile.setSavedInputUri(key, b.uri.toString());
            }
            activeProfile.setSavedReplaceEnabled(key, b.replaceSwitch == null || b.replaceSwitch.isChecked());
            activeProfile.setSavedMaskPath(key, b.maskPath == null ? "" : b.maskPath);
        }
        WorkflowStore.upsert(this, activeProfile);
    }

    private String inputKey(WorkflowUtils.NodeChoice n) { return n.id + "|" + n.inputName; }

    /** Refresh mask capability from ComfyUI /object_info so custom image loaders are supported too. */
    private void refreshMaskCapabilitiesAsync(String profileId) {
        if (profileId == null || profileId.isEmpty() || activeProfile == null) return;
        final JSONObject prompt;
        try { prompt = activeProfile.promptObject(); } catch (Exception e) { return; }
        final String server = currentServer();
        pool.submit(() -> {
            try {
                JSONObject objectInfo = getObjectInfoCached(server);
                List<WorkflowUtils.NodeChoice> detected = WorkflowUtils.findLoadImageNodes(prompt, objectInfo);
                Map<String, Boolean> support = new HashMap<>();
                for (WorkflowUtils.NodeChoice n : detected) support.put(inputKey(n), n.maskCapable);
                runOnUiThread(() -> {
                    if (activeProfile == null || !profileId.equals(activeProfile.id)) return;
                    for (ImageBinding b : imageBindings) {
                        Boolean v = support.get(inputKey(b.choice));
                        if (v != null) b.maskSupported = v;
                        updateMaskUi(b);
                    }
                });
            } catch (Exception ignored) {
                // Offline: keep the conservative class-name detection already shown in the UI.
            }
        });
    }

    private void updateMaskUi(ImageBinding b) {
        if (b == null || b.maskRow == null) return;
        b.maskRow.setVisibility(b.maskSupported ? View.VISIBLE : View.GONE);
        if (b.maskState != null) {
            b.maskState.setVisibility(b.maskSupported ? View.VISIBLE : View.GONE);
            if (b.maskSupported) {
                boolean hasMask = b.maskPath != null && !b.maskPath.isEmpty() && new File(b.maskPath).isFile();
                b.maskState.setText(hasMask ? "✓ 已绘制蒙版：红色涂抹区域会作为 MASK" : "支持蒙版：当前未绘制遮罩");
                b.maskState.setTextColor(hasMask ? ThemeManager.success(this) : ThemeManager.muted(this));
                if (b.maskButton != null) b.maskButton.setText(hasMask ? "🎨 编辑蒙版（已绘制）" : "🎨 编辑蒙版遮罩");
                if (b.clearMaskButton != null) b.clearMaskButton.setEnabled(hasMask);
            }
        }
        if (b.maskButton != null) b.maskButton.setEnabled(b.uri != null);
    }

    private void openMaskEditor(ImageBinding b) {
        if (b == null || !b.maskSupported) return;
        if (b.uri == null) { toast("请先选择输入图片，再编辑蒙版"); return; }
        if (activeProfile == null) return;
        File out = maskFileFor(activeProfile.id, inputKey(b.choice));
        Intent i = new Intent(this, MaskEditorActivity.class);
        i.putExtra(MaskEditorActivity.EXTRA_SOURCE_URI, b.uri.toString());
        i.putExtra(MaskEditorActivity.EXTRA_EXISTING_MASK_PATH, b.maskPath == null ? "" : b.maskPath);
        i.putExtra(MaskEditorActivity.EXTRA_OUTPUT_PATH, out.getAbsolutePath());
        startActivityForResult(i, REQ_MASK_EDITOR_BASE + b.index);
    }

    private File maskFileFor(String profileId, String key) {
        File dir = new File(getFilesDir(), "masks_v22");
        if (!dir.exists()) dir.mkdirs();
        String safeId = profileId == null ? "workflow" : profileId.replaceAll("[^A-Za-z0-9._-]", "_");
        String hash = Integer.toHexString((key == null ? "" : key).hashCode());
        return new File(dir, safeId + "_" + hash + ".png");
    }

    private void onMaskEdited(ImageBinding b, String path) {
        if (b == null || path == null || path.isEmpty() || !new File(path).isFile()) {
            toast("蒙版文件保存失败");
            return;
        }
        b.maskPath = path;
        b.remoteFilename = "";
        b.uploadError = "";
        if (b.replaceSwitch != null) b.replaceSwitch.setChecked(true);
        if (activeProfile != null) {
            activeProfile.setSavedMaskPath(inputKey(b.choice), path);
            WorkflowStore.upsert(this, activeProfile);
        }
        updateMaskUi(b);
        statusText.setText("状态：蒙版已保存，正在把原图 + 蒙版上传到 ComfyUI…");
        if (b.uri != null) startInputUpload(b, b.uri);
    }

    private void clearMaskForBinding(ImageBinding b, boolean reuploadOriginal) {
        if (b == null) return;
        String old = b.maskPath == null ? "" : b.maskPath;
        b.maskPath = "";
        if (!old.isEmpty()) {
            try { new File(old).delete(); } catch (Exception ignored) {}
        }
        if (activeProfile != null) {
            activeProfile.setSavedMaskPath(inputKey(b.choice), "");
            WorkflowStore.upsert(this, activeProfile);
        }
        updateMaskUi(b);
        if (reuploadOriginal && b.uri != null && b.replaceSwitch != null && b.replaceSwitch.isChecked()) {
            b.remoteFilename = "";
            statusText.setText("状态：蒙版已清除，正在重新上传原图…");
            startInputUpload(b, b.uri);
        } else if (reuploadOriginal) {
            statusText.setText("状态：蒙版已清除");
        }
    }

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

    private void rebuildOutputNodeControls() {
        if (outputSelectorList == null) return;
        outputSelectorList.removeAllViews();
        outputNodeBindings.clear();
        if (activeProfile == null) {
            outputSelectorList.addView(text("当前没有工作流", 13, false));
            return;
        }
        List<WorkflowUtils.OutputChoice> choices = activeProfile.outputChoices();
        if (choices.isEmpty()) {
            try {
                choices = WorkflowUtils.findOutputNodes(activeProfile.promptObject());
                if (!choices.isEmpty()) {
                    activeProfile.setOutputChoices(choices, false, false);
                    WorkflowStore.upsert(this, activeProfile);
                }
            } catch (Exception ignored) {}
        }
        if (choices.isEmpty()) {
            TextView empty = text("暂未检测到明确的输出节点。可以点“重新检测输出节点”读取 ComfyUI /object_info。", 13, false);
            empty.setTextColor(ThemeManager.muted(this));
            outputSelectorList.addView(empty);
        } else {
            Set<String> selected = activeProfile.selectedOutputNodeIds();
            if (selected.isEmpty()) {
                for (WorkflowUtils.OutputChoice c : choices) selected.add(c.id);
                activeProfile.setSelectedOutputNodeIds(selected);
                WorkflowStore.upsert(this, activeProfile);
            }
            for (WorkflowUtils.OutputChoice choice : choices) {
                OutputNodeBinding binding = new OutputNodeBinding(choice);
                outputNodeBindings.add(binding);
                LinearLayout box = miniCard();
                Switch sw = new Switch(this);
                sw.setText(choice.label());
                sw.setChecked(selected.contains(choice.id));
                binding.switchView = sw;
                box.addView(sw);
                TextView note = text("关闭后，本次提交会从 API Prompt 中移除此输出节点。", 11, false);
                note.setTextColor(ThemeManager.muted(this));
                box.addView(note);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(8));
                outputSelectorList.addView(box, lp);
                sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (!isChecked && countEnabledOutputs() == 0) {
                        buttonView.setChecked(true);
                        toast("至少保留一个输出节点");
                        return;
                    }
                    saveOutputSelection();
                    refreshSubmitEnabled();
                });
            }
            TextView verified = text(activeProfile.outputNodesVerified ? "✓ 已根据当前 ComfyUI 节点定义确认输出点" : "当前为本地识别结果；App 会尝试连接 ComfyUI 进一步确认。", 11, false);
            verified.setTextColor(activeProfile.outputNodesVerified ? ThemeManager.success(this) : ThemeManager.warning(this));
            outputSelectorList.addView(verified);
        }
        if (!activeProfile.outputNodesVerified) refreshOutputMetadataAsync(false);
    }

    private int countEnabledOutputs() {
        int n = 0;
        for (OutputNodeBinding b : outputNodeBindings) if (b.switchView != null && b.switchView.isChecked()) n++;
        return n;
    }

    private void saveOutputSelection() {
        if (activeProfile == null || outputNodeBindings.isEmpty()) return;
        Set<String> selected = new LinkedHashSet<>();
        for (OutputNodeBinding b : outputNodeBindings) if (b.switchView != null && b.switchView.isChecked()) selected.add(b.choice.id);
        if (selected.isEmpty()) return;
        activeProfile.setSelectedOutputNodeIds(selected);
        WorkflowStore.upsert(this, activeProfile);
    }

    private void refreshOutputMetadataAsync(boolean userInitiated) {
        if (activeProfile == null) { if (userInitiated) toast("请先选择工作流"); return; }
        final String profileId = activeProfile.id;
        if (!outputRefreshInFlight.add(profileId)) { if (userInitiated) toast("正在检测输出节点…"); return; }
        if (userInitiated) statusText.setText("状态：正在读取 ComfyUI 输出节点定义…");
        final String server = currentServer();
        pool.submit(() -> {
            try {
                WorkflowProfile profile = WorkflowStore.find(WorkflowStore.load(this), profileId);
                if (profile == null) return;
                JSONObject objectInfo = getObjectInfoCached(server);
                List<WorkflowUtils.OutputChoice> detectedTmp = WorkflowUtils.findOutputNodes(profile.promptObject(), objectInfo);
                if (detectedTmp.isEmpty()) detectedTmp = WorkflowUtils.findOutputNodes(profile.promptObject());
                final List<WorkflowUtils.OutputChoice> detected = detectedTmp;
                profile.setOutputChoices(detected, true, true);
                WorkflowStore.upsert(this, profile);
                runOnUiThread(() -> {
                    profiles = WorkflowStore.load(this);
                    if (activeProfile != null && profileId.equals(activeProfile.id)) {
                        activeProfile = WorkflowStore.find(profiles, profileId);
                        rebuildOutputNodeControls();
                        refreshSubmitEnabled();
                    }
                    if (userInitiated) {
                        statusText.setText("状态：输出节点检测完成 · " + detected.size() + " 个");
                        toast("检测到 " + detected.size() + " 个输出节点");
                    }
                });
            } catch (Exception e) {
                if (userInitiated) runOnUiThread(() -> showError("输出节点检测失败", e));
            } finally {
                outputRefreshInFlight.remove(profileId);
            }
        });
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
        saveOutputSelection();
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
            JSONObject prompt = new JSONObject(profile.promptJson); // V2.0: reusable compiled prompt; output sinks are filtered per submission
            applyFieldOverrides(prompt, overrides);
            List<WorkflowUtils.OutputChoice> availableOutputs = profile.outputChoices();
            Set<String> selectedOutputs = profile.selectedOutputNodeIds();
            if (!availableOutputs.isEmpty()) {
                if (selectedOutputs.isEmpty()) throw new Exception("至少选择一个输出节点");
                prompt = WorkflowUtils.keepSelectedOutputNodes(prompt, availableOutputs, selectedOutputs);
            }
            for (ImageJob b : imageJobs) {
                if (!b.replace) continue;
                WorkflowUtils.setInputValue(prompt, b.choice.id, b.choice.inputName, b.remoteFilename);
            }

            final String server = currentServer();
            final String previewUri = firstInputUri(imageJobs);
            final JobRecord record = JobRecord.create(profile.id, profile.name, buildInputSummary(imageJobs),
                    previewUri, server, prompt.toString());
            record.selectedOutputNodeIdsJson = new JSONArray(selectedOutputs).toString();
            record.outputSelectionSummary = WorkflowUtils.outputSummary(availableOutputs, selectedOutputs);
            JobStore.add(this, record);
            GenerationManager.notifySubmitted(this, record.localId);
            GenerationManager.enqueueSubmission(this, record.localId);

            // V2.1: keep the chosen input image visible and persisted for this workflow.
            statusText.setText("状态：已加入提交队列 · 当前输入图已保留，可再次生成或更换图片");
            refreshQueueButton();
            refreshSubmitEnabled();
            toast("已加入队列");
        } catch (Exception e) {
            showError("准备任务失败", e);
        }
    }

    private void confirmBatchGeneration(ImageBinding target, List<Uri> uris) {
        if (target == null || uris == null || uris.isEmpty()) return;
        if (activeProfile == null) { toast("请先选择工作流"); return; }
        new AlertDialog.Builder(this)
                .setTitle("批量生成 " + uris.size() + " 张？")
                .setMessage("这些图片将替换“输入图 " + (target.index + 1) + "”，并按安卓文件选择器返回的顺序逐张上传、逐张加入队列。批量目标图不会复用当前蒙版（避免蒙版与不同图片错位）；其他输入图沿用当前设置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("按顺序加入队列", (d, w) -> startBatchGeneration(target, new ArrayList<>(uris)))
                .show();
    }

    private void startBatchGeneration(ImageBinding target, List<Uri> uris) {
        if (activeProfile == null || target == null || uris == null || uris.isEmpty()) return;
        target.replaceSwitch.setChecked(true);
        clearMaskForBinding(target, false);
        saveAddress();
        saveCurrentOverrides();
        saveOutputSelection();
        captureSessionInputs();

        final WorkflowProfile profile = activeProfile;
        final String profileId = profile.id;
        final String targetKey = inputKey(target.choice);
        final int targetIndex = target.index;
        final List<ImageJob> baseJobs = snapshotImageJobs();
        final List<FieldOverride> overrides = snapshotFieldOverrides();
        final List<WorkflowUtils.OutputChoice> availableOutputs = new ArrayList<>(profile.outputChoices());
        final Set<String> selectedOutputs = new LinkedHashSet<>(profile.selectedOutputNodeIds());
        final String server = currentServer();

        for (ImageJob j : baseJobs) {
            if (!j.replace || j.index == targetIndex) continue;
            if (j.uri == null || j.uploading || j.remoteFilename == null || j.remoteFilename.isEmpty()) {
                showError("批量生成前还有输入图未就绪", new Exception("输入图 " + (j.index + 1) + " 仍未上传完成。请先让其他启用的输入图显示“已上传”，再批量选择。"));
                return;
            }
        }
        if (!availableOutputs.isEmpty() && selectedOutputs.isEmpty()) {
            toast("至少选择一个输出节点");
            return;
        }

        statusText.setText("状态：准备批量处理 " + uris.size() + " 张图片…");
        toast("已开始按顺序处理 " + uris.size() + " 张");

        batchPool.submit(() -> {
            int ok = 0;
            int failed = 0;
            Uri lastOkUri = null;
            String lastOkRemote = "";
            ComfyApiClient api = new ComfyApiClient(server);
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                int displayIndex = i + 1;
                runOnUiThread(() -> statusText.setText("状态：批量 " + displayIndex + "/" + uris.size() + " · 正在上传 " + getDisplayName(uri)));
                try {
                    ComfyApiClient.UploadResult uploaded = api.uploadImage(this, uri);
                    List<ImageJob> jobs = new ArrayList<>();
                    for (ImageJob base : baseJobs) {
                        if (base.index == targetIndex) {
                            jobs.add(new ImageJob(base.index, base.choice, uri, true, uploaded.workflowFilename(), false, ""));
                        } else {
                            jobs.add(new ImageJob(base.index, base.choice, base.uri, base.replace,
                                    base.remoteFilename, false, base.uploadError));
                        }
                    }

                    JSONObject prompt = new JSONObject(profile.promptJson);
                    applyFieldOverrides(prompt, overrides);
                    if (!availableOutputs.isEmpty()) {
                        prompt = WorkflowUtils.keepSelectedOutputNodes(prompt, availableOutputs, selectedOutputs);
                    }
                    for (ImageJob job : jobs) {
                        if (!job.replace) continue;
                        WorkflowUtils.setInputValue(prompt, job.choice.id, job.choice.inputName, job.remoteFilename);
                    }

                    JobRecord record = JobRecord.create(profile.id, profile.name, buildInputSummary(jobs),
                            firstInputUri(jobs), server, prompt.toString());
                    record.selectedOutputNodeIdsJson = new JSONArray(selectedOutputs).toString();
                    record.outputSelectionSummary = WorkflowUtils.outputSummary(availableOutputs, selectedOutputs);
                    JobStore.add(this, record);
                    GenerationManager.notifySubmitted(this, record.localId);
                    GenerationManager.enqueueSubmission(this, record.localId);
                    ok++;
                    lastOkUri = uri;
                    lastOkRemote = uploaded.workflowFilename();
                    int currentOk = ok;
                    runOnUiThread(() -> {
                        refreshQueueButton();
                        statusText.setText("状态：批量 " + displayIndex + "/" + uris.size() + " 已加入队列 · 已成功 " + currentOk + " 张");
                    });
                } catch (Exception e) {
                    failed++;
                    int currentFailed = failed;
                    runOnUiThread(() -> statusText.setText("状态：批量 " + displayIndex + "/" + uris.size() + " 上传/入队失败 · 已失败 " + currentFailed + " 张"));
                }
            }

            if (lastOkUri != null) {
                WorkflowProfile latest = WorkflowStore.find(WorkflowStore.load(this), profileId);
                if (latest != null) {
                    latest.setSavedInputUri(targetKey, lastOkUri.toString());
                    latest.setSavedReplaceEnabled(targetKey, true);
                    WorkflowStore.upsert(this, latest);
                }
            }
            final int finalOk = ok;
            final int finalFailed = failed;
            final Uri finalLastUri = lastOkUri;
            final String finalLastRemote = lastOkRemote;
            runOnUiThread(() -> {
                if (finalLastUri != null && activeProfile != null && profileId.equals(activeProfile.id)
                        && targetIndex >= 0 && targetIndex < imageBindings.size()) {
                    ImageBinding current = imageBindings.get(targetIndex);
                    current.uri = finalLastUri;
                    current.remoteFilename = finalLastRemote;
                    current.uploading = false;
                    current.uploadError = "";
                    current.replaceSwitch.setChecked(true);
                    current.selectButton.setText("单张：" + getDisplayName(finalLastUri));
                    current.uploadState.setText("✓ 批量最后一张已上传，可继续单张生成");
                    current.uploadState.setTextColor(ThemeManager.success(this));
                    sessionInputUris.computeIfAbsent(profileId, x -> new HashMap<>()).put(targetKey, finalLastUri);
                    loadPreviewOnly(current, finalLastUri);
                    activeProfile = WorkflowStore.find(WorkflowStore.load(this), profileId);
                }
                refreshQueueButton();
                refreshSubmitEnabled();
                statusText.setText("状态：批量处理完成 · 成功 " + finalOk + " 张" + (finalFailed > 0 ? " · 失败 " + finalFailed + " 张" : ""));
                toast("批量完成：成功 " + finalOk + " 张" + (finalFailed > 0 ? "，失败 " + finalFailed + " 张" : ""));
            });
        });
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
        if (!safe(latest.promptId).equals(lastPreviewedPromptId) || currentOutputRefs.isEmpty()) loadJobOutputs(latest);
    }

    private void loadJobOutputs(JobRecord job) {
        if (job == null) return;
        final String serverForJob = job.server == null || job.server.isEmpty() ? currentServer() : job.server;
        pool.submit(() -> {
            try {
                ComfyApiClient api = new ComfyApiClient(serverForJob);
                List<ImageRef> refs = new ArrayList<>(job.outputRefs());
                boolean legacyOrder = refs.isEmpty() || lacksV23OutputOrder(refs);
                if (legacyOrder && job.promptId != null && !job.promptId.isEmpty()) {
                    List<ImageRef> remote = new ArrayList<>();
                    try {
                        JSONObject history = api.getHistoryForPrompt(job.promptId);
                        remote = api.parseAllImagesForPrompt(history, job.promptId);
                    } catch (Exception ignored) {}
                    if (remote.isEmpty()) {
                        try {
                            JSONObject all = api.getAllHistory(300);
                            remote = api.parseAllImagesForPrompt(all, job.promptId);
                        } catch (Exception ignored) {}
                    }
                    refs = mergeOutputRefs(refs, remote);
                }
                if (refs.isEmpty()) {
                    runOnUiThread(() -> {
                        currentOutputRefs.clear();
                        currentOutputJob = null;
                        outputList.removeAllViews();
                        outputList.addView(text("任务已完成，但没有可读取的图片记录。", 13, false));
                        saveAllButton.setEnabled(false);
                        saveAllButton.setText("全部保存当前输出");
                    });
                    return;
                }

                ImageRef finalRef = ImageRef.chooseFinal(refs);
                if (finalRef == null) return;
                ComfyApiClient.ImageDownload dl = api.fetchImage(finalRef);
                Bitmap bmp = decodeScaled(dl.bytes, 1400, 1400);
                if (bmp == null) return;
                OutputBinding finalOutput = new OutputBinding(finalRef, dl, bmp);
                List<ImageRef> fullRefs = new ArrayList<>(refs);
                runOnUiThread(() -> {
                    lastPreviewedPromptId = safe(job.promptId);
                    renderFinalOutput(job, fullRefs, finalOutput);
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("状态：最终输出加载失败 · " + safe(e.getMessage())));
            }
        });
    }

    private boolean lacksV23OutputOrder(List<ImageRef> refs) {
        if (refs == null || refs.isEmpty()) return true;
        for (ImageRef ref : refs) if (ref != null && (ref.generatedAt > 0 || ref.outputOrder > 0)) return false;
        return true;
    }

    private List<ImageRef> mergeOutputRefs(List<ImageRef> local, List<ImageRef> remote) {
        java.util.LinkedHashMap<String, ImageRef> map = new java.util.LinkedHashMap<>();
        if (local != null) for (ImageRef ref : local) if (ref != null && !ref.filename.isEmpty()) map.put(ref.key(), ref);
        if (remote != null) for (ImageRef ref : remote) {
            if (ref == null || ref.filename.isEmpty()) continue;
            ImageRef old = map.get(ref.key());
            if (old == null || (old.generatedAt <= 0 && old.outputOrder <= 0)) map.put(ref.key(), ref);
        }
        return new ArrayList<>(map.values());
    }

    private void renderFinalOutput(JobRecord job, List<ImageRef> fullRefs, OutputBinding finalOutput) {
        outputBindings.clear();
        outputBindings.add(finalOutput);
        currentOutputRefs.clear();
        currentOutputRefs.addAll(fullRefs);
        currentOutputJob = job;
        outputList.removeAllViews();

        LinearLayout box = miniCard();
        String title = "最终输出 · " + finalOutput.ref.filename;
        if (fullRefs.size() > 1) title += " · 共 " + fullRefs.size() + " 张";
        box.addView(text(title, 12, true));
        FrameLayout imageFrame = new FrameLayout(this);
        ImageView image = previewImage();
        image.setImageBitmap(finalOutput.bitmap);
        imageFrame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(235)));
        if (fullRefs.size() > 1) {
            Button folder = button("📁 " + fullRefs.size());
            FrameLayout.LayoutParams folderLp = new FrameLayout.LayoutParams(dp(92), dp(46));
            folderLp.gravity = Gravity.END | Gravity.BOTTOM;
            folderLp.setMargins(0, 0, dp(8), dp(8));
            imageFrame.addView(folder, folderLp);
            folder.setOnClickListener(v -> openTaskOutputs(job));
        }
        box.addView(imageFrame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(235)));

        Button save = button("保存这张");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        saveLp.setMargins(0, dp(6), 0, 0);
        box.addView(save, saveLp);
        save.setOnClickListener(v -> saveOneOutput(finalOutput));
        outputList.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        saveAllButton.setEnabled(!fullRefs.isEmpty());
        saveAllButton.setText(fullRefs.size() > 1 ? "全部保存当前输出 (" + fullRefs.size() + ")" : "全部保存当前输出");
    }

    private void openTaskOutputs(JobRecord job) {
        if (job == null) return;
        Intent i = new Intent(this, TaskOutputActivity.class);
        i.putExtra(TaskOutputActivity.EXTRA_LOCAL_ID, safe(job.localId));
        i.putExtra(TaskOutputActivity.EXTRA_PROMPT_ID, safe(job.promptId));
        i.putExtra(TaskOutputActivity.EXTRA_SERVER, safe(job.server));
        i.putExtra(TaskOutputActivity.EXTRA_TITLE, job.workflowName == null ? "任务全部图片" : job.workflowName);
        startActivity(i);
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
        if (currentOutputRefs.isEmpty()) { toast("当前没有输出图"); return; }
        final List<ImageRef> refs = new ArrayList<>(currentOutputRefs);
        final String serverForJob = currentOutputJob != null && currentOutputJob.server != null && !currentOutputJob.server.isEmpty()
                ? currentOutputJob.server : currentServer();
        saveAllButton.setEnabled(false);
        statusText.setText("状态：正在保存当前任务全部 " + refs.size() + " 张输出…");
        pool.submit(() -> {
            ComfyApiClient api = new ComfyApiClient(serverForJob);
            int ok = 0;
            for (int i = 0; i < refs.size(); i++) {
                ImageRef ref = refs.get(i);
                try {
                    ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                    MediaSaver.saveImage(this, dl.bytes, ref.filename, dl.mime);
                    ok++;
                } catch (Exception ignored) {}
                int done = i + 1;
                int good = ok;
                runOnUiThread(() -> statusText.setText("状态：全部保存中 " + done + " / " + refs.size() + " · 成功 " + good));
            }
            int finalOk = ok;
            runOnUiThread(() -> {
                saveAllButton.setEnabled(true);
                statusText.setText("状态：全部保存完成 · " + finalOk + " / " + refs.size());
                toast("已保存 " + finalOk + " 张到 Pictures/ComfyRemote");
            });
        });
    }

    private void refreshQueueButton() {
        if (queueButton == null) return;
        int active = 0;
        for (JobRecord job : JobStore.list(this)) if (!job.isTerminal()) active++;
        queueButton.setText(active > 0 ? "队列 (" + active + ")" : "队列");
    }

    private JSONObject getObjectInfoCached(String server) throws Exception {
        String normalized = ComfyApiClient.normalizeBase(server);
        synchronized (objectInfoLock) {
            long now = System.currentTimeMillis();
            if (cachedObjectInfo != null && normalized.equals(cachedObjectInfoServer) &&
                    now - cachedObjectInfoAt < 5 * 60 * 1000L) {
                return cachedObjectInfo;
            }
            JSONObject fetched = new ComfyApiClient(server).getObjectInfo();
            cachedObjectInfo = fetched;
            cachedObjectInfoServer = normalized;
            cachedObjectInfoAt = now;
            return fetched;
        }
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
        String[] items = new String[]{
                "外观模式（跟随系统 / 浅色 / 深色）",
                "选择主界面背景图片",
                "清除主界面背景",
                "选择启动页图片",
                "清除启动页图片",
                "App 图标样式"
        };
        new AlertDialog.Builder(this)
                .setTitle("外观与个性化")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showThemeModeDialog();
                    else if (which == 1) chooseCustomizationImage(REQ_BACKGROUND);
                    else if (which == 2) {
                        CustomizationStore.setBackgroundUri(this, "");
                        toast("已恢复默认背景");
                        recreate();
                    } else if (which == 3) chooseCustomizationImage(REQ_SPLASH);
                    else if (which == 4) {
                        CustomizationStore.setSplashUri(this, "");
                        toast("已恢复默认启动页");
                    } else showIconMenu();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showThemeModeDialog() {
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

    private void chooseCustomizationImage(int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.setType("image/*");
        startActivityForResult(i, requestCode);
    }

    private void showIconMenu() {
        String current = IconSwitcher.getChoice(this);
        String[] values = new String[]{IconSwitcher.DEFAULT, IconSwitcher.MINIMAL, IconSwitcher.BLUE};
        String[] labels = new String[]{"霓虹节点沙发（默认）", "极简节点", "蓝色节点"};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("App 图标样式")
                .setMessage("Android 不允许已安装 App 把任意相册图片直接变成桌面 Launcher 图标，因此 V2.4.1 继续提供 3 个预置图标即时切换。主界面背景和启动页仍可使用任意图片。")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    IconSwitcher.apply(this, values[which]);
                    dialog.dismiss();
                    toast("图标已切换；部分桌面可能需要几秒刷新");
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
        Button batchButton;
        LinearLayout maskRow;
        Button maskButton;
        Button clearMaskButton;
        TextView maskState;
        TextView uploadState;
        boolean maskSupported = false;
        String maskPath = "";
        String remoteFilename = "";
        String uploadError = "";
        boolean uploading = false;
        long uploadToken = 0;
        ImageBinding(int index, WorkflowUtils.NodeChoice choice) { this.index = index; this.choice = choice; }
    }

    private static final class OutputNodeBinding {
        final WorkflowUtils.OutputChoice choice;
        Switch switchView;
        OutputNodeBinding(WorkflowUtils.OutputChoice choice) { this.choice = choice; }
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
