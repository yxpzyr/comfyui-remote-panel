package com.comfyremote.panel;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

public final class ThemeManager {
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    private static final String PREFS = "comfy_remote";
    private static final String KEY = "appearance_mode_v19";

    private ThemeManager() {}

    public static String getMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, MODE_SYSTEM);
    }

    public static void setMode(Context context, String mode) {
        if (!MODE_LIGHT.equals(mode) && !MODE_DARK.equals(mode)) mode = MODE_SYSTEM;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply();
    }

    public static boolean isDark(Context context) {
        String mode = getMode(context);
        if (MODE_DARK.equals(mode)) return true;
        if (MODE_LIGHT.equals(mode)) return false;
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void prepare(Activity activity) {
        boolean dark = isDark(activity);
        activity.setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    public static void applyWindow(Activity activity) {
        boolean dark = isDark(activity);
        Window w = activity.getWindow();
        w.setStatusBarColor(background(activity));
        w.setNavigationBarColor(card(activity));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = w.getDecorView().getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int flags = w.getDecorView().getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    public static int background(Context c) { return isDark(c) ? Color.rgb(17, 19, 22) : Color.rgb(246, 247, 249); }
    public static int card(Context c) { return isDark(c) ? Color.rgb(28, 31, 36) : Color.WHITE; }
    public static int miniCard(Context c) { return isDark(c) ? Color.rgb(36, 40, 47) : Color.rgb(248, 249, 251); }
    public static int text(Context c) { return isDark(c) ? Color.rgb(243, 244, 246) : Color.rgb(28, 30, 35); }
    public static int secondary(Context c) { return isDark(c) ? Color.rgb(188, 193, 202) : Color.DKGRAY; }
    public static int muted(Context c) { return isDark(c) ? Color.rgb(135, 142, 153) : Color.GRAY; }
    public static int imagePlaceholder(Context c) { return isDark(c) ? Color.rgb(42, 46, 54) : Color.rgb(232, 234, 239); }
    public static int selected(Context c) { return isDark(c) ? Color.rgb(38, 55, 88) : Color.rgb(222, 235, 255); }
    public static int accent(Context c) { return isDark(c) ? Color.rgb(124, 156, 255) : Color.rgb(50, 110, 215); }
    public static int success(Context c) { return isDark(c) ? Color.rgb(87, 201, 126) : Color.rgb(30, 145, 80); }
    public static int warning(Context c) { return isDark(c) ? Color.rgb(235, 177, 72) : Color.rgb(210, 135, 0); }
    public static int error(Context c) { return isDark(c) ? Color.rgb(255, 112, 112) : Color.rgb(200, 55, 55); }
}
