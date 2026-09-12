package com.comfyremote.panel;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ComfyApiClient {
    private final String base;
    private final String clientId = UUID.randomUUID().toString();

    public ComfyApiClient(String baseUrl) {
        this.base = normalizeBase(baseUrl);
    }

    public String getBase() { return base; }

    public static String normalizeBase(String text) {
        String s = text == null ? "" : text.trim();
        if (s.matches("^\\d{2,5}$")) s = "127.0.0.1:" + s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public void testConnection() throws Exception {
        Exception last = null;
        for (String path : new String[]{"/system_stats", "/history?max_items=1", "/api/system_stats"}) {
            try {
                HttpResult r = request("GET", path, null, null, 5000, 8000);
                if (r.code >= 200 && r.code < 300) return;
                last = new Exception("HTTP " + r.code + " " + path);
            } catch (Exception e) { last = e; }
        }
        throw new Exception("无法连接 ComfyUI：" + (last == null ? "未知错误" : last.getMessage()));
    }

    public UploadResult uploadImage(Context context, Uri uri) throws Exception {
        String display = getDisplayName(context, uri);
        if (display == null || display.trim().isEmpty()) display = "input.png";
        display = "mobile_" + System.currentTimeMillis() + "_" + sanitize(display);
        String boundary = "----ComfyRemote" + UUID.randomUUID();

        Exception last = null;
        for (String path : new String[]{"/upload/image", "/api/upload/image"}) {
            HttpURLConnection con = null;
            try {
                URL u = new URL(base + path);
                con = (HttpURLConnection) u.openConnection();
                con.setRequestMethod("POST");
                con.setConnectTimeout(10000);
                con.setReadTimeout(60000);
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream out = con.getOutputStream()) {
                    writePartHeader(out, boundary, "image", display, context.getContentResolver().getType(uri));
                    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new Exception("无法读取输入图片");
                        copy(in, out);
                    }
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    writeField(out, boundary, "type", "input");
                    writeField(out, boundary, "overwrite", "true");
                    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                int code = con.getResponseCode();
                byte[] bytes = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
                if (code >= 200 && code < 300) {
                    JSONObject obj = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    return new UploadResult(obj.optString("name", display), obj.optString("subfolder", ""), obj.optString("type", "input"));
                }
                last = new Exception("上传失败 HTTP " + code + ": " + new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) { last = e; }
            finally { if (con != null) con.disconnect(); }
        }
        throw last == null ? new Exception("上传图片失败") : last;
    }

    public String queuePrompt(JSONObject workflow) throws Exception {
        JSONObject body = new JSONObject();
        body.put("prompt", workflow);
        body.put("client_id", clientId);
        HttpResult r = postJsonAny(new String[]{"/prompt", "/api/prompt"}, body, 10000, 30000);
        JSONObject result = new JSONObject(r.text());
        String id = result.optString("prompt_id", "");
        if (id.isEmpty()) id = result.optString("job_id", "");
        if (id.isEmpty()) throw new JSONException("服务器未返回 prompt_id：" + result);
        return id;
    }

    public JSONObject getHistoryForPrompt(String promptId) throws Exception {
        String enc = URLEncoder.encode(promptId, "UTF-8");
        HttpResult r = getAny(new String[]{"/history/" + enc, "/api/history_v2/" + enc}, 8000, 15000);
        return new JSONObject(r.text());
    }

    public JSONObject getAllHistory() throws Exception {
        HttpResult r = getAny(new String[]{"/history?max_items=80", "/api/history_v2?max_items=80"}, 8000, 20000);
        return new JSONObject(r.text());
    }

    public List<ImageRef> parseImagesFromPromptHistory(JSONObject history, String promptId) {
        JSONObject entry = history.optJSONObject(promptId);
        if (entry == null && history.has("outputs")) entry = history;
        if (entry == null) return new ArrayList<>();
        return parseImagesFromEntry(entry);
    }

    public List<ImageRef> parseImagesFromAllHistory(JSONObject history, int limit) {
        LinkedHashMap<String, ImageRef> dedupe = new LinkedHashMap<>();

        JSONArray historyArray = history.optJSONArray("history");
        if (historyArray != null) {
            for (int i = 0; i < historyArray.length(); i++) {
                JSONObject entry = historyArray.optJSONObject(i);
                if (entry == null) continue;
                for (ImageRef ref : parseImagesFromEntry(entry)) dedupe.put(ref.key(), ref);
                if (dedupe.size() >= limit) break;
            }
        } else {
            List<String> keys = new ArrayList<>();
            Iterator<String> it = history.keys();
            while (it.hasNext()) keys.add(it.next());
            // Local ComfyUI commonly returns newest-first insertion order; preserve server order.
            for (String key : keys) {
                JSONObject entry = history.optJSONObject(key);
                if (entry == null) continue;
                for (ImageRef ref : parseImagesFromEntry(entry)) dedupe.put(ref.key(), ref);
                if (dedupe.size() >= limit) break;
            }
        }

        List<ImageRef> out = new ArrayList<>(dedupe.values());
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    private List<ImageRef> parseImagesFromEntry(JSONObject entry) {
        List<ImageRef> refs = new ArrayList<>();
        JSONObject outputs = entry.optJSONObject("outputs");
        if (outputs == null) return refs;
        Iterator<String> nodes = outputs.keys();
        while (nodes.hasNext()) {
            JSONObject node = outputs.optJSONObject(nodes.next());
            if (node == null) continue;
            JSONArray images = node.optJSONArray("images");
            if (images == null) continue;
            for (int i = 0; i < images.length(); i++) {
                JSONObject img = images.optJSONObject(i);
                if (img != null && !img.optString("filename", "").isEmpty()) refs.add(ImageRef.fromJson(img));
            }
        }
        return refs;
    }

    public boolean isHistoryCompleted(JSONObject history, String promptId) {
        JSONObject entry = history.optJSONObject(promptId);
        if (entry == null && history.has("status")) entry = history;
        if (entry == null) return false;
        JSONObject status = entry.optJSONObject("status");
        if (status == null) return false;
        if (status.optBoolean("completed", false)) return true;
        String s = status.optString("status_str", "").toLowerCase();
        return s.equals("success") || s.equals("completed") || s.equals("error");
    }

    public String historyError(JSONObject history, String promptId) {
        JSONObject entry = history.optJSONObject(promptId);
        if (entry == null && history.has("status")) entry = history;
        if (entry == null) return "";
        JSONObject status = entry.optJSONObject("status");
        if (status == null) return "";
        String s = status.optString("status_str", "").toLowerCase();
        if (!s.equals("error")) return "";
        return status.toString();
    }

    public ImageDownload fetchImage(ImageRef ref) throws Exception {
        String q = "?filename=" + enc(ref.filename) + "&subfolder=" + enc(ref.subfolder) + "&type=" + enc(ref.type);
        HttpResult r = getAny(new String[]{"/view" + q, "/api/view" + q}, 10000, 60000);
        return new ImageDownload(r.body, r.contentType == null ? MediaSaver.guessMime(ref.filename) : r.contentType);
    }

    public void interrupt() throws Exception {
        postEmptyAny(new String[]{"/interrupt", "/api/interrupt"}, 5000, 10000);
    }

    private HttpResult getAny(String[] paths, int connect, int read) throws Exception {
        Exception last = null;
        for (String p : paths) {
            try {
                HttpResult r = request("GET", p, null, null, connect, read);
                if (r.code >= 200 && r.code < 300) return r;
                last = new Exception("HTTP " + r.code + " " + p + ": " + r.text());
            } catch (Exception e) { last = e; }
        }
        throw last == null ? new Exception("请求失败") : last;
    }

    private HttpResult postJsonAny(String[] paths, JSONObject body, int connect, int read) throws Exception {
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        Exception last = null;
        for (String p : paths) {
            try {
                HttpResult r = request("POST", p, data, "application/json; charset=utf-8", connect, read);
                if (r.code >= 200 && r.code < 300) return r;
                last = new Exception("HTTP " + r.code + " " + p + ": " + r.text());
            } catch (Exception e) { last = e; }
        }
        throw last == null ? new Exception("请求失败") : last;
    }

    private void postEmptyAny(String[] paths, int connect, int read) throws Exception {
        Exception last = null;
        for (String p : paths) {
            try {
                HttpResult r = request("POST", p, new byte[0], "application/json", connect, read);
                if (r.code >= 200 && r.code < 300) return;
                last = new Exception("HTTP " + r.code + " " + p);
            } catch (Exception e) { last = e; }
        }
        throw last == null ? new Exception("请求失败") : last;
    }

    private HttpResult request(String method, String path, byte[] body, String contentType, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            con.setRequestMethod(method);
            con.setConnectTimeout(connectTimeout);
            con.setReadTimeout(readTimeout);
            con.setUseCaches(false);
            con.setRequestProperty("Accept", "*/*");
            if (body != null) {
                con.setDoOutput(true);
                if (contentType != null) con.setRequestProperty("Content-Type", contentType);
                try (OutputStream out = con.getOutputStream()) { out.write(body); }
            }
            int code = con.getResponseCode();
            InputStream stream = code >= 400 ? con.getErrorStream() : con.getInputStream();
            byte[] bytes = readAll(stream);
            return new HttpResult(code, bytes, con.getContentType());
        } finally { con.disconnect(); }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }

    private static void writePartHeader(OutputStream out, String boundary, String field, String filename, String mime) throws Exception {
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        String h = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename.replace("\"", "") + "\"\r\n" +
                "Content-Type: " + mime + "\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        String s = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(input, out);
            return out.toByteArray();
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }

    private static String getDisplayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    public static class UploadResult {
        public final String name, subfolder, type;
        public UploadResult(String name, String subfolder, String type) {
            this.name = name; this.subfolder = subfolder; this.type = type;
        }
        public String workflowFilename() { return subfolder == null || subfolder.isEmpty() ? name : subfolder + "/" + name; }
    }

    public static class ImageDownload {
        public final byte[] bytes;
        public final String mime;
        public ImageDownload(byte[] bytes, String mime) { this.bytes = bytes; this.mime = mime; }
    }

    private static class HttpResult {
        final int code; final byte[] body; final String contentType;
        HttpResult(int code, byte[] body, String contentType) { this.code = code; this.body = body; this.contentType = contentType; }
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }
}
