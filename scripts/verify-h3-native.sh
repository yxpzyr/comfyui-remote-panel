#!/usr/bin/env bash
set -u
task_result=0
gradle connectedDebugAndroidTest --stacktrace || task_result=$?
adb pull /sdcard/Pictures/ComfyRemoteValidation device-validation || true
if [ "$task_result" -eq 0 ]; then
    adb exec-out run-as com.comfyremote.panel cat files/h3-native-request.json > device-validation/h3-native-request.json || task_result=$?
fi
exit "$task_result"

