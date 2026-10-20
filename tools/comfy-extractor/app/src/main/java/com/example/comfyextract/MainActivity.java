package com.example.comfyextract;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

public class MainActivity extends Activity {
    private static final int PICK = 11, SAVE = 12;
    private TextView status;
    private volatile byte[] exportBytes;
    private String suggestedName = "ComfyUI工作流.json";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(50), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(247, 248, 252));
        TextView title = new TextView(this);
        title.setText("ComfyUI 图片转工作流");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(22, 32, 55));
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("选择 ComfyUI 生成的 PNG 图片，自动读取内嵌 workflow，并导出可导入 ComfyUI 的 JSON 工作流。\n\n完全离线 · 中文界面 · 不上传图片");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(75, 84, 104));
        subtitle.setPadding(0, dp(18), 0, dp(24));
        root.addView(subtitle);

        Button pick = new Button(this);
        pick.setText("选择 PNG 图片");
        pick.setAllCaps(false);
        pick.setTextSize(17);
        root.addView(pick);

        status = new TextView(this);
        status.setText("等待选择图片…");
        status.setTextSize(15);
        status.setTextColor(Color.rgb(52, 63, 83));
        status.setPadding(0, dp(26), 0, dp(18));
        root.addView(status);

        TextView note = new TextView(this);
        note.setText("提示：只有保留 workflow 元数据的 PNG 才能完整恢复原始节点与连线。普通图片、截图或被清除元数据的图片无法凭像素还原工作流。");
        note.setTextSize(13);
        note.setTextColor(Color.rgb(116, 123, 143));
        root.addView(note);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(root);
        setContentView(scroller);

        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/png");
            startActivityForResult(intent, PICK);
        });
    }

    private int dp(int n) {
        return (int) (getResources().getDisplayMetrics().density * n + .5f);
    }

    private String deriveName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if(cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {}
        if(name == null || name.trim().isEmpty()) return "ComfyUI工作流.json";
        name = name.replaceAll("(?i)\\.png$", "").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if(name.length() > 90) name = name.substring(0, 90);
        return name + "_工作流.json";
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if(result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if(req == PICK) {
            exportBytes = null;
            suggestedName = deriveName(uri);
            status.setText("正在读取 PNG 元数据…");
            new Thread(() -> {
                try(InputStream in = getContentResolver().openInputStream(uri)) {
                    if(in == null) throw new IOException("无法打开所选图片");
                    String workflow = PngWorkflow.extract(in);
                    JSONObject object = new JSONObject(workflow);
                    if(object.optJSONArray("nodes") == null)
                        throw new IOException("workflow 不是可导入的画布 JSON（缺少 nodes 数组）");
                    byte[] prepared = object.toString(2).getBytes(StandardCharsets.UTF_8);
                    exportBytes = prepared;
                    int count = object.getJSONArray("nodes").length();
                    runOnUiThread(() -> {
                        status.setText("提取成功！发现 " + count + " 个节点。\n请选择 JSON 保存位置。");
                        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        save.addCategory(Intent.CATEGORY_OPENABLE);
                        save.setType("application/json");
                        save.putExtra(Intent.EXTRA_TITLE, suggestedName);
                        startActivityForResult(save, SAVE);
                    });
                } catch(Exception e) {
                    runOnUiThread(() -> status.setText("提取失败：" + e.getMessage()));
                }
            }).start();
        } else if(req == SAVE) {
            final byte[] bytes = exportBytes;
            if(bytes == null) {
                status.setText("无可保存的工作流，请重新选择图片。");
                return;
            }
            status.setText("正在保存 JSON…");
            new Thread(() -> {
                try(OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if(out == null) throw new IOException("无法打开保存位置");
                    out.write(bytes);
                    out.flush();
                    runOnUiThread(() -> status.setText("保存成功！JSON 可直接导入 ComfyUI 画布。✅"));
                } catch(Exception e) {
                    runOnUiThread(() -> status.setText("保存失败：" + e.getMessage()));
                }
            }).start();
        }
    }

    static class PngWorkflow {
        private static final byte[] SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        static String extract(InputStream raw) throws IOException {
            DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
            byte[] sig = new byte[8];
            in.readFully(sig);
            if(!Arrays.equals(sig, SIGNATURE))
                throw new IOException("不是有效的 PNG 图片，当前仅支持 PNG");
            while(true) {
                int len;
                try { len = in.readInt(); } catch(EOFException e) { break; }
                if(len < 0 || len > 100_000_000)
                    throw new IOException("PNG 块大小异常");
                byte[] type = new byte[4];
                in.readFully(type);
                String kind = new String(type, StandardCharsets.US_ASCII);
                if(kind.equals("tEXt") || kind.equals("zTXt") || kind.equals("iTXt")) {
                    if(len > 16_000_000) throw new IOException("图片元数据过大");
                    byte[] data = new byte[len];
                    in.readFully(data);
                    in.readInt(); // CRC
                    String value = parseText(kind, data);
                    if(value != null) return value;
                } else {
                    skipFully(in, (long)len + 4);
                }
                if(kind.equals("IEND")) break;
            }
            throw new IOException("这张图片没有 workflow 元数据。图片被压缩、转发或重新保存后可能丢失元数据；仅有 prompt 也无法完整恢复画布。");
        }
        static String parseText(String kind, byte[] data) throws IOException {
            int zero = indexZero(data, 0);
            if(zero < 0) return null;
            String key = new String(data, 0, zero, StandardCharsets.ISO_8859_1);
            if(!"workflow".equals(key)) return null;
            int pos = zero + 1;
            if(kind.equals("tEXt"))
                return new String(data, pos, data.length - pos, StandardCharsets.UTF_8);
            if(kind.equals("zTXt")) {
                if(pos >= data.length || data[pos++] != 0)
                    throw new IOException("不支持的 PNG zTXt 压缩格式");
                return inflate(data, pos);
            }
            if(pos + 2 > data.length) throw new IOException("iTXt 元数据不完整");
            int flag = data[pos++] & 255;
            int method = data[pos++] & 255;
            int lang = indexZero(data, pos);
            if(lang < 0) throw new IOException("iTXt 语言字段不完整");
            pos = lang + 1;
            int translated = indexZero(data, pos);
            if(translated < 0) throw new IOException("iTXt 翻译字段不完整");
            pos = translated + 1;
            if(flag == 0)
                return new String(data, pos, data.length - pos, StandardCharsets.UTF_8);
            if(flag == 1 && method == 0) return inflate(data, pos);
            throw new IOException("不支持的 PNG iTXt 压缩格式");
        }
        static int indexZero(byte[] arr, int from) {
            for(int i = from; i < arr.length; i++)
                if(arr[i] == 0) return i;
            return -1;
        }
        static String inflate(byte[] data, int from) throws IOException {
            try(InputStream inflater = new InflaterInputStream(new ByteArrayInputStream(data, from, data.length - from));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while((n = inflater.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if(out.size() > 24_000_000) throw new IOException("解压后的元数据过大");
                }
                return out.toString("UTF-8");
            }
        }
        static void skipFully(InputStream in, long bytes) throws IOException {
            while(bytes > 0) {
                long skipped = in.skip(bytes);
                if(skipped > 0) { bytes -= skipped; continue; }
                if(in.read() == -1) throw new EOFException();
                bytes--;
            }
        }
    }
}
