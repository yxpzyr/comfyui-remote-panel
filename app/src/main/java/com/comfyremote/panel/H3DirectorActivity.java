package com.comfyremote.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native shot cards; the graph and uploads are frozen before background preparation. */
public final class H3DirectorActivity extends Activity {
    private static final int PICK_IMAGE = 301;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private WorkflowProfile profile;
    private JSONObject original, draft;
    private String server, stateKey, pendingShot, pendingRole;
    private int pendingIndex;
    private LinearLayout root;
    private ScrollView contentScroll;
    private TextView status, estimate;
    private Button generate;
    private volatile boolean preparing;
    private final GenerationManager.JobListener jobListener = job -> runOnUiThread(() -> {
        if (isDestroyed() || profile == null || status == null || job == null || !profile.id.equals(job.workflowId)) return;
        status.setText(job.message + (job.progressPercent > 0 ? " · " + job.progressPercent + "%" : ""));
    });

    @Override protected void onStart() { super.onStart(); GenerationManager.addListener(jobListener); }
    @Override protected void onStop() { GenerationManager.removeListener(jobListener); super.onStop(); }

    @Override public void onCreate(Bundle state) {
        ThemeManager.prepare(this); super.onCreate(state); ThemeManager.applyWindow(this);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            profile = WorkflowStore.loadById(this, getIntent().getStringExtra("workflow_id"));
            if (profile == null) throw new Exception("工作流不存在，请重新导入手机导演台模板");
            original = profile.submissionPrompt(getIntent().getStringExtra("server"));
            H3DirectorPlan.directorId(original);
            server = getIntent().getStringExtra("server");
            if (server == null || server.isEmpty()) server = "8188";
            stateKey = profile.id + ":" + profile.activeVariantId;
            String saved = getSharedPreferences("h3_director_drafts_v1", MODE_PRIVATE).getString(stateKey, "");
            draft = saved.isEmpty() ? H3DirectorPlan.fromPrompt(original, stateKey) : new JSONObject(saved);
            if (state != null) {
                pendingShot = state.getString("pending_shot"); pendingRole = state.getString("pending_role");
                pendingIndex = state.getInt("pending_index");
            }
            render();
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("无法打开导演台").setMessage(error.getMessage())
                    .setPositiveButton("返回", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
        }
    }

    @Override protected void onPause() { saveDraft(); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending_shot", pendingShot); state.putString("pending_role", pendingRole);
        state.putInt("pending_index", pendingIndex); saveDraft(); super.onSaveInstanceState(state);
    }

    private void saveDraft() {
        if (draft != null && stateKey != null)
            getSharedPreferences("h3_director_drafts_v1", MODE_PRIVATE).edit().putString(stateKey, draft.toString()).apply();
    }

