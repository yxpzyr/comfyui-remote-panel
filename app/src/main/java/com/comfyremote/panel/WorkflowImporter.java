package com.comfyremote.panel;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared V2.4.1 helpers for importing individual workflow files or whole SAF folders. */
public final class WorkflowImporter {
    private static final int MAX_WORKFLOW_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TREE_JSON_FILES = 500;

    private WorkflowImporter() {}

    public static WorkflowProfile importProfile(Context context, Uri uri, String folderId,
                                                List<WorkflowProfile> latest, JSONObject objectInfo,
                                                String server) throws Exception {
        String raw = readText(context, uri, MAX_WORKFLOW_BYTES);
        JSONObject json = new JSONObject(raw);
        boolean uiFormat = json.optJSONArray("nodes") != null ||
                (json.optJSONObject("workflow") != null && json.optJSONObject("workflow").optJSONArray("nodes") != null);
        JSONObject prompt = uiFormat ? WorkflowUiConverter.toApiPrompt(json, server) : WorkflowUtils.extractPromptObject(json);
        String name = WorkflowStore.uniqueName(latest, displayName(context, uri), folderId);
        JSONObject uiCopy = uiFormat ? new JSONObject(json.toString()) : null;
        WorkflowProfile profile = WorkflowProfile.create(name, prompt, uiCopy);
        profile.folderId = folderId == null ? "" : folderId;
        if (objectInfo != null) profile.setOutputChoices(WorkflowUtils.findOutputNodes(prompt, objectInfo), true, false);
        else profile.setOutputChoices(WorkflowUtils.findOutputNodes(prompt), false, false);
        WorkflowStore.upsert(context, profile);
        return profile;
    }

    public static String displayName(Context context, Uri uri) {
        if (uri == null) return "工作流";
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.trim().isEmpty()) return s;
            }
        } catch (Exception ignored) {}
        String tail = uri.getLastPathSegment();
        return tail == null || tail.trim().isEmpty() ? "工作流" : tail;
    }

    public static String treeDisplayName(Context context, Uri treeUri) {
        if (treeUri == null) return "导入文件夹";
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            try (Cursor c = context.getContentResolver().query(rootDoc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String s = c.getString(0);
                    if (s != null && !s.trim().isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {}
        return "导入文件夹";
    }

    /** Recursively finds JSON files under a SAF tree. All nested files are imported into one app folder. */
    public static List<Uri> scanJsonFiles(Context context, Uri treeUri) throws Exception {
        List<Uri> out = new ArrayList<>();
        if (treeUri == null) return out;
        ContentResolver resolver = context.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(rootId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        while (!queue.isEmpty()) {
            String parentId = queue.removeFirst();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            try (Cursor c = resolver.query(children, projection, null, null, null)) {
                if (c == null) continue;
                int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (c.moveToNext()) {
                    String docId = idCol >= 0 ? c.getString(idCol) : "";
                    String name = nameCol >= 0 ? c.getString(nameCol) : "";
                    String mime = mimeCol >= 0 ? c.getString(mimeCol) : "";
                    if (docId == null || docId.isEmpty()) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        queue.addLast(docId);
                        continue;
                    }
                    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".json")) continue;
                    out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId));
                    if (out.size() > MAX_TREE_JSON_FILES) {
                        throw new Exception("文件夹内 JSON 超过 " + MAX_TREE_JSON_FILES + " 个，请拆分后再导入");
                    }
                }
            }
        }
        return out;
    }

    private static String readText(Context context, Uri uri, int maxBytes) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("无法打开文件");
            byte[] buf = new byte[32 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) throw new Exception("工作流文件过大（单个上限 32 MB）");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
