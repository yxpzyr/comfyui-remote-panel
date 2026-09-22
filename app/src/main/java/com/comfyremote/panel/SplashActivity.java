package com.comfyremote.panel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;

/** V2.1 launcher/splash surface. */
public class SplashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ThemeManager.background(this));
        String custom = CustomizationStore.getSplashUri(this);
        boolean customLoaded = false;
        if (custom != null && !custom.isEmpty()) {
            try {
                Bitmap bitmap = decode(Uri.parse(custom));
                if (bitmap != null) {
                    ImageView background = new ImageView(this);
                    background.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    background.setImageBitmap(bitmap);
                    root.addView(background, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                    customLoaded = true;
                }
            } catch (Exception ignored) {
                CustomizationStore.setSplashUri(this, "");
            }
        }

        if (!customLoaded) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(IconSwitcher.iconRes(this));
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER);
            root.addView(icon, iconLp);
        }

        TextView label = new TextView(this);
        label.setText("ComfyUI 远程面板 · V2.2");
        label.setTextColor(customLoaded ? Color.WHITE : ThemeManager.text(this));
        label.setTextSize(16);
        label.setGravity(Gravity.CENTER);
        if (customLoaded) label.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM);
        labelLp.setMargins(dp(20), 0, dp(20), dp(30));
        root.addView(label, labelLp);
        setContentView(root);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }, customLoaded ? 850L : 500L);
    }

    private Bitmap decode(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 3000) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
