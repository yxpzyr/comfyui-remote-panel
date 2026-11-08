package com.comfyremote.panel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.InputStreamReader;

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
                        WorkflowProfile profile = WorkflowProfile.fromJson(new JSONObject(raw));
                        // Upgrade V2.5 imports without deleting saved inputs, masks or node parameters.
                        if (!profile.isCollection() && profile.uiJson != null && !profile.uiJson.isEmpty()) {
                            org.json.JSONArray variants = WorkflowVariants.detect(new JSONObject(profile.uiJson));
                            if (variants.length() >= 2) {
                                profile.initializeCollection(variants, WorkflowVariants.initialVariantId(variants));
                                profile.setOutputChoices(WorkflowVariants.finalOutputs(
                                        profile.promptObject(), variants, profile.activeVariantId, null), false, false);
                                writeProfile(dir, profile);
                            }
                        }
                        out.add(profile);
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

    /**
     * Read only library-row metadata. Workflows store their UI graph as an escaped,
     * often multi-megabyte JSON string. JsonReader.skipValue() avoids retaining it
     * on the UI thread or parsing it twice each time the library opens.
     *
     * Returned profiles are metadataOnly; never serialize them as workflow files.
     */
    public static List<WorkflowProfile> loadSummaries(Context context) {
        synchronized (LOCK) {
            File directory = dir(context);
            List<WorkflowProfile> summaries = new ArrayList<>();
            File[] files = directory.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    WorkflowProfile summary = readSummary(file);
                    if (summary != null) summaries.add(summary);
                }
            }
            // Preserve the legacy import path on a fresh installation.
            if (summaries.isEmpty() &&
                    !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getBoolean(KEY_MIGRATED, false)) {
                load(context);
                files = directory.listFiles((d, name) -> name.endsWith(".json"));
                if (files != null) for (File file : files) {
                    WorkflowProfile summary = readSummary(file);
                    if (summary != null) summaries.add(summary);
                }
            }
            sort(summaries);
            return summaries;
        }
    }

    private static WorkflowProfile readSummary(File file) {
        String fileId = file.getName().replaceFirst("\\.json$", "");
        String id = fileId;
        String name = "未命名工作流";
        String folder = "";
        long created = 1L, updated = 1L;
        boolean favorite = false;
        try (JsonReader r = new JsonReader(
                new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                String key = r.nextName();
                switch (key) {
                    case "id": id = r.peek() == JsonToken.STRING ? r.nextString() : skipString(r, fileId); break;
                    case "name": name = r.peek() == JsonToken.STRING ? r.nextString() : skipString(r, name); break;
                    case "folder_id": folder = r.peek() == JsonToken.STRING ? r.nextString() : skipString(r, ""); break;
                    case "favorite": favorite = r.peek() == JsonToken.BOOLEAN ? r.nextBoolean() : skipBoolean(r); break;
                    case "created_at": created = readLongOr(r, created); break;
                    case "updated_at": updated = readLongOr(r, updated); break;
                    default: r.skipValue(); break;
                }
            }
            r.endObject();
            if (id == null || id.isEmpty()) id = fileId;
            WorkflowProfile p = new WorkflowProfile(id, name, "{}", null,
                    favorite, created, updated, new JSONObject());
            p.folderId = folder == null ? "" : folder;
            p.metadataOnly = true;
            return p;
        } catch (Exception e) {
            android.util.Log.e("WorkflowStore", "读取工作流列表元数据失败: " + file.getName(), e);
            // Broken profile is ignored rather than deleting or rewriting its JSON.
            return null;
        }
    }

    private static String skipString(JsonReader r, String fallback) throws Exception {
        r.skipValue();
        return fallback;
    }

    private static boolean skipBoolean(JsonReader r) throws Exception {
        r.skipValue();
        return false;
    }

    private static long readLongOr(JsonReader r, long fallback) throws Exception {
        try {
            JsonToken token = r.peek();
            if (token == JsonToken.NUMBER || token == JsonToken.STRING) {
                String value = r.nextString();
                return Long.parseLong(value);
            }
            r.skipValue();
        } catch (NumberFormatException ignored) {}
        return fallback;
    }

    private static WorkflowProfile readFull(File directory, String id) {
        if (id == null || id.isEmpty()) return null;
        File file = new File(directory, id + ".json");
        if (!file.isFile()) return null;
        try {
            return WorkflowProfile.fromJson(new JSONObject(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("原始工作流读取失败，已取消保存以防数据丢失: " + id, e);
        }
    }

    /** Apply light-weight row edits to a full disk profile before writing. */
    private static WorkflowProfile ensureFull(File directory, WorkflowProfile profile) {
        if (!profile.metadataOnly) return profile;
        WorkflowProfile full = readFull(directory, profile.id);
        if (full == null)
            throw new IllegalStateException("工作流原文件不存在，已取消保存: " + profile.id);
        full.name = profile.name;
        full.folderId = profile.folderId;
        full.favorite = profile.favorite;
        return full;
    }

    public static void save(Context context, List<WorkflowProfile> profiles) {
        synchronized (LOCK) {
            File dir = dir(context);
            Set<String> keep = new HashSet<>();
            for (WorkflowProfile profile : profiles) {
                WorkflowProfile full = ensureFull(dir, profile);
                keep.add(full.id + ".json");
                writeProfile(dir, full);
            }
            File[] existing = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (existing != null) {
                for (File f : existing) if (!keep.contains(f.getName())) f.delete();
            }
        }
    }

    public static WorkflowProfile upsert(Context context, WorkflowProfile profile) {
        synchronized (LOCK) {
            WorkflowProfile full = ensureFull(dir(context), profile);
            full.updatedAt = System.currentTimeMillis();
            writeProfile(dir(context), full);
            return full;
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
                .thenComparing(Comparator.comparingLong((WorkflowProfile p) -> p.updatedAt).reversed())
                .thenComparing(p -> p.name.toLowerCase()));
    }
}
