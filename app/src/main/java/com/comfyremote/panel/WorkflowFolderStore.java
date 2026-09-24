package com.comfyremote.panel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stores the small V2.4 logical-folder index; workflow payloads remain file-backed. */
public final class WorkflowFolderStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "comfy_remote";
    private static final String KEY = "workflow_folders_v24";

    private WorkflowFolderStore() {}

    public static List<WorkflowFolder> load(Context context) {
        synchronized (LOCK) {
            List<WorkflowFolder> out = new ArrayList<>();
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            try {
                JSONArray a = new JSONArray(raw == null || raw.trim().isEmpty() ? "[]" : raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(WorkflowFolder.fromJson(o));
                }
            } catch (Exception ignored) {}
            sort(out);
            return out;
        }
    }

    public static WorkflowFolder create(Context context, String desiredName) {
        synchronized (LOCK) {
            List<WorkflowFolder> list = load(context);
            WorkflowFolder folder = WorkflowFolder.create(uniqueName(list, desiredName, null));
            list.add(folder);
            save(context, list);
            return folder;
        }
    }

    public static void rename(Context context, String id, String desiredName) {
        synchronized (LOCK) {
            List<WorkflowFolder> list = load(context);
            for (WorkflowFolder f : list) {
                if (f.id.equals(id)) {
                    f.name = uniqueName(list, desiredName, id);
                    f.updatedAt = System.currentTimeMillis();
                    break;
                }
            }
            save(context, list);
        }
    }

    public static void delete(Context context, String id) {
        synchronized (LOCK) {
            List<WorkflowFolder> list = load(context);
            list.removeIf(f -> f.id.equals(id));
            save(context, list);
        }
    }

    public static WorkflowFolder find(List<WorkflowFolder> folders, String id) {
        if (folders == null || id == null || id.isEmpty()) return null;
        for (WorkflowFolder f : folders) if (id.equals(f.id)) return f;
        return null;
    }

    public static String uniqueName(List<WorkflowFolder> folders, String desired, String excludeId) {
        String base = desired == null || desired.trim().isEmpty() ? "新文件夹" : desired.trim();
        String candidate = base;
        int suffix = 2;
        while (containsName(folders, candidate, excludeId)) candidate = base + " (" + suffix++ + ")";
        return candidate;
    }

    private static boolean containsName(List<WorkflowFolder> folders, String name, String excludeId) {
        if (folders == null) return false;
        for (WorkflowFolder f : folders) {
            if (excludeId != null && excludeId.equals(f.id)) continue;
            if (f.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static void save(Context context, List<WorkflowFolder> list) {
        sort(list);
        JSONArray a = new JSONArray();
        for (WorkflowFolder f : list) a.put(f.toJson());
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY, a.toString()).apply();
    }

    private static void sort(List<WorkflowFolder> list) {
        list.sort(Comparator.comparing((WorkflowFolder f) -> f.name.toLowerCase()));
    }
}
