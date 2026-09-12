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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_WORKFLOW = 101;
    private static final int REQ_IMAGE = 102;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private SharedPreferences prefs;

    private EditText addressEdit;
    private TextView connectionState, workflowState, statusText;
    private ImageView inputImage, outputImage;
    private Button generateBtn, stopBtn, saveBtn;

    private JSONObject workflowPrompt;
    private String workflowName = "未选择";
    private String selectedImageNodeId;
    private Uri inputUri;
    private volatile boolean cancelPolling = false;

    private ComfyApiClient.ImageDownload lastOutput;
    private ImageRef lastOutputRef;

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

        TextView title = text("ComfyUI 远程面板", 25, true);
        root.addView(title);
        TextView subtitle = text("Termius 负责隧道，这里只负责工作流、输入图与生成结果。", 13, false);
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
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        connRow.addView(addressEdit, addrLp);
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

        LinearLayout workflowCard = card();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(14), 0, 0);
        root.addView(workflowCard, cardLp);
        workflowCard.addView(sectionTitle("工作流"));
        Button workflowBtn = button("上传工作流 JSON");
        workflowCard.addView(workflowBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        workflowState = text("当前：未选择", 13, false);
        workflowState.setTextColor(Color.DKGRAY);
        workflowState.setPadding(0, dp(8), 0, 0);
        workflowCard.addView(workflowState);
        workflowBtn.setOnClickListener(v -> chooseWorkflow());

        LinearLayout imagesCard = card();
        LinearLayout.LayoutParams imagesLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        imagesLp.setMargins(0, dp(14), 0, 0);
        root.addView(imagesCard, imagesLp);
        imagesCard.addView(sectionTitle("输入 / 输出"));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setWeightSum(2f);
        imagesCard.addView(columns);

        LinearLayout inCol = imageColumn("输入图片");
        LinearLayout outCol = imageColumn("输出图片");
        LinearLayout.LayoutParams colLp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp1.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams colLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp2.setMargins(dp(5), 0, 0, 0);
        columns.addView(inCol, colLp1);
        columns.addView(outCol, colLp2);

        inputImage = previewImage();
        outputImage = previewImage();
        inCol.addView(inputImage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        outCol.addView(outputImage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        Button inputBtn = button("选择输入图");
        saveBtn = button("保存输出");
        saveBtn.setEnabled(false);
        LinearLayout.LayoutParams inputBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        inputBtnLp.setMargins(0, dp(8), 0, 0);
        LinearLayout.LayoutParams saveBtnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        saveBtnLp.setMargins(0, dp(8), 0, 0);
        inCol.addView(inputBtn, inputBtnLp);
        outCol.addView(saveBtn, saveBtnLp);
        inputBtn.setOnClickListener(v -> chooseInputImage());
        saveBtn.setOnClickListener(v -> saveCurrentOutput());

        LinearLayout actionCard = card();
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionLp.setMargins(0, dp(14), 0, 0);
        root.addView(actionCard, actionLp);
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

        TextView tip = text("提示：普通 ComfyUI JSON 与 API JSON 都可以直接使用；普通工作流会读取远程 /object_info 自动转换。", 12, false);
        tip.setTextColor(Color.GRAY);
        tip.setPadding(0, dp(16), 0, 0);
        root.addView(tip);

        setContentView(scroll);
    }

    private void restoreState() {
        addressEdit.setText(prefs.getString("server", "8188"));
        String saved = prefs.getString("workflow_json", "");
        workflowName = prefs.getString("workflow_name", "未选择");
        selectedImageNodeId = prefs.getString("image_node", null);
        if (!saved.isEmpty()) {
            try {
                workflowPrompt = WorkflowUtils.extractPromptObject(new JSONObject(saved));
                workflowState.setText("当前：" + workflowName + formatNodeSuffix());
            } catch (Exception e) {
                workflowPrompt = null;
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

    private void chooseInputImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_WORKFLOW) loadWorkflow(uri);
        else if (requestCode == REQ_IMAGE) loadInputPreview(uri);
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
                JSONObject prompt = uiFormat
                        ? WorkflowUiConverter.toApiPrompt(json, server)
                        : WorkflowUtils.extractPromptObject(json);
                List<WorkflowUtils.NodeChoice> nodes = WorkflowUtils.findLoadImageNodes(prompt);
                if (nodes.isEmpty()) throw new Exception("工作流已经读取成功，但没有检测到可替换的图片加载节点（带 image 文件名输入）。请确认工作流中存在 LoadImage 或兼容图片加载节点。");
                String name = getDisplayName(uri);
                runOnUiThread(() -> {
                    applyWorkflow(prompt, name, nodes);
                    statusText.setText(uiFormat ? "状态：普通 JSON 已自动转换，可直接生成" : "状态：API 工作流已载入");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    workflowState.setText("当前：读取失败，请重新选择");
                    showError("工作流读取失败", e);
                });
            }
        });
    }

    private void applyWorkflow(JSONObject prompt, String name, List<WorkflowUtils.NodeChoice> nodes) {
        workflowPrompt = prompt;
        workflowName = name == null ? "workflow.json" : name;
        if (nodes.size() == 1) {
            selectedImageNodeId = nodes.get(0).id;
            persistWorkflow();
            workflowState.setText("当前：" + workflowName + formatNodeSuffix());
            toast("已载入工作流");
        } else {
            String[] labels = new String[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) labels[i] = nodes.get(i).toString();
            new AlertDialog.Builder(this)
                    .setTitle("选择输入图节点")
                    .setItems(labels, (d, which) -> {
                        selectedImageNodeId = nodes.get(which).id;
                        persistWorkflow();
                        workflowState.setText("当前：" + workflowName + formatNodeSuffix());
                        toast("已选择输入节点 " + selectedImageNodeId);
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void persistWorkflow() {
        if (workflowPrompt == null) return;
        prefs.edit()
                .putString("workflow_json", workflowPrompt.toString())
                .putString("workflow_name", workflowName)
                .putString("image_node", selectedImageNodeId)
                .apply();
    }

    private void loadInputPreview(Uri uri) {
        inputUri = uri;
        statusText.setText("状态：已选择输入图");
        pool.submit(() -> {
            try {
                Bitmap b = decodeScaled(uri, 1200, 1200);
                runOnUiThread(() -> inputImage.setImageBitmap(b));
            } catch (Exception e) {
                runOnUiThread(() -> showError("图片读取失败", e));
            }
        });
    }

    private void startGeneration() {
        if (workflowPrompt == null) { toast("请先上传工作流 JSON"); return; }
        if (selectedImageNodeId == null || selectedImageNodeId.isEmpty()) { toast("请选择工作流输入节点"); return; }
        if (inputUri == null) { toast("请先选择输入图片"); return; }
        saveAddress();
        cancelPolling = false;
        generateBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        statusText.setText("状态：正在上传输入图片…");

        pool.submit(() -> {
            try {
                ComfyApiClient api = new ComfyApiClient(currentServer());
                ComfyApiClient.UploadResult uploaded = api.uploadImage(this, inputUri);
                setStatus("正在提交工作流…");
                JSONObject prompt = WorkflowUtils.withImageFilename(workflowPrompt, selectedImageNodeId, uploaded.workflowFilename());
                String promptId = api.queuePrompt(prompt);
                setStatus("已提交，生成中… 任务 " + shortId(promptId));

                JSONObject lastHistory = null;
                List<ImageRef> refs = null;
                for (int i = 0; i < 900 && !cancelPolling; i++) {
                    Thread.sleep(2000);
                    try {
                        lastHistory = api.getHistoryForPrompt(promptId);
                        refs = api.parseImagesFromPromptHistory(lastHistory, promptId);
                        if (!refs.isEmpty()) break;
                        String err = api.historyError(lastHistory, promptId);
                        if (!err.isEmpty()) throw new Exception("ComfyUI 执行失败：" + err);
                        if (api.isHistoryCompleted(lastHistory, promptId)) {
                            throw new Exception("工作流已完成，但没有发现图像输出。请确认工作流包含 PreviewImage/SaveImage 等图像输出节点。");
                        }
                    } catch (java.io.FileNotFoundException ignored) {
                        // Prompt may not have reached history yet.
                    }
                }
                if (cancelPolling) return;
                if (refs == null || refs.isEmpty()) throw new Exception("等待生成结果超时");

                ImageRef ref = refs.get(0);
                ComfyApiClient.ImageDownload dl = api.fetchImage(ref);
                Bitmap bmp = decodeScaled(dl.bytes, 1600, 1600);
                lastOutput = dl;
                lastOutputRef = ref;
                runOnUiThread(() -> {
                    outputImage.setImageBitmap(bmp);
                    saveBtn.setEnabled(true);
                    statusText.setText("状态：生成完成 · " + ref.filename);
                    generateBtn.setEnabled(true);
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

    private void saveCurrentOutput() {
        if (lastOutput == null || lastOutputRef == null) { toast("当前没有可保存的输出图"); return; }
        pool.submit(() -> {
            try {
                MediaSaver.saveImage(this, lastOutput.bytes, lastOutputRef.filename, lastOutput.mime);
                runOnUiThread(() -> toast("已保存到 Pictures/ComfyRemote"));
            } catch (Exception e) {
                runOnUiThread(() -> showError("保存失败", e));
            }
        });
    }

    private void saveAddress() {
        prefs.edit().putString("server", addressEdit.getText().toString().trim()).apply();
    }

    private String currentServer() {
        String s = addressEdit.getText().toString().trim();
        if (s.isEmpty()) s = "8188";
        return s;
    }

    private String formatNodeSuffix() {
        return selectedImageNodeId == null ? "" : " · 输入节点 " + selectedImageNodeId;
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText("状态：" + text));
    }

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

    private LinearLayout imageColumn(String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(label, 14, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, dp(8));
        col.addView(t);
        return col;
    }

    private ImageView previewImage() {
        ImageView i = new ImageView(this);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setBackgroundColor(Color.rgb(238, 240, 244));
        i.setPadding(dp(4), dp(4), dp(4), dp(4));
        return i;
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, dp(50), 1f);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
