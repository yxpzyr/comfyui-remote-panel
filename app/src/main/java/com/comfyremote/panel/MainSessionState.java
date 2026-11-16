package com.comfyremote.panel;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Keep UI state out of Android's size-limited saved-instance Bundle. No graphs or bitmaps. */
final class MainSessionState {
    private MainSessionState() {}
    static void save(Context context, Bundle out, JSONObject state) {
        try {
            File dir = new File(context.getFilesDir(), "ui_sessions_v289");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String token = UUID.randomUUID().toString();
            File file = new File(dir, token + ".json");
            try (java.io.Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                writer.write(state.toString());
            }
            out.putString("panel_ui_session", token);
            File[] old = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (old != null) {
                java.util.Arrays.sort(old, java.util.Comparator.comparingLong(File::lastModified).reversed());
                for (int i = 16; i < old.length; i++) old[i].delete();
            }
        } catch (Exception e) { android.util.Log.w("MainSessionState", "无法保存界面状态", e); }
    }
    static JSONObject restore(Context context, Bundle saved) {
        if (saved == null) return null;
        String token = saved.getString("panel_ui_session", "");
        if (!token.matches("[A-Fa-f0-9-]{36}")) return null;
        File file = new File(new File(context.getFilesDir(), "ui_sessions_v289"), token + ".json");
        try { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return null; }
    }
}
