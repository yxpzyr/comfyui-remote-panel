ComfyUI Remote Panel V1.3 patch

This patch fixes the version mismatch where the APK still used the old API-only MainActivity.

Replace these files in the repository:
1. app/build.gradle
2. app/src/main/java/com/comfyremote/panel/MainActivity.java
3. app/src/main/java/com/comfyremote/panel/WorkflowUiConverter.java

V1.3 behavior:
- accepts normal ComfyUI UI JSON (top-level nodes/links)
- converts it using the remote ComfyUI /object_info
- keeps API JSON support
- uses the V1.2 live combo/default repair logic
- shows V1.3 in the app subtitle so you can verify the correct APK is installed

After uploading and committing, GitHub Actions should build the new APK automatically.
