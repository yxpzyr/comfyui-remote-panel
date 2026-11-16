package com.comfyremote.panel;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.File;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Short-lived upload receipts prevent unchanged inputs being uploaded on UI reconstruction. */
final class InputUploadReceipts {
    private static final String PREFS = "input_upload_receipts_v289";
    private static final long TTL = 6L * 60 * 60 * 1000;
    private InputUploadReceipts() {}

    static String key(Context context, String server, Uri uri, String mask, boolean video) {
        try {
            long size = -1, modified = -1;
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                size = file.length(); modified = file.lastModified();
            } else {
                try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int s = cursor.getColumnIndex(OpenableColumns.SIZE);
                        int m = cursor.getColumnIndex("last_modified");
                        if (m < 0) m = cursor.getColumnIndex("date_modified");
                        if (s >= 0) size = cursor.getLong(s);
                        if (m >= 0) modified = cursor.getLong(m);
                    }
                }
            }
            // Providers without stable size metadata must upload normally.
            if (size < 0) return "";
            File maskFile = mask == null || mask.isEmpty() ? null : new File(mask);
            String identity = server + "|" + uri + "|" + size + "|" + modified + "|" + video
                    + "|" + mask + "|" + (maskFile == null ? "" : maskFile.length() + ":" + maskFile.lastModified());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception ignored) { return ""; }
    }

    static String find(Context context, String key) {
        if (key.isEmpty()) return "";
        try {
            JSONObject receipt = new JSONObject(context.getSharedPreferences(PREFS, 0).getString(key, "{}"));
            long age = System.currentTimeMillis() - receipt.optLong("time");
            return age >= 0 && age < TTL ? receipt.optString("filename", "") : "";
        } catch (Exception ignored) { return ""; }
    }

    static synchronized void remember(Context context, String key, String filename) {
        if (key.isEmpty()) return;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (prefs.getAll().size() >= 64) edit.clear();
            edit.putString(key, new JSONObject().put("time", System.currentTimeMillis())
                    .put("filename", filename).toString()).apply();
        } catch (Exception ignored) {}
    }

    static void forget(Context context, String key) {
        if (!key.isEmpty()) context.getSharedPreferences(PREFS, 0).edit().remove(key).apply();
    }
}
