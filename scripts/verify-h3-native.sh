#!/usr/bin/env bash
set -u
task_result=0
gradle connectedDebugAndroidTest --stacktrace || task_result=$?
adb pull /sdcard/Pictures/ComfyRemoteValidation device-validation || true
exit "$task_result"

