package com.mcp.dubbo.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded CDP requests over a single WebSocket; protocol data is never logged. */
final class CdpConnection implements AutoCloseable {
    private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final WebSocket socket;

    CdpConnection(OkHttpClient client, String url, long timeoutMillis) throws IOException {
        socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { ready.complete(null); }
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (text.length() > 1024 * 1024) {
                    fail(); webSocket.cancel(); return;
                }
                try {
                    JSONObject message = JSON.parseObject(text);
                    if (message == null || !message.containsKey("id")) return;
                    CompletableFuture<JSONObject> future = pending.remove(message.getLongValue("id"));
                    if (future != null) future.complete(message);
                } catch (RuntimeException invalid) { fail(); webSocket.cancel(); }
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) { fail(); }
            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                fail(); webSocket.close(code, null);
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { fail(); }
        });
        try { await(ready, timeoutMillis); }
        catch (IOException failure) { close(); throw failure; }
    }

    JSONObject call(String method, JSONObject parameters, long timeoutMillis) throws IOException {
        long id = sequence.incrementAndGet();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JSONObject request = new JSONObject();
        request.put("id", id); request.put("method", method); request.put("params", parameters);
        try {
            if (!socket.send(request.toJSONString())) throw new IOException("Browser connection closed");
            JSONObject reply = await(future, timeoutMillis);
            if (reply.containsKey("error")) throw new IOException("Browser protocol request failed");
            JSONObject result = reply.getJSONObject("result");
            if (result == null || result.containsKey("exceptionDetails"))
                throw new IOException("Browser page is not ready");
            return result;
        } finally { pending.remove(id); }
    }

    private static <T> T await(CompletableFuture<T> future, long timeoutMillis) throws IOException {
        try { return future.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Browser login cancelled");
        } catch (TimeoutException timeout) { throw new IOException("Browser connection timed out"); }
        catch (ExecutionException failure) { throw new IOException("Browser connection closed"); }
    }

    private void fail() {
        IOException error = new IOException("Browser connection closed");
        ready.completeExceptionally(error);
        for (CompletableFuture<JSONObject> future : pending.values()) future.completeExceptionally(error);
        pending.clear();
    }

    @Override public void close() { fail(); socket.cancel(); }
}
