package com.comfyremote.panel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * File-backed reusable workflow library. Workflows can be large, so V1.8 keeps
 * the actual JSON in app-private files instead of putting multi-megabyte JSON
 * blobs in SharedPreferences.
 */
public final class WorkflowStore {
    private static final Object LOCK = new Object();
    private static final String PREFS = "comfy_remote";
    private static final String KEY_ACTIVE = "active_workflow_id_v18";
    private static final String KEY_MIGRATED = "workflow_profiles_v18_migrated_files";
    private static final String DIR_NAME = "workflows_v18";

    private WorkflowStore() {}

    public static List<WorkflowProfile> load(Context context) {
        synchronized (LOCK) {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            File dir = dir(context);
            List<WorkflowProfile> out = new ArrayList<>();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    try {
                        String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        out.add(WorkflowProfile.fromJson(new JSONObject(raw)));
                    } catch (Exception ignored) {}
                }
            }

            if (out.isEmpty() && !p.getBoolean(KEY_MIGRATED, false)) {
                migrateLegacy(context, p, out);
                p.edit().putBoolean(KEY_MIGRATED, true).apply();
            }
            sort(out);
            return out;
        }
    }

    public static void save(Context context, List<WorkflowProfile> profiles) {
        synchronized (LOCK) {
            File dir = dir(context);
            Set<String> keep = new HashSet<>();
            for (WorkflowProfile profile : profiles) {
                keep.add(profile.id + ".json");
                writeProfile(dir, profile);
            }
            File[] existing = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (existing != null) {
                for (File f : existing) if (!keep.contains(f.getName())) f.delete();
            }
        }
    }

    public static WorkflowProfile upsert(Context context, WorkflowProfile profile) {
        synchronized (LOCK) {
            profile.updatedAt = System.currentTimeMillis();
            writeProfile(dir(context), profile);
            return profile;
        }
    }

    public static void delete(Context context, String id) {
        synchronized (LOCK) {
            if (id != null && !id.isEmpty()) new File(dir(context), id + ".json").delete();
            String active = getActiveId(context);
            if (id != null && id.equals(active)) {
                List<WorkflowProfile> remain = load(context);
                setActiveId(context, remain.isEmpty() ? "" : remain.get(0).id);
            }
        }
    }

    public static String getActiveId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, "");
    }

    public static void setActiveId(Context context, String id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id == null ? "" : id).apply();
    }

    public static WorkflowProfile find(List<WorkflowProfile> profiles, String id) {
        if (profiles == null) return null;
        for (WorkflowProfile p : profiles) if (p.id.equals(id)) return p;
        return null;
    }

    public static String uniqueName(List<WorkflowProfile> profiles, String desired) {
        return uniqueName(profiles, desired, "", null);
    }

    /** V2.4 names only need to be unique inside the destination logical folder. */
    public static String uniqueName(List<WorkflowProfile> profiles, String desired, String folderId) {
        return uniqueName(profiles, desired, folderId, null);
    }

    public static String uniqueName(List<WorkflowProfile> profiles, String desired, String folderId, String excludeProfileId) {
        String base = desired == null || desired.trim().isEmpty() ? "工作流" : desired.trim();
        base = base.replaceAll("(?i)\\.json$", "");
        String candidate = base;
        int suffix = 2;
        while (containsName(profiles, candidate, folderId, excludeProfileId)) candidate = base + " (" + suffix++ + ")";
        return candidate;
    }

    private static void migrateLegacy(Context context, SharedPreferences p, List<WorkflowProfile> out) {
        String legacyPrompt = p.getString("workflow_json", "");
        if (legacyPrompt == null || legacyPrompt.trim().isEmpty()) return;
        String legacyUi = p.getString("workflow_ui_json", "");
        String legacyName = p.getString("workflow_name", "已保存工作流");
        try {
            WorkflowProfile profile = new WorkflowProfile(
                    null,
                    legacyName,
                    new JSONObject(legacyPrompt).toString(),
                    legacyUi == null || legacyUi.trim().isEmpty() ? null : new JSONObject(legacyUi).toString(),
                    true,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    new JSONObject()
            );
            writeProfile(dir(context), profile);
            out.add(profile);
            p.edit().putString(KEY_ACTIVE, profile.id).apply();
        } catch (Exception ignored) {}
    }

    private static File dir(Context context) {
        File d = new File(context.getFilesDir(), DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void writeProfile(File dir, WorkflowProfile profile) {
        File target = new File(dir, profile.id + ".json");
        File temp = new File(dir, profile.id + ".json.tmp");
        try {
            Files.write(temp.toPath(), profile.toJson().toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            temp.delete();
        }
    }

    private static boolean containsName(List<WorkflowProfile> profiles, String name, String folderId, String excludeProfileId) {
        String targetFolder = folderId == null ? "" : folderId;
        for (WorkflowProfile p : profiles) {
            if (excludeProfileId != null && excludeProfileId.equals(p.id)) continue;
            String pFolder = p.folderId == null ? "" : p.folderId;
            if (!targetFolder.equals(pFolder)) continue;
            if (p.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static void sort(List<WorkflowProfile> list) {
        Collections.sort(list, Comparator
                .comparing((WorkflowProfile p) -> !p.favorite)
                .thenComparing((WorkflowProfile p) -> -p.updatedAt)
                .thenComparing(p -> p.name.toLowerCase()));
    }
}
