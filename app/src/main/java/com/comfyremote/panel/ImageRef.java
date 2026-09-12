package com.comfyremote.panel;

import org.json.JSONObject;

public class ImageRef {
    public final String filename;
    public final String subfolder;
    public final String type;

    public ImageRef(String filename, String subfolder, String type) {
        this.filename = filename == null ? "" : filename;
        this.subfolder = subfolder == null ? "" : subfolder;
        this.type = type == null || type.isEmpty() ? "output" : type;
    }

    public static ImageRef fromJson(JSONObject o) {
        return new ImageRef(
                o.optString("filename", ""),
                o.optString("subfolder", ""),
                o.optString("type", "output")
        );
    }

    public String key() {
        return type + "|" + subfolder + "|" + filename;
    }
}
