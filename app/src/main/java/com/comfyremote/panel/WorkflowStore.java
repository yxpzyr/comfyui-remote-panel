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
            List<WorkflowProfile> out = WorkflowIndex.load(dir);

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
                writeProfile(dir, materializeMetadata(profile));
            }
            File[] existing = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (existing != null) {
                for (File f : existing) if (!keep.contains(f.getName())) f.delete();
            }
        }
    }

    public static WorkflowProfile upsert(Context context, WorkflowProfile profile) {
        synchronized (LOCK) {
            WorkflowProfile full = materializeMetadata(profile);
            full.updatedAt = System.currentTimeMillis();
            writeProfile(dir(context), full);
            profile.updatedAt = full.updatedAt;
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
        for (WorkflowProfile p : profiles) if (p.id.equals(id)) {
            if (!p.metadataOnly) return p;
            try { return readProfile(p.backingFile); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    public static WorkflowProfile summary(WorkflowProfile p) {
        WorkflowProfile out = new WorkflowProfile(p.id, p.name, "{}", null, p.favorite, p.createdAt, p.updatedAt, null);
        out.folderId = p.folderId;
        out.metadataOnly = true;
        out.backingFile = p.backingFile;
        return out;
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
        if (profile.metadataOnly) throw new IllegalStateException("Cannot save an unloaded workflow");
        File target = new File(dir, profile.id + ".json");
        try {
            atomicWrite(target, profile.toJson().toString());
            profile.backingFile = target;
            WorkflowIndex.updated(target, profile);
        } catch (Exception failure) {
            throw new IllegalStateException("工作流保存失败：" + profile.name, failure);
        }
    }

    static void atomicWrite(File target, String raw) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            Files.write(temp.toPath(), raw.getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception unsupported) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { temp.delete(); }
    }

    private static WorkflowProfile readProfile(File file) throws Exception {
        return WorkflowProfile.fromJson(new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
    }

    private static WorkflowProfile materializeMetadata(WorkflowProfile summary) {
        if (!summary.metadataOnly) return summary;
        try {
            WorkflowProfile full = readProfile(summary.backingFile);
            full.name = summary.name; full.favorite = summary.favorite; full.folderId = summary.folderId;
            return full;
        } catch (Exception e) { throw new IllegalStateException("工作流读取失败，原文件未改动：" + summary.name, e); }
    }

    /** Called on a worker only. Old misclassified collections are repaired only
     * when selected, after a complete compile succeeds and a recovery copy exists. */
    public static WorkflowProfile open(Context context, String id, String server) throws Exception {
        WorkflowProfile p;
        File file = new File(dir(context), id + ".json");
        long sourceModified, sourceSize;
        synchronized (LOCK) {
            p = readProfile(file); sourceModified = file.lastModified(); sourceSize = file.length();
        }
        if (p.collectionDetectionVersion == WorkflowVariants.DETECTION_VERSION || p.uiJson == null) return p;
        org.json.JSONArray detected = WorkflowVariants.detect(p.uiObjectOrNull());
        boolean wasCollection = p.isCollection();
        if (wasCollection && detected.length() == 0) {
            JSONObject fullPrompt = WorkflowUiConverter.toApiPrompt(p.uiObjectOrNull(), server);
            // Keep the active view's choices on conflicts; recover missing keyed
            // inputs/masks/parameters from the other previously split views.
            java.util.Iterator<String> states = p.variantStates.keys();
            while (states.hasNext()) {
                JSONObject state = p.variantStates.optJSONObject(states.next());
                if (state == null) continue;
                mergeMissing(p.overrides, state.optJSONObject("overrides"));
                mergeMissing(p.inputUris, state.optJSONObject("input_uris"));
                mergeMissing(p.inputReplace, state.optJSONObject("input_replace"));
                mergeMissing(p.inputMaskPaths, state.optJSONObject("input_mask_paths"));
            }
            p.promptJson = fullPrompt.toString();
            p.variants = new org.json.JSONArray(); p.activeVariantId = "";
            // Retain archived variant states as recovery data; isCollection is false.
            p.setOutputChoices(WorkflowUtils.findOutputNodes(fullPrompt), false, false);
        } else if (detected.length() >= 2) {
            String selected = p.activeVariantId;
            JSONObject chosen = null;
            for (int i = 0; i < detected.length(); i++) {
                JSONObject entry = detected.getJSONObject(i);
                if (entry.optString("id").equals(selected)) chosen = entry;
            }
            if (chosen == null) selected = WorkflowVariants.initialVariantId(detected);
            JSONObject previous = null;
            for (int i = 0; i < p.variants.length(); i++) {
                JSONObject entry = p.variants.optJSONObject(i);
                if (entry != null && selected.equals(entry.optString("id"))) previous = entry;
            }
            boolean sameOutputs = chosen != null && previous != null &&
                    chosen.optJSONArray("outputs").toString().equals(previous.optJSONArray("outputs").toString());
            if (!wasCollection || !sameOutputs) {
                Set<String> images = new HashSet<>();
                java.util.Iterator<String> keys = p.enabledAuxImages.keys();
                while (keys.hasNext()) { String key = keys.next(); if (p.isAuxImageEnabled(key)) images.add(key); }
                JSONObject compiled = WorkflowVariants.compile(p.uiObjectOrNull(), detected, selected, server, images, p.auxLoRAIds());
                p.promptJson = compiled.toString();
                p.setOutputChoices(WorkflowVariants.finalOutputs(compiled, detected, selected, null), false, true);
            }
            p.variants = detected; p.activeVariantId = selected;
        }
        p.collectionDetectionVersion = WorkflowVariants.DETECTION_VERSION;
        synchronized (LOCK) {
            if (file.lastModified() != sourceModified || file.length() != sourceSize)
                throw new Exception("工作流刚被更新，请重新选择；原数据已保留。");
            File backup = new File(new File(dir(context), "backup_before_v274"), file.getName());
            backup.getParentFile().mkdirs();
            if (!backup.exists()) Files.copy(file.toPath(), backup.toPath());
            writeProfile(dir(context), p);
        }
        return p;
    }

    private static void mergeMissing(JSONObject target, JSONObject source) throws Exception {
        if (source == null) return;
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) { String key = keys.next(); if (!target.has(key)) target.put(key, source.get(key)); }
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
