# V2.8.10 (versionCode 35)

Restores actual video covers on the home page, queue and gallery. V2.8.9's generic play icons were an unsuitable substitute for visual video identification.

- Decode the first keyframe via a custom MediaDataSource. Fetch only 64 KiB byte ranges with a 6 MiB transfer budget and 15-second retrieval deadline. Reject large HTTP 200 responses when a proxy ignores Range.
- Share one extraction per video across views. Store JPEG covers on disk (40 MiB / 400 entries), retain a bounded bitmap cache, and persist failed-cover backoff to prevent queue refresh from repeatedly downloading the same video. Decode at most two covers at once; skip stale/hidden pages.
- Capture a rendered playback frame into the same cover cache as a fallback for servers without range support.
- Replace VideoView with a TextureView and a dedicated media HandlerThread. Every MediaPlayer operation, including setDataSource, position queries and release, runs on that thread. Back closes the UI without waiting for media preparation or native teardown. Button Back, system Back, foreground exit and late callbacks share the cancellation path.
- Preserve pause, seek, audio, video download, queue virtualization, metadata-only job storage, state recovery and per-submission latent-preview suppression from V2.8.9.

Validation includes JVM regressions plus an Android 11 emulator using a real H.264/AAC MP4, a valid 16 MiB padding atom before moov, persistent-cover cache checks, native playback and return while HTTP response headers are delayed for six seconds. Consult test artifacts for actual outcomes and timings; emulator evidence does not establish identical behavior on every phone.
