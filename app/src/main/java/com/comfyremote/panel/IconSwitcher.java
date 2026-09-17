package com.comfyremote.panel;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Switches between launcher aliases bundled in the APK. */
public final class IconSwitcher {
    public static final String DEFAULT = "default";
    public static final String MINIMAL = "minimal";
    public static final String BLUE = "blue";

    private static final String PREFS = "comfy_remote";
    private static final String KEY = "launcher_icon_v21";

    private IconSwitcher() {}

    public static String getChoice(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT);
        if (!MINIMAL.equals(value) && !BLUE.equals(value)) return DEFAULT;
        return value;
    }


    public static int iconRes(Context context) {
        String choice = getChoice(context);
        if (MINIMAL.equals(choice)) return R.drawable.ic_launcher_minimal;
        if (BLUE.equals(choice)) return R.drawable.ic_launcher_blue;
        return R.mipmap.ic_launcher;
    }

    public static void apply(Context context, String choice) {
        if (!MINIMAL.equals(choice) && !BLUE.equals(choice)) choice = DEFAULT;
        PackageManager pm = context.getPackageManager();
        ComponentName def = component(context, "LauncherDefault");
        ComponentName minimal = component(context, "LauncherMinimal");
        ComponentName blue = component(context, "LauncherBlue");
        ComponentName selected = DEFAULT.equals(choice) ? def : MINIMAL.equals(choice) ? minimal : blue;

        // Enable the new alias first so the launcher never observes a no-launcher window.
        set(pm, selected, true);
        set(pm, def, selected.equals(def));
        set(pm, minimal, selected.equals(minimal));
        set(pm, blue, selected.equals(blue));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice).apply();
    }

    private static ComponentName component(Context context, String simpleName) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + "." + simpleName);
    }

    private static void set(PackageManager pm, ComponentName component, boolean enabled) {
        pm.setComponentEnabledSetting(component,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