    private void render() throws Exception {
        int previousScroll = contentScroll == null ? 0 : contentScroll.getScrollY();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setFitsSystemWindows(true);
        contentScroll = scroll;
        scroll.setBackgroundColor(ThemeManager.background(this));
        root = column(); root.setFocusableInTouchMode(true); root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root); setContentView(scroll); root.requestFocus();
        root.addView(text("H3 导演台", 25, true));
        root.addView(text(profile.name + " · 分镜设置自动保留", 13, false));
        LinearLayout actions = row();
        Button back = button("返回"), save = button("保存分镜"), reset = button("重置分镜");
        actions.addView(back, weight()); actions.addView(save, weight()); actions.addView(reset, weight()); root.addView(actions);
        back.setOnClickListener(v -> finish()); save.setOnClickListener(v -> { saveDraft(); toast("分镜已保存"); });
        reset.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("重新载入原分镜？")
                .setMessage("将替换本页草稿，原工作流和历史任务不变。")
                .setNegativeButton("取消", null).setPositiveButton("载入", (d,w) -> {
                    try { draft = H3DirectorPlan.fromPrompt(original, stateKey); saveDraft(); render(); }
                    catch (Exception error) { error(error); }
                }).show());

        LinearLayout settings = card("作品设置");
        Spinner modes = new Spinner(this);
        modes.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"文生视频", "首帧 / 首尾帧视频", "参考主体（图片）"}));
        String task = draft.getString("mode");
        modes.setSelection("r2v".equals(task) ? 2 : "fl2v".equals(task) ? 1 : 0);
        settings.addView(modes);
        modes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = new String[]{"t2v", "fl2v", "r2v"}[position];
                if (selected.equals(draft.optString("mode"))) return;
                try { draft.put("mode", selected).put("steps", "r2v".equals(selected) ? 4 : 8); saveDraft(); render(); }
                catch (Exception error) { error(error); }
            }
        });
        settings.addView(text("生成时按模式匹配电脑上已有的 FL2VA / Ref2VA 与加速 LoRA；计算由电脑完成。", 12, false));
        settings.addView(text("公共提示词 · 外观、场景与声音", 15, true));
        settings.addView(edit(draft, "common_prompt", "各段共享的描述，可以留空", true, false));
        settings.addView(text("宽 / 高 / 采样步数", 13, false));
        LinearLayout dimensions = row();
        dimensions.addView(number(draft, "width", "宽"), weight());
        dimensions.addView(number(draft, "height", "高"), weight());
        dimensions.addView(number(draft, "steps", "步数"), weight()); settings.addView(dimensions);
        settings.addView(text("种子（-1 为每次随机）", 13, false));
        settings.addView(number(draft, "seed", "种子"));
        CheckBox continuity = new CheckBox(this); continuity.setText("段间引导 · 22帧画面与音频上下文");
        continuity.setTextColor(ThemeManager.text(this)); continuity.setChecked(draft.optBoolean("continuity", true));
        continuity.setOnCheckedChangeListener((b, value) -> put(draft, "continuity", value)); settings.addView(continuity);
        settings.addView(text("建议每段5–10秒。首次完整生成全部分镜，自动分段保存后合成；长片实际时长会受帧对齐裁剪影响。", 12, false));
        if ("r2v".equals(task)) {
            settings.addView(text("公共参考图 · 所有分镜共享", 16, true));
            settings.addView(text("提示词可用 <Picture 1>、<Picture 2> 引用对应图片。", 12, false));
            images(settings, null, draft);
        }
        root.addView(settings);

        estimate = text("", 15, true); root.addView(estimate);
        JSONArray shots = draft.getJSONArray("shots");
        for (int i = 0; i < shots.length(); i++) {
            final int index = i; JSONObject shot = shots.getJSONObject(i); String shotId = shot.getString("id");
            LinearLayout card = card("分镜 " + (i + 1)); card.setTag("h3-shot-" + shotId);
            LinearLayout tools = row();
            Button up = button("上移"), down = button("下移"), remove = button("删除");
            up.setEnabled(i > 0); down.setEnabled(i < shots.length() - 1); remove.setEnabled(shots.length() > 1);
            tools.addView(up); tools.addView(down); tools.addView(remove); card.addView(tools);
            up.setOnClickListener(v -> move(index, index - 1)); down.setOnClickListener(v -> move(index, index + 1));
            remove.setOnClickListener(v -> { shots.remove(index); rebuild(); });
            card.addView(text("本段时长（秒）", 13, false));
            EditText seconds = number(shot, "seconds", "本段时长（秒）"); seconds.setTag("h3-duration-" + i); card.addView(seconds);
            EditText prompt = edit(shot, "prompt", "本段动作与镜头", true, false); prompt.setTag("h3-prompt-" + i); card.addView(prompt);
            if (i > 0) {
                CheckBox fromPrevious = new CheckBox(this); fromPrevious.setText("引用上段 · 延续上一段运动");
                fromPrevious.setTextColor(ThemeManager.text(this)); fromPrevious.setChecked(shot.optBoolean("continuity", true));
                fromPrevious.setOnCheckedChangeListener((b,value) -> put(shot, "continuity", value)); card.addView(fromPrevious);
            }
            if ("fl2v".equals(task)) {
                media(card, shotId, "first", 0, "首帧", H3DirectorPlan.imageSlot(draft, shotId, "first", 0));
                media(card, shotId, "last", 0, "尾帧（可选）", H3DirectorPlan.imageSlot(draft, shotId, "last", 0));
                if (i > 0) card.addView(text("连续生成时可留空首尾帧，由上一段引导。", 12, false));
            }
            if ("r2v".equals(task)) {
                Button show = button("本段参考图（可选覆盖公共图）"); card.addView(show);
                LinearLayout perShot = column(); perShot.setVisibility(shot.optBoolean("show_images", false) ? View.VISIBLE : View.GONE); card.addView(perShot);
                images(perShot, shotId, shot);
                show.setOnClickListener(v -> {
                    boolean visible = perShot.getVisibility() != View.VISIBLE;
                    put(shot, "show_images", visible); perShot.setVisibility(visible ? View.VISIBLE : View.GONE);
                });
            }
            root.addView(card);
        }
        Button add = button("＋ 添加分镜"); add.setTag("h3-add-shot"); root.addView(add);
        add.setOnClickListener(v -> {
            try {
                JSONObject shot = H3DirectorPlan.newShot();
                if (shots.length() > 0) shot.put("prompt", shots.getJSONObject(shots.length() - 1).optString("prompt"));
                shots.put(shot); rebuild();
            } catch (Exception error) { error(error); }
        });
        root.addView(text("本版支持图片参考和整片生成。参考视频、参考音频、外接二采与语义桥请在电脑端处理。", 12, false));
        status = text("准备就绪 · 完成后在队列 / 图库查看完整视频", 14, false); root.addView(status);
        generate = button(preparing ? "正在准备提交…" : "▶ 生成整部视频"); generate.setTag("h3-generate");
        generate.setEnabled(!preparing); root.addView(generate);
        generate.setOnClickListener(v -> submit());
        Button queue = button("查看队列 / 当前分镜进度"); root.addView(queue);
        queue.setOnClickListener(v -> startActivity(new Intent(this, QueueActivity.class)));
        Button gallery = button("打开图库 / 查看成片"); root.addView(gallery);
        gallery.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        updateEstimate();
        scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    private void images(LinearLayout parent, String shotId, JSONObject owner) throws Exception {
        JSONArray array = owner.optJSONArray("images");
        int count = Math.min(9, Math.max(1, array == null ? 0 : array.length()));
        for (int i = 0; i < count; i++) media(parent, shotId, "images", i,
                "图片 " + (i + 1), H3DirectorPlan.imageSlot(draft, shotId, "images", i));
        if (count < 9) {
            Button more = button("＋ 增加参考图位置"); parent.addView(more);
            more.setOnClickListener(v -> {
                try { H3DirectorPlan.imageSlot(draft, shotId, "images", count); rebuild(); }
                catch (Exception error) { error(error); }
            });
        }
    }

    private void media(LinearLayout parent, String shotId, String role, int index, String label, JSONObject slot) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumbnail = new ImageView(this); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(ThemeManager.imagePlaceholder(this)); row.addView(thumbnail, new LinearLayout.LayoutParams(dp(62), dp(62)));
        String uri = slot.optString("localUri"), remote = slot.optString("imageFile");
        TextView name = text(label + (uri.isEmpty() ? remote.isEmpty() ? " · 未选择" : " · 电脑文件：" + remote : " · 已选择手机图片"), 12, false);
        row.addView(name, weight());
        Button choose = button("选择"), clear = button("清除"); row.addView(choose); row.addView(clear); parent.addView(row);
        choose.setTag("h3-image-" + (shotId == null ? "shared" : shotId) + "-" + role + "-" + index);
        choose.setOnClickListener(v -> {
            pendingShot = shotId; pendingRole = role; pendingIndex = index; saveDraft();
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(pick, PICK_IMAGE);
        });
        clear.setOnClickListener(v -> { H3DirectorPlan.clearImage(slot); rebuild(); });
        if (!uri.isEmpty()) worker.execute(() -> {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri))) { BitmapFactory.decodeStream(in, null, options); }
                options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 160); options.inJustDecodeBounds = false;
                Bitmap bitmap;
                try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri))) { bitmap = BitmapFactory.decodeStream(in, null, options); }
                runOnUiThread(() -> { if (!isDestroyed()) thumbnail.setImageBitmap(bitmap); });
            } catch (Exception ignored) { /* A lost document permission is reported when preparing the upload. */ }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        if (pendingRole == null) { toast("图片位置已失效，请重新点选择"); return; }
        try {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) {}
            JSONObject slot = H3DirectorPlan.imageSlot(draft, pendingShot, pendingRole, pendingIndex);
            H3DirectorPlan.selectImage(slot, uri.toString()); saveDraft(); render();
        } catch (Exception error) { error(error); }
    }

    private void move(int from, int to) {
        try {
            JSONArray shots = draft.getJSONArray("shots"); Object a = shots.get(from), b = shots.get(to);
            shots.put(to, a); shots.put(from, b); rebuild();
        } catch (Exception error) { error(error); }
    }

    private void rebuild() { try { saveDraft(); render(); } catch (Exception error) { error(error); } }

    private void submit() {
        if (preparing) return;
        final JSONObject snapshot, base;
        try { saveDraft(); snapshot = new JSONObject(draft.toString()); base = new JSONObject(original.toString()); }
        catch (Exception error) { error(error); return; }
        preparing = true; generate.setEnabled(false); status.setText("正在检查电脑节点并上传参考图…");
        final String requestId = UUID.randomUUID().toString(), connection = server;
        final String workflowId = profile.id, workflowName = profile.name;
        worker.execute(() -> {
            try {
                android.content.Context app = getApplicationContext(); ComfyApiClient api = new ComfyApiClient(connection);
                JSONObject exporter = api.getNodeInfo(H3DirectorPlan.EXPORT_TYPE);
                if (!exporter.has(H3DirectorPlan.EXPORT_TYPE)) throw new Exception("电脑端尚未加载手机导演台输出插件，请先安装配套桌面插件并重启 ComfyUI");
                JSONObject schemas = new JSONObject();
                for (String type : new String[]{"UNETLoader", "LoraLoaderModelOnly", "MiniMaxH3Director"}) {
                    JSONObject info = api.getNodeInfo(type);
                    if (!info.has(type)) throw new Exception("电脑端缺少节点：" + type);
                    schemas.put(type, info.getJSONObject(type));
                }
                Map<String, String> uploads = new LinkedHashMap<>();
                for (String uri : H3DirectorPlan.localUris(snapshot)) uploads.put(uri, api.uploadImage(app, Uri.parse(uri)).workflowFilename());
                JSONObject prompt = H3DirectorPlan.compile(base, snapshot, uploads, schemas, requestId);
                SeedPolicy.resolve(prompt, schemas);
                JobRecord record = JobRecord.create(workflowId, workflowName + " · 导演台",
                        snapshot.getJSONArray("shots").length() + "个分镜 · " + snapshot.getString("mode"), "", connection, prompt.toString());
                record.selectedOutputNodeIdsJson = new JSONArray().put(H3DirectorPlan.EXPORT_ID).toString();
                record.outputSelectionSummary = "导演台完整视频";
                JobStore.add(app, record); GenerationManager.notifySubmitted(app, record.localId); GenerationManager.enqueueSubmission(app, record.localId);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    status.setText("已加入队列 · 电脑将按顺序生成并合成完整视频"); toast("已提交导演台任务");
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) { status.setText("准备失败，尚未提交：" + error.getMessage()); error(error); } });
            } finally {
                preparing = false;
                runOnUiThread(() -> { if (!isDestroyed()) { generate.setEnabled(true); generate.setText("▶ 生成整部视频"); } });
            }
        });
    }

    private void updateEstimate() {
        if (estimate == null || draft == null) return;
        try {
            JSONArray shots = draft.getJSONArray("shots"); double seconds = 0;
            for (int i = 0; i < shots.length(); i++) seconds += shots.getJSONObject(i).getDouble("seconds");
            estimate.setText(String.format(LocaleHolder.LOCALE, "%d 个分镜 · 计划约 %.2f 秒", shots.length(), seconds));
        } catch (Exception ignored) { estimate.setText("请填写有效的分镜时长"); }
    }

    private EditText number(JSONObject owner, String key, String label) { return edit(owner, key, label, false, true); }
    private EditText edit(JSONObject owner, String key, String hint, boolean multiline, boolean numeric) {
        EditText edit = new EditText(this); edit.setTextColor(ThemeManager.text(this)); edit.setHintTextColor(ThemeManager.muted(this));
        edit.setHint(hint); edit.setText(owner.optString(key, "")); edit.setTextSize(15);
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                : InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        edit.setMinLines(multiline ? 3 : 1); edit.setMaxLines(multiline ? 6 : 1); edit.setGravity(Gravity.TOP);
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) { put(owner, key, value.toString()); if ("seconds".equals(key)) updateEstimate(); }
        });
        return edit;
    }
    private void put(JSONObject owner, String key, Object value) { try { owner.put(key, value); } catch (Exception error) { error(error); } }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private LinearLayout card(String title) {
        LinearLayout card = column(); card.setPadding(dp(12), dp(12), dp(12), dp(12)); card.setBackgroundColor(ThemeManager.card(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(14); card.setLayoutParams(params);
        card.addView(text(title, 19, true)); return card;
    }
    private TextView text(String value, int size, boolean bold) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(ThemeManager.text(this));
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD); text.setPadding(0, dp(5), 0, dp(7)); return text;
    }
    private Button button(String value) { Button button = new Button(this); button.setText(value); button.setAllCaps(false); return button; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void error(Exception error) { new AlertDialog.Builder(this).setTitle("导演台提示").setMessage(error.getMessage()).setPositiveButton("确定", null).show(); }
    private static final class LocaleHolder { static final java.util.Locale LOCALE = java.util.Locale.SIMPLIFIED_CHINESE; }
}
