package com.comfyremote.panel;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Lightweight ComfyUI websocket progress listener. Falls back safely when unavailable. */
public final class ComfyProgressSocket {
    public interface Listener {
        void onProgress(String promptId, int value, int max, String node);
        void onExecuting(String promptId, String node);
        void onExecutionStart(String promptId);
        default void onDirectorProgress(String promptId, String node, int percent, String message) {}
    }

    public interface Handle { void close(); default boolean isOpen() { return false; } default boolean isActive(){return isOpen();} }

    private ComfyProgressSocket() {}

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
    public static Handle connect(String server, String clientId, Listener listener) {
        String base = ComfyApiClient.normalizeBase(server);
        String ws = base.startsWith("https://") ? "wss://" + base.substring(8) : "ws://" + base.substring(7);
        String encodedClient;
        try { encodedClient = URLEncoder.encode(clientId == null ? "" : clientId, "UTF-8"); }
        catch (Exception e) { encodedClient = clientId == null ? "" : clientId; }
        String url = ws + "/ws?clientId=" + encodedClient;
        Request request = new Request.Builder().url(url).build();
        java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        WebSocket socket = CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { connected.set(true); }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { connected.set(false);active.set(false); }
            @Override public void onClosing(WebSocket webSocket,int code,String reason){connected.set(false);active.set(false);webSocket.close(code,reason);}
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject root = new JSONObject(text);
                    String type = root.optString("type", "");
                    JSONObject data = root.optJSONObject("data");
                    if (data == null) return;
                    String promptId = data.optString("prompt_id", "");
                    if ("progress".equals(type)) {
                        listener.onProgress(promptId, data.optInt("value", 0), data.optInt("max", 0), data.optString("node", ""));
                    } else if ("executing".equals(type)) {
                        listener.onExecuting(promptId, data.isNull("node") ? "" : data.optString("node", ""));
                    } else if ("execution_start".equals(type)) {
                        listener.onExecutionStart(promptId);
                    } else if ("minimax_director_progress".equals(type)) {
                        double max = Math.max(1, data.optDouble("overall_max", 1));
                        int percent = (int) Math.round(94 * data.optDouble("overall_value", 0) / max);
                        int total = data.optInt("segment_total", 0), segment = data.optInt("segment", 1);
                        String phase = data.optString("phase_label", data.optString("phase", "生成"));
                        String message = total > 0 ? "分镜 " + segment + "/" + total + " · " + phase : "正在解析导演台时间轴";
                        listener.onDirectorProgress(promptId, data.optString("node_id"), Math.max(0, Math.min(94, percent)), message);
                    } else if ("comfyremote_h3_progress".equals(type)) {
                        listener.onDirectorProgress(promptId, data.optString("node_id"),
                                Math.max(94, Math.min(99, data.optInt("percent", 95))), data.optString("message", "合成完整视频"));
                    }
                } catch (Exception ignored) {}
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected.set(false);
                active.set(false);
                // Polling remains the fallback source of truth; no user-facing failure needed here.
            }
        });
        return new Handle() {
          @Override public boolean isOpen() { return connected.get(); }
          @Override public boolean isActive(){return active.get();}
          @Override public void close() {
            connected.set(false);
            active.set(false);
            socket.cancel();
          }
        };
    }
}
