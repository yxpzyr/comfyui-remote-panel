package com.comfyremote.panel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolve date placeholders unsupported by the server's default SaveImage.
 * Changes only each queued API prompt; original imported workflow and saved presets stay untouched.
 */
final class SubmissionFilenamePolicy {
    private static final Pattern DATE_TOKEN = Pattern.compile("%date:[^%]+%", Pattern.CASE_INSENSITIVE);

    private SubmissionFilenamePolicy() {}

    static String forTask(String prefix, String localId, long submittedAt) {
        if (prefix == null) return null;
        Matcher matcher = DATE_TOKEN.matcher(prefix);
        if (!matcher.find()) return prefix;
        long timestamp = submittedAt > 0 ? submittedAt : System.currentTimeMillis();
        String clock = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date(timestamp));
        String id = localId == null ? "" : localId.replaceAll("[^a-zA-Z0-9]", "");
        if (id.length() > 12) id = id.substring(0, 12);
        if (id.isEmpty()) id = Long.toHexString(System.nanoTime());
        String result = matcher.replaceAll(Matcher.quoteReplacement(clock));
        return result + "_" + id;
    }

    /** Legacy placeholder names can still be downloaded without exposing raw %date macros in filenames. */
    static String forLegacyDownload(String filename, long at) {
        if (filename == null) return "";
        Matcher m = DATE_TOKEN.matcher(filename);
        if (!m.find()) return filename;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                .format(new Date(at > 0 ? at : System.currentTimeMillis()));
        return m.replaceAll(Matcher.quoteReplacement(stamp + "_legacy"));
    }
}
