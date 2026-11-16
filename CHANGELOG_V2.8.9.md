# V2.8.9 (versionCode 34)

Based on V2.8.8 commit 104c247cac1e5a092145c07f914f8a778bb58485.

- Queue: recycle visible ListView rows, read job metadata off the UI thread, redraw only when records change, bound local bitmap memory and release Activity workers.
- Video lists use playback icons. Opening queue/gallery/results no longer downloads video files to extract covers. Video bytes are requested only after explicit playback or download.
- Block video files in image download/preview APIs before any HTTP request. Queue playback uses the native player; queue downloads stream media to Movies/ComfyRemote.
- Separate submission graphs into private per-job files. Back up the legacy history index before migration; retain existing task IDs, progress, outputs and errors. Progress updates serialize metadata only.
- Poll filtered per-prompt status every five seconds with failure backoff; share a 30-second queue metadata cache across jobs and avoid full queue polling while the progress WebSocket is open. Skip graph fields using a streaming JSON parser.
- Disable unused latent preview images per submission using extra_data.preview_method=none. Text progress and final outputs stay enabled. Older servers may ignore the optional hint.
- Persist WebSocket client IDs for reconnection. Preserve V2.8.8 video parameter repair and execution error diagnostics.
- Coalesce homepage result loading and discard stale Activity callbacks. Restore temporary parameters, expansion states and scroll after Android recreates the page. Large UI state stays in private files; only a small token enters the saved-instance Bundle.
- Reuse recent upload receipts for unchanged inputs on the same server. Explicitly reselecting the input invalidates its receipt and uploads again. Receipts expire after six hours.
- Stop playback/network buffering when the player leaves the foreground. Tap the continuation hint to resume.

Validation: run existing workflow/library/video/SaveVideo tests and VideoPerformanceRegressionTest in CI. Consult the final test report for actual counts. Passing JVM tests and an Android build do not establish real-phone memory behavior or cellular-byte savings.

Phone acceptance: install over V2.8.8 without uninstalling; verify existing library/history; run image-to-video; repeatedly open/return from queue; confirm parameters survive background recreation; play/return/download a video; compare system per-app traffic over equal periods. Unmodified ComfyUI history endpoints still transmit graph bytes even when this client skips parsing them; lower polling frequency reduces those requests.
