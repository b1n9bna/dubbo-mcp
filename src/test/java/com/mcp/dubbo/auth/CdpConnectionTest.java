package com.mcp.dubbo.auth;

import com.alibaba.fastjson2.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class CdpConnectionTest {
    private MockWebServer server;
    private OkHttpClient client;
    @Before public void setup() throws Exception { server = new MockWebServer(); server.start(); client = new OkHttpClient(); }
    @After public void cleanup() throws Exception {
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); server.shutdown();
    }
    private String url() { return server.url("/devtools/page/test").toString().replace("http:", "ws:"); }
    private void reply(WebSocket socket, JSONObject request, String value) {
        JSONObject message = new JSONObject(); message.put("id", request.getLongValue("id"));
        message.put("result", JSON.parseObject(value)); socket.send(message.toJSONString());
    }
    @Test public void matchesRepliesAndIgnoresUnsolicitedEvents() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) {
                socket.send("{\"method\":\"Page.frameNavigated\",\"params\":{}}");
                reply(socket, JSON.parseObject(text), "{\"cookies\":[]}");
            }
        }));
        try (CdpConnection connection = new CdpConnection(client, url(), 1000)) {
            Assert.assertTrue(connection.call("Network.getCookies", new JSONObject(), 1000).getJSONArray("cookies").isEmpty());
        }
    }
    @Test public void timedOutReplyCannotSatisfyTheNextRequest() throws Exception {
        AtomicInteger count = new AtomicInteger();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) {
                if (count.incrementAndGet() == 2) reply(socket, JSON.parseObject(text), "{\"value\":\"second\"}");
            }
        }));
        try (CdpConnection connection = new CdpConnection(client, url(), 1000)) {
            try { connection.call("Runtime.evaluate", new JSONObject(), 50); Assert.fail(); }
            catch (IOException expected) { Assert.assertTrue(expected.getMessage().contains("timed out")); }
            Assert.assertEquals("second", connection.call("Runtime.evaluate", new JSONObject(), 1000).getString("value"));
        }
    }
    @Test public void protocolErrorDoesNotLeakBrowserData() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) {
                JSONObject request = JSON.parseObject(text);
                socket.send("{\"id\":" + request.getLongValue("id") + ",\"error\":{\"message\":\"SECRET_TOKEN\"}}");
            }
        }));
        try (CdpConnection connection = new CdpConnection(client, url(), 1000)) {
            try { connection.call("Runtime.evaluate", new JSONObject(), 1000); Assert.fail(); }
            catch (IOException expected) { Assert.assertFalse(expected.getMessage().contains("SECRET_TOKEN")); }
        }
    }
}
