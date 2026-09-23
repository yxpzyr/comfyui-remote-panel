package com.comfyremote.panel;

import org.json.JSONObject;

import java.util.List;

public class ImageRef {
    public final String filename;
    public final String subfolder;
    public final String type;
    /** Task/history timestamp retained for gallery/task ordering. */
    public final long timestamp;
    public final String promptId;
    /** Output node which exposed this image when ComfyUI history provides it. */
    public final String sourceNodeId;
    /** First time V2.3 observed this image during task execution. 0 means legacy/unknown. */
    public final long generatedAt;
    /** Stable per-task first-seen order. 0 means legacy/unknown. */
    public final int outputOrder;

    public ImageRef(String filename, String subfolder, String type) {
        this(filename, subfolder, type, 0L, "", "", 0L, 0);
    }

    public ImageRef(String filename, String subfolder, String type, long timestamp, String promptId) {
        this(filename, subfolder, type, timestamp, promptId, "", 0L, 0);
    }

    public ImageRef(String filename, String subfolder, String type, long timestamp, String promptId,
                    String sourceNodeId, long generatedAt, int outputOrder) {
        this.filename = filename == null ? "" : filename;
        this.subfolder = subfolder == null ? "" : subfolder;
        this.type = type == null || type.isEmpty() ? "output" : type;
        this.timestamp = timestamp;
        this.promptId = promptId == null ? "" : promptId;
        this.sourceNodeId = sourceNodeId == null ? "" : sourceNodeId;
        this.generatedAt = generatedAt;
        this.outputOrder = Math.max(0, outputOrder);
    }

    public ImageRef withObservation(long generatedAt, int outputOrder) {
        return new ImageRef(filename, subfolder, type, timestamp, promptId, sourceNodeId,
                Math.max(0L, generatedAt), Math.max(0, outputOrder));
    }

    public ImageRef withSourceNode(String nodeId) {
        return new ImageRef(filename, subfolder, type, timestamp, promptId, nodeId,
                generatedAt, outputOrder);
    }

    public static ImageRef fromJson(JSONObject o) {
        return fromJson(o, 0L, "");
    }

    public static ImageRef fromJson(JSONObject o, long timestamp, String promptId) {
        if (o == null) return new ImageRef("", "", "output", timestamp, promptId);
        long storedTs = o.optLong("timestamp", timestamp);
        String storedPrompt = o.optString("prompt_id", promptId == null ? "" : promptId);
        return new ImageRef(
                o.optString("filename", ""),
                o.optString("subfolder", ""),
                o.optString("type", "output"),
                storedTs,
                storedPrompt,
                o.optString("source_node_id", ""),
                o.optLong("generated_at", 0L),
                o.optInt("output_order", 0)
        );
    }

    public static ImageRef fromJson(JSONObject o, long timestamp, String promptId, String sourceNodeId) {
        ImageRef base = fromJson(o, timestamp, promptId);
        String node = base.sourceNodeId == null || base.sourceNodeId.isEmpty() ? sourceNodeId : base.sourceNodeId;
        return new ImageRef(base.filename, base.subfolder, base.type, base.timestamp, base.promptId,
                node, base.generatedAt, base.outputOrder);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("filename", filename);
            o.put("subfolder", subfolder);
            o.put("type", type);
            o.put("timestamp", timestamp);
            o.put("prompt_id", promptId);
            if (sourceNodeId != null && !sourceNodeId.isEmpty()) o.put("source_node_id", sourceNodeId);
            if (generatedAt > 0) o.put("generated_at", generatedAt);
            if (outputOrder > 0) o.put("output_order", outputOrder);
        } catch (Exception ignored) {}
        return o;
    }

    public String key() {
        return type + "|" + subfolder + "|" + filename;
    }

    /**
     * V2.3 final-image rule: newest observed output wins. For legacy records without V2.3
     * observation metadata, later list position is the deterministic fallback requested by the UI.
     */
    public static ImageRef chooseFinal(List<ImageRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        int bestIndex = -1;
        ImageRef best = null;
        for (int i = 0; i < refs.size(); i++) {
            ImageRef candidate = refs.get(i);
            if (candidate == null || candidate.filename == null || candidate.filename.isEmpty()) continue;
            if (best == null || compareForFinal(candidate, i, best, bestIndex) > 0) {
                best = candidate;
                bestIndex = i;
            }
        }
        return best;
    }

    private static int compareForFinal(ImageRef a, int ai, ImageRef b, int bi) {
        if (a.generatedAt != b.generatedAt) return Long.compare(a.generatedAt, b.generatedAt);
        if (a.outputOrder != b.outputOrder) return Integer.compare(a.outputOrder, b.outputOrder);
        if (a.timestamp != b.timestamp) return Long.compare(a.timestamp, b.timestamp);
        return Integer.compare(ai, bi); // legacy fallback: later returned image wins
    }
}
