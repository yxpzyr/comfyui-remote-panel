package com.comfyremote.panel;

import android.util.JsonReader;
import org.json.JSONObject;
import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Disposable metadata cache. Original profile files remain the source of truth. */
final class WorkflowIndex {
    private static final Map<String, JSONObject> memory = new HashMap<>();
    private WorkflowIndex() {}

    static List<WorkflowProfile> load(File dir) {
        List<WorkflowProfile> result = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return result;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (File file : files) {
            live.add(file.getAbsolutePath());
            try {
                JSONObject meta = memory.get(file.getAbsolutePath());
                if (!fresh(meta, file)) {
                    File cached = cacheFile(file);
                    try { meta = new JSONObject(new String(Files.readAllBytes(cached.toPath()), StandardCharsets.UTF_8)); }
                    catch (Exception ignored) { meta = null; }
                    if (!fresh(meta, file)) {
                        meta = scan(file);
                        writeCache(file, meta);
                    }
                    memory.put(file.getAbsolutePath(), meta);
                }
                WorkflowProfile p = new WorkflowProfile(meta.getString("id"), meta.getString("name"),
                        "{}", null, meta.optBoolean("favorite"), meta.optLong("created_at"),
                        meta.optLong("updated_at"), null);
                p.folderId = meta.optString("folder_id", "");
                p.metadataOnly = true;
                p.backingFile = file;
                result.add(p);
            } catch (Exception badFile) {
                android.util.Log.w("WorkflowIndex", "Cannot read workflow metadata: " + file.getName());
            }
        }
        memory.keySet().removeIf(key -> key.startsWith(dir.getAbsolutePath() + File.separator) && !live.contains(key));
        return result;
    }

    private static boolean fresh(JSONObject meta, File file) {
        return meta != null && meta.optInt("index_version") == 1 &&
                file.getName().equals(meta.optString("id") + ".json") &&
                meta.optLong("file_size", -1) == file.length() &&
                meta.optLong("file_modified", -1) == file.lastModified();
    }

    private static JSONObject scan(File file) throws Exception {
        JSONObject meta = new JSONObject();
        try (Reader input = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(input)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                switch (key) {
                    case "id": case "name": case "folder_id": meta.put(key, reader.nextString()); break;
                    case "favorite": meta.put(key, reader.nextBoolean()); break;
                    case "created_at": case "updated_at": meta.put(key, reader.nextLong()); break;
                    default: reader.skipValue(); // Never allocate prompt/UI/variant-state strings.
                }
            }
            reader.endObject();
        }
        if (!file.getName().equals(meta.optString("id") + ".json")) throw new Exception("Profile ID mismatch");
        stamp(meta, file);
        return meta;
    }

    static void updated(File file, WorkflowProfile p) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("id", p.id); meta.put("name", p.name); meta.put("folder_id", p.folderId);
            meta.put("favorite", p.favorite); meta.put("created_at", p.createdAt); meta.put("updated_at", p.updatedAt);
            stamp(meta, file);
            memory.put(file.getAbsolutePath(), meta);
            writeCache(file, meta);
        } catch (Exception ignored) { memory.remove(file.getAbsolutePath()); }
    }

    private static void stamp(JSONObject meta, File file) throws Exception {
        meta.put("index_version", 1); meta.put("file_size", file.length());
        meta.put("file_modified", file.lastModified());
    }

    private static File cacheFile(File file) {
        return new File(new File(file.getParentFile(), "index_v274"), file.getName());
    }

    private static void writeCache(File file, JSONObject meta) {
        try {
            File target = cacheFile(file);
            target.getParentFile().mkdirs();
            WorkflowStore.atomicWrite(target, meta.toString());
        } catch (Exception ignored) { /* A lost cache is rebuilt from the original. */ }
    }
}
