package com.comfyremote.panel;

import org.json.JSONArray;
import org.json.JSONObject;

/** Formats the real ComfyUI /history status.messages execution_error event. */
public final class ComfyExecutionError {
    private ComfyExecutionError() {}

    public static String summary(JSONObject status) {
        if (!isError(status)) return "";
        JSONObject event = newestFailureEvent(status);
        if (event != null) {
            String nodeId = event.optString("node_id", "?");
            String nodeType = event.optString("node_type", "");
            String exception = firstNonEmpty(event.optString("exception_message", ""),
                    event.optString("message", ""), event.optString("exception_type", ""));
            StringBuilder result = new StringBuilder("节点 ").append(nodeId);
            if (!nodeType.isEmpty()) result.append(" (").append(nodeType).append(")");
            if (!exception.isEmpty()) result.append(" · ").append(trim(exception, 650));
            else result.append(" · ComfyUI 执行异常");
            return result.toString();
        }
        return "ComfyUI 已报告执行错误。点“查看错误详情”获取完整信息";
    }

    public static String details(JSONObject status) {
        if (!isError(status)) return "";
        StringBuilder out = new StringBuilder();
        JSONObject event = newestFailureEvent(status);
        if (event != null) {
            out.append("节点 ID: ").append(event.optString("node_id", "未知")).append('\n');
            out.append("节点类型: ").append(event.optString("node_type", "未知")).append('\n');
            out.append("异常类型: ").append(event.optString("exception_type", "未知")).append('\n');
            out.append("异常信息: ").append(event.optString("exception_message", "未知")).append('\n');
            JSONArray trace = event.optJSONArray("traceback");
            if (trace != null && trace.length() > 0) {
                out.append("\nPython traceback:\n");
                for (int i = 0; i < trace.length() && out.length() < 52000; i++)
                    out.append(trace.optString(i, "")).append('\n');
            }
        } else {
            out.append("未找到 execution_error 事件。原始状态:\n").append(status.toString());
        }
        if (status != null)
            out.append("\nstatus_str: ").append(status.optString("status_str", ""));
        return trim(out.toString(), 64000);
    }

    static boolean isError(JSONObject status) {
        if (status == null) return false;
        String value = status.optString("status_str", "");
        return "error".equalsIgnoreCase(value) || "failed".equalsIgnoreCase(value);
    }

    static JSONObject newestFailureEvent(JSONObject status) {
        if (status == null) return null;
        JSONArray messages = status.optJSONArray("messages");
        if (messages == null) return null;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONArray pair = messages.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;
            String eventName = pair.optString(0, "");
            if (!"execution_error".equals(eventName) && !"execution_interrupted".equals(eventName))
                continue;
            JSONObject payload = pair.optJSONObject(1);
            if (payload != null) return payload;
        }
        return null;
    }

    private static String firstNonEmpty(String... options) {
        for (String option : options) if (option != null && !option.trim().isEmpty()) return option.trim();
        return "";
    }

    private static String trim(String text, int limit) {
        if (text == null) return "";
        String normalized = text.replace("\r\n", "\n").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }
}
