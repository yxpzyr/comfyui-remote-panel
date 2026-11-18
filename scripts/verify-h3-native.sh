#!/usr/bin/env bash
set -u
task_result=0
gradle connectedDebugAndroidTest --stacktrace || task_result=$?
adb pull /sdcard/Pictures/ComfyRemoteValidation device-validation || true
if [ "$task_result" -eq 0 ]; then
    adb pull /sdcard/Download/ComfyRemoteValidation/h3-native-request.json device-validation/h3-native-request.json || task_result=$?
    python3 -c 'import json; graph=json.load(open("device-validation/h3-native-request.json")); assert graph["comfyremote_h3_video"]["class_type"]=="ComfyRemoteH3VideoOutput"' || task_result=$?
fi
exit "$task_result"

