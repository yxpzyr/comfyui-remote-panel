package com.comfyremote.panel;

import org.json.JSONObject;

public class ImageRef {
    public final String filename;
    public final String subfolder;
    public final String type;
    public final long timestamp;
    public final String promptId;

    public ImageRef(String filename, String subfolder, String type) {
        this(filename, subfolder, type, 0L, "");
    }

    public ImageRef(String filename, String subfolder, String type, long timestamp, String promptId) {
        this.filename = filename == null ? "" : filename;
        this.subfolder = subfolder == null ? "" : subfolder;
        this.type = type == null || type.isEmpty() ? "output" : type;
        this.timestamp = timestamp;
        this.promptId = promptId == null ? "" : promptId;
    }

    public static ImageRef fromJson(JSONObject o) {
        return fromJson(o, 0L, "");
    }

    public static ImageRef fromJson(JSONObject o, long timestamp, String promptId) {
        return new ImageRef(
                o.optString("filename", ""),
                o.optString("subfolder", ""),
                o.optString("type", "output"),
                timestamp,
                promptId
        );
    }

    public String key() {
        return type + "|" + subfolder + "|" + filename;
    }
}
