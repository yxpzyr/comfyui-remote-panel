# V2.8.11 (versionCode 36)

- Seed controls: dice generates a visible fixed seed within the node's declared range; -1 remains an every-submission random policy until the job is about to be sent. Freeze real seeds in the task snapshot before the first HTTP request. Queue records show actual seed values. Importing rgthree -1 no longer randomizes only once; shared seed sources remain shared within a job.
- MiniMax H3 text branch: discover TE_text_display and text-output nodes, include the previously omitted prompt-enhancement group, preserve upstream output nodes needed by the selected result, and add newly discovered branches to existing collections without replacing saved variants or overrides.
- Text results: recognize completed text-only tasks, store results in private per-task files instead of the progress index, show/select/copy results on the home page and queue. Text-only success no longer waits for media files.
- Preserve V2.8.10 cached video covers and nonblocking playback navigation.

Tests use a sanitized structural fixture of the MiniMax H3 workflow and its node interfaces, plus an Android emulator with a local mock ComfyUI for dice, two random-seed submissions, frozen payloads, text completion and clipboard copying. No user prompt contents or API credentials are included in the fixture.
