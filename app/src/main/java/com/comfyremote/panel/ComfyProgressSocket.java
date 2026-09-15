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
    }

    public interface Handle { void close(); }

    private ComfyProgressSocket() {}

    public static Handle connect(String server, String clientId, Listener listener) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
        String base = ComfyApiClient.normalizeBase(server);
        String ws = base.startsWith("https://") ? "wss://" + base.substring(8) : "ws://" + base.substring(7);
        String encodedClient;
        try { encodedClient = URLEncoder.encode(clientId == null ? "" : clientId, "UTF-8"); }
        catch (Exception e) { encodedClient = clientId == null ? "" : clientId; }
        String url = ws + "/ws?clientId=" + encodedClient;
        Request request = new Request.Builder().url(url).build();
        WebSocket socket = client.newWebSocket(request, new WebSocketListener() {
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
                    }
                } catch (Exception ignored) {}
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                // Polling remains the fallback source of truth; no user-facing failure needed here.
            }
        });
        return () -> {
            try { socket.close(1000, "done"); } catch (Exception ignored) {}
            try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
            try { client.connectionPool().evictAll(); } catch (Exception ignored) {}
        };
    }
}
