package com.comfyremote.panel;

import android.widget.ImageView;
import java.util.function.BooleanSupplier;

/** A video tile is deliberately network-free. Remote frame extraction can fetch
 * an entire MP4, and failed extraction used to retry on every queue refresh.
 * Only an explicit Play or Download action may request video bytes. */
final class VideoThumbnailLoader {
    private VideoThumbnailLoader() {}
    static void load(String server, ImageRef ref, ImageView target, int maxSide,
                     BooleanSupplier stillVisible) {
        if (ref != null && ref.isVideo() && stillVisible.getAsBoolean())
            target.setImageResource(android.R.drawable.ic_media_play);
    }
}
