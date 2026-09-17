package com.comfyremote.panel;

import android.content.Context;

/** Lightweight V2.1 appearance persistence. */
public final class CustomizationStore {
    private static final String PREFS = "comfy_remote";
    private static final String KEY_BACKGROUND = "custom_background_uri_v21";
    private static final String KEY_SPLASH = "custom_splash_uri_v21";

    private CustomizationStore() {}

    public static String getBackgroundUri(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND, "");
    }

    public static void setBackgroundUri(Context context, String uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BACKGROUND, uri == null ? "" : uri).apply();
    }

    public static String getSplashUri(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SPLASH, "");
    }

    public static void setSplashUri(Context context, String uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SPLASH, uri == null ? "" : uri).apply();
    }
}
