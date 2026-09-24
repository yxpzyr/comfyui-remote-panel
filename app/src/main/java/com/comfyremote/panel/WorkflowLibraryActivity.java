package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V2.4.1 file-manager style workflow library.
 * Root shows real app folders; entering a folder shows only workflows in it.
 */
public class WorkflowLibraryActivity extends Activity {
    public static final String EXTRA_AUTO_ACTION = "auto_action";
    public static final String ACTION_IMPORT_FOLDER = "import_folder";

    private static final int REQ_FILES = 4101;
    private static final int REQ_TREE = 4102;
    private static final String FAVORITES = "__favorites__";

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView title, subtitle, status;
    private Button backButton, newFolderButton, importFilesButton, importFolderButton, folderMenuButton;

    private List<WorkflowFolder> folders = new ArrayList<>();
    private List<WorkflowProfile> profiles = new ArrayList<>();
    // null = root, "" = uncategorized, FAVORITES = virtual favorites, otherwise folder id.
    private String currentFolderId = null;
    private String pendingFileDestination = null;
    private boolean importing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);
        buildUi();
        reload();
        render();

        String auto = getIntent().getStringExtra(EXTRA_AUTO_ACTION);
        if (ACTION_IMPORT_FOLDER.equals(auto)) {
            list.post(this::chooseWholeFolder);
        }
    }

    @Override protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (currentFolderId != null) {
            currentFolderId = null;
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(18));
        root.setBackgroundColor(ThemeManager.background(this));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        backButton = button("关闭");
        title = text("工作流库", 23, true);
        top.addView(backButton, new LinearLayout.LayoutParams(dp(86), dp(46)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMargins(dp(8), 0, 0, 0);
        top.addView(title, titleLp);
        root.addView(top);
        backButton.setOnClickListener(v -> onBackPressed());

        subtitle = text("按文件夹管理不同功能的工作流", 12, false);
        subtitle.setTextColor(ThemeManager.secondary(this));
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        LinearLayout actions1 = new LinearLayout(this);
        actions1.setOrientation(LinearLayout.HORIZONTAL);
        newFolderButton = button("＋ 新建文件夹");
        importFilesButton = button("导入工作流文件");
        actions1.addView(newFolderButton, weighted());
        LinearLayout.LayoutParams impLp = weighted(); impLp.setMargins(dp(8), 0, 0, 0);
        actions1.addView(importFilesButton, impLp);
        root.addView(actions1);

        LinearLayout actions2 = new LinearLayout(this);
        actions2.setOrientation(LinearLayout.HORIZONTAL);
        importFolderButton = button("📂 导入整个文件夹");
        folderMenuButton = button("文件夹设置");
        actions2.addView(importFolderButton, weighted());
        LinearLayout.LayoutParams menuLp = weighted(); menuLp.setMargins(dp(8), 0, 0, 0);
        actions2.addView(folderMenuButton, menuLp);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        a2.setMargins(0, dp(8), 0, 0);
        root.addView(actions2, a2);

        status = text("", 12, false);
        status.setTextColor(ThemeManager.muted(this));
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        newFolderButton.setOnClickListener(v -> createFolderDialog());
        importFilesButton.setOnClickListener(v -> chooseWorkflowFiles());
        importFolderButton.setOnClickListener(v -> chooseWholeFolder());
        folderMenuButton.setOnClickListener(v -> currentFolderSettings());
        setContentView(root);
    }

    private void reload() {
        folders = WorkflowFolderStore.load(this);
        profiles = WorkflowStore.load(this);
    }

    private void render() {
        reload();
        list.removeAllViews();
        boolean atRoot = currentFolderId == null;
        backButton.setText(atRoot ? "关闭" : "← 返回");
        newFolderButton.setVisibility(atRoot ? View.VISIBLE : View.GONE);
        importFolderButton.setVisibility(atRoot ? View.VISIBLE : View.GONE);
        folderMenuButton.setVisibility(isRealFolder(currentFolderId) ? View.VISIBLE : View.GONE);

        if (atRoot) {
            title.setText("工作流库");
            subtitle.setText("先进入文件夹，再选择工作流；也可以直接导入整个手机文件夹。共 " + profiles.size() + " 个工作流");
            importFilesButton.setText("导入工作流文件");
            addVirtualFolder("★ 常用", FAVORITES, countFavorites(), "所有文件夹里标记为常用的工作流");
            addVirtualFolder("未分类", "", countInFolder(""), "V2.3 / 旧版工作流默认在这里");
            for (WorkflowFolder folder : folders) addRealFolder(folder);
            if (folders.isEmpty()) {
                TextView hint = text("还没有自定义文件夹。点上方“＋ 新建文件夹”，例如建立：动漫转真人 / 换脸 / 放大修复 / 测试。", 13, false);
                hint.setTextColor(ThemeManager.muted(this));
                hint.setPadding(dp(8), dp(14), dp(8), dp(14));
                list.addView(hint);
            }
        } else {
            String name = currentFolderName();
            title.setText(name);
            subtitle.setText(FAVORITES.equals(currentFolderId)
                    ? "这里集中显示全部常用工作流；常用不是实际文件夹。"
                    : "当前文件夹内的工作流。点工作流即可切换回主页。" );
            importFilesButton.setText(FAVORITES.equals(currentFolderId) ? "导入工作流文件" : "＋ 导入到当前文件夹");
            List<WorkflowProfile> visible = profilesInCurrentFolder();
            WorkflowStore.sort(visible);
            if (visible.isEmpty()) {
                TextView empty = text("这个文件夹还是空的。", 14, false);
                empty.setTextColor(ThemeManager.muted(this));
                empty.setPadding(dp(8), dp(22), dp(8), dp(22));
                list.addView(empty);
            } else {
                for (WorkflowProfile p : visible) addWorkflowRow(p);
            }
        }
        status.setText(importing ? "正在导入，请不要关闭页面…" : "");
    }

    private void addVirtualFolder(String name, String id, int count, String description) {
        LinearLayout row = folderCard();
        TextView t = text("📁 " + name + "   " + count + " 个", 16, true);
        TextView sub = text(description, 11, false);
        sub.setTextColor(ThemeManager.muted(this));
        row.addView(t);
        row.addView(sub);
        row.setOnClickListener(v -> { currentFolderId = id; render(); });
        list.addView(row, cardLp());
    }

    private void addRealFolder(WorkflowFolder folder) {
        LinearLayout row = folderCard();
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text("📁 " + folder.name + "   " + countInFolder(folder.id) + " 个", 16, true);
        Button more = button("⋮");
        line.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(more, new LinearLayout.LayoutParams(dp(56), dp(42)));
        row.addView(line);
        row.setOnClickListener(v -> { currentFolderId = folder.id; render(); });
        more.setOnClickListener(v -> manageFolder(folder));
        list.addView(row, cardLp());
    }

    private void addWorkflowRow(WorkflowProfile profile) {
        LinearLayout row = folderCard();
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String prefix = profile.favorite ? "★ " : "";
        TextView name = text(prefix + profile.name, 15, true);
        Button more = button("⋮");
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(more, new LinearLayout.LayoutParams(dp(56), dp(42)));
        row.addView(line);
        TextView meta = text("点此使用 · " + folderNameForProfile(profile), 11, false);
        meta.setTextColor(ThemeManager.muted(this));
        row.addView(meta);
        row.setOnClickListener(v -> selectWorkflow(profile));
        more.setOnClickListener(v -> manageWorkflow(profile));
        list.addView(row, cardLp());
    }

    private void selectWorkflow(WorkflowProfile p) {
        WorkflowStore.setActiveId(this, p.id);
        Toast.makeText(this, "已切换到：" + p.name, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void chooseWorkflowFiles() {
        if (importing) { toast("正在导入，请稍候"); return; }
        if (FAVORITES.equals(currentFolderId)) {
            showDestinationThenPickFiles();
            return;
        }
        if (currentFolderId == null) {
            showDestinationThenPickFiles();
            return;
        }
        pendingFileDestination = currentFolderId;
        launchFilePicker();
    }

    private void showDestinationThenPickFiles() {
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add("未分类"); ids.add("");
        for (WorkflowFolder f : folders) { labels.add(f.name); ids.add(f.id); }
        labels.add("＋ 新建文件夹…"); ids.add("__new__");
        new AlertDialog.Builder(this)
                .setTitle("导入到哪个文件夹？")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String id = ids.get(which);
                    if ("__new__".equals(id)) createFolderThenImportFiles();
                    else { pendingFileDestination = id; launchFilePicker(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createFolderThenImportFiles() {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint("例如：动漫转真人");
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹并导入")
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) { toast("文件夹名称不能为空"); return; }
                    WorkflowFolder f = WorkflowFolderStore.create(this, name);
                    pendingFileDestination = f.id;
                    launchFilePicker();
                }).show();
    }

    private void launchFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FILES);
    }

    private void chooseWholeFolder() {
        if (importing) { toast("正在导入，请稍候"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_FILES) {
            List<Uri> uris = collectUris(data);
            if (uris.isEmpty()) return;
            for (Uri u : uris) takeReadPermission(u, data.getFlags());
            String destination = pendingFileDestination == null ? "" : pendingFileDestination;
            startImportFiles(uris, destination, null);
            pendingFileDestination = null;
        } else if (requestCode == REQ_TREE) {
            Uri tree = data.getData();
            if (tree == null) return;
            takeReadPermission(tree, data.getFlags());
            startImportTree(tree);
        }
    }

    private void startImportTree(Uri treeUri) {
        importing = true;
        render();
        status.setText("正在扫描所选文件夹…");
        final String server = currentServer();
        pool.submit(() -> {
            try {
                List<Uri> jsonFiles = WorkflowImporter.scanJsonFiles(this, treeUri);
                if (jsonFiles.isEmpty()) throw new Exception("所选文件夹里没有找到 JSON 工作流");
                String sourceName = WorkflowImporter.treeDisplayName(this, treeUri);
                WorkflowFolder folder = WorkflowFolderStore.create(this, sourceName);
                runOnUiThread(() -> status.setText("已找到 " + jsonFiles.size() + " 个 JSON，正在导入到 “" + folder.name + "”…"));
                ImportResult result = importBatch(jsonFiles, folder.id, server);
                if (result.success == 0) WorkflowFolderStore.delete(this, folder.id);
                runOnUiThread(() -> finishImport(result,
                        result.success > 0 ? "已从文件夹导入到：" + folder.name : "文件夹导入失败"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    importing = false;
                    render();
                    showError("文件夹导入失败", e);
                });
            }
        });
    }

    private void startImportFiles(List<Uri> uris, String folderId, String successPrefix) {
        importing = true;
        render();
        final String server = currentServer();
        pool.submit(() -> {
            ImportResult result = importBatch(uris, folderId == null ? "" : folderId, server);
            runOnUiThread(() -> finishImport(result, successPrefix));
        });
    }

    private ImportResult importBatch(List<Uri> uris, String folderId, String server) {
        List<WorkflowProfile> latest = WorkflowStore.load(this);
        List<String> failures = new ArrayList<>();
        int success = 0;
        JSONObject objectInfo = null;
        try { objectInfo = new ComfyApiClient(server).getObjectInfo(); } catch (Exception ignored) {}
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            int done = i + 1;
            runOnUiThread(() -> status.setText("正在导入 " + done + " / " + uris.size() + " · " + WorkflowImporter.displayName(this, uri)));
            try {
                WorkflowProfile profile = WorkflowImporter.importProfile(this, uri, folderId, latest, objectInfo, server);
                latest.add(profile);
                success++;
            } catch (Exception e) {
                failures.add(WorkflowImporter.displayName(this, uri) + "：" + safe(e.getMessage()));
            }
        }
        return new ImportResult(success, failures);
    }

    private void finishImport(ImportResult result, String successPrefix) {
        importing = false;
        reload();
        render();
        String prefix = successPrefix == null ? "导入完成" : successPrefix;
        status.setText(prefix + " · 成功 " + result.success + " · 失败 " + result.failures.size());
        if (result.failures.isEmpty()) {
            toast(prefix + "，共 " + result.success + " 个");
            return;
        }
        StringBuilder msg = new StringBuilder("成功 ").append(result.success)
                .append("，失败 ").append(result.failures.size()).append("。\n\n");
        int show = Math.min(12, result.failures.size());
        for (int i = 0; i < show; i++) msg.append("• ").append(result.failures.get(i)).append("\n");
        if (result.failures.size() > show) msg.append("…另有 ").append(result.failures.size() - show).append(" 个失败项");
        new AlertDialog.Builder(this).setTitle("导入完成").setMessage(msg.toString()).setPositiveButton("知道了", null).show();
    }

    private void createFolderDialog() {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint("例如：动漫转真人");
        new AlertDialog.Builder(this)
                .setTitle("新建工作流文件夹")
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) { toast("文件夹名称不能为空"); return; }
                    WorkflowFolder folder = WorkflowFolderStore.create(this, name);
                    currentFolderId = folder.id;
                    render();
                    toast("已创建文件夹：" + folder.name);
                }).show();
    }

    private void manageFolder(WorkflowFolder folder) {
        new AlertDialog.Builder(this)
                .setTitle(folder.name)
                .setItems(new String[]{"打开", "重命名", "删除文件夹"}, (d, which) -> {
                    if (which == 0) { currentFolderId = folder.id; render(); }
                    else if (which == 1) renameFolder(folder);
                    else deleteFolder(folder);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void currentFolderSettings() {
        if (!isRealFolder(currentFolderId)) return;
        WorkflowFolder f = WorkflowFolderStore.find(folders, currentFolderId);
        if (f != null) manageFolder(f);
    }

    private void renameFolder(WorkflowFolder folder) {
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
                    render();
                }).show();
    }

    private void deleteFolder(WorkflowFolder folder) {
        int count = countInFolder(folder.id);
        new AlertDialog.Builder(this)
                .setTitle("删除文件夹？")
                .setMessage("不会删除工作流。里面的 " + count + " 个工作流会自动移动到“未分类”。")
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
                    if (folder.id.equals(currentFolderId)) currentFolderId = null;
                    render();
                    toast("文件夹已删除，工作流已移到未分类");
                }).show();
    }

    private void manageWorkflow(WorkflowProfile p) {
        String star = p.favorite ? "取消常用" : "★ 设为常用";
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(new String[]{"使用这个工作流", "重命名", "移动到文件夹", star, "删除工作流"}, (d, which) -> {
                    if (which == 0) selectWorkflow(p);
                    else if (which == 1) renameWorkflow(p);
                    else if (which == 2) moveWorkflow(p);
                    else if (which == 3) { p.favorite = !p.favorite; WorkflowStore.upsert(this, p); render(); }
                    else deleteWorkflow(p);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameWorkflow(WorkflowProfile p) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(p.name);
        edit.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("重命名工作流")
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String desired = edit.getText().toString().trim();
                    if (desired.isEmpty()) { toast("名称不能为空"); return; }
                    String folderId = effectiveFolderId(p);
                    String unique = WorkflowStore.uniqueName(WorkflowStore.load(this), desired, folderId, p.id);
                    p.name = unique;
                    WorkflowStore.upsert(this, p);
                    render();
                    if (!unique.equals(desired)) toast("同一文件夹存在同名工作流，已自动改名");
                }).show();
    }

    private void moveWorkflow(WorkflowProfile p) {
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add("未分类"); ids.add("");
        for (WorkflowFolder f : folders) { labels.add(f.name); ids.add(f.id); }
        new AlertDialog.Builder(this)
                .setTitle("移动到文件夹")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String target = ids.get(which);
                    List<WorkflowProfile> latest = WorkflowStore.load(this);
                    p.name = WorkflowStore.uniqueName(latest, p.name, target, p.id);
                    p.folderId = target;
                    WorkflowStore.upsert(this, p);
                    render();
                    toast("已移动到 " + (target.isEmpty() ? "未分类" : folderName(target)));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteWorkflow(WorkflowProfile p) {
        new AlertDialog.Builder(this)
                .setTitle("删除工作流？")
                .setMessage(p.name + "\n\n只删除手机 App 中保存的工作流，不影响电脑上的 ComfyUI。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> { WorkflowStore.delete(this, p.id); render(); })
                .show();
    }

    private List<WorkflowProfile> profilesInCurrentFolder() {
        List<WorkflowProfile> out = new ArrayList<>();
        for (WorkflowProfile p : profiles) {
            if (FAVORITES.equals(currentFolderId)) {
                if (p.favorite) out.add(p);
            } else if (safe(currentFolderId).equals(effectiveFolderId(p))) out.add(p);
        }
        return out;
    }

    private int countFavorites() { int n = 0; for (WorkflowProfile p : profiles) if (p.favorite) n++; return n; }
    private int countInFolder(String id) { int n = 0; for (WorkflowProfile p : profiles) if (safe(id).equals(effectiveFolderId(p))) n++; return n; }

    private String effectiveFolderId(WorkflowProfile p) {
        if (p == null || p.folderId == null || p.folderId.isEmpty()) return "";
        return WorkflowFolderStore.find(folders, p.folderId) == null ? "" : p.folderId;
    }

    private String folderNameForProfile(WorkflowProfile p) {
        String id = effectiveFolderId(p);
        return id.isEmpty() ? "未分类" : folderName(id);
    }

    private String folderName(String id) {
        WorkflowFolder f = WorkflowFolderStore.find(folders, id);
        return f == null ? "未分类" : f.name;
    }

    private String currentFolderName() {
        if (FAVORITES.equals(currentFolderId)) return "★ 常用";
        if (currentFolderId == null || currentFolderId.isEmpty()) return currentFolderId == null ? "工作流库" : "未分类";
        return folderName(currentFolderId);
    }

    private boolean isRealFolder(String id) {
        return id != null && !id.isEmpty() && !FAVORITES.equals(id) && WorkflowFolderStore.find(folders, id) != null;
    }

    private List<Uri> collectUris(Intent data) {
        List<Uri> out = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !out.contains(u)) out.add(u);
            }
        }
        Uri single = data.getData();
        if (single != null && !out.contains(single)) out.add(single);
        return out;
    }

    private void takeReadPermission(Uri uri, int flags) {
        try {
            int take = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, take == 0 ? Intent.FLAG_GRANT_READ_URI_PERMISSION : take);
        } catch (Exception ignored) {}
    }

    private String currentServer() {
        String s = getSharedPreferences("comfy_remote", MODE_PRIVATE).getString("server", "8188");
        return s == null || s.trim().isEmpty() ? "8188" : s.trim();
    }

    private LinearLayout folderCard() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(13), dp(12), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ThemeManager.card(this));
        bg.setCornerRadius(dp(15));
        l.setBackground(bg);
        return l;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(ThemeManager.text(this));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, dp(46), 1f); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static String safe(String s) { return s == null ? "" : s; }

    private void showError(String title, Exception e) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(safe(e.getMessage()).isEmpty() ? e.toString() : e.getMessage())
                .setPositiveButton("知道了", null).show();
    }

    private static final class ImportResult {
        final int success;
        final List<String> failures;
        ImportResult(int success, List<String> failures) { this.success = success; this.failures = failures; }
    }
}
