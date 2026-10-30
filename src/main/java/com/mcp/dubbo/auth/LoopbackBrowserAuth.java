package com.mcp.dubbo.auth;

import com.mcp.dubbo.auth.spi.AuthStrategy;
import com.mcp.dubbo.config.ConfigManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback Browser Authentication (RFC 8252 pattern).
 * Uses pure JDK 8 com.sun.net.httpserver.HttpServer (zero external dependencies).
 * Automatically pops up system browser for user to log into dubbo-admin (SSO / Feishu / Password),
 * then captures the session cookie via local loopback.
 */
public class LoopbackBrowserAuth implements AuthStrategy {

    private final AuthSessionManager sessionManager = AuthSessionManager.getInstance();

    @Override
    public String getName() {
        return "loopback-browser";
    }

    @Override
    public String getValidCredential(String env, String adminUrl) throws Exception {
        // 1. Check existing session
        String existing = sessionManager.getCredential(env);
        if (existing != null && !existing.trim().isEmpty()) {
            return existing;
        }

        // 2. Launch loopback HTTP server
        int port = ConfigManager.getInstance().getInt("auth.callback.port", 20888);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedCredential = new AtomicReference<>();

        server.createContext("/", new AuthPageHandler(adminUrl, port));
        server.createContext("/callback", new CallbackHandler(latch, capturedCredential));
        server.setExecutor(null);
        server.start();

        System.err.println("\n==================================================================");
        System.err.println("🔐 [Dubbo-MCP] 检测到生产环境尚未授权，正在唤起默认浏览器...");
        System.err.println("👉 如果浏览器未自动弹出，请手动在浏览器中打开: " + adminUrl);
        System.err.println("👉 本地授权服务已就绪: http://127.0.0.1:" + port);
        System.err.println("==================================================================\n");

        // 3. Open system browser
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(adminUrl));
            }
        } catch (Exception e) {
            System.err.println("[LoopbackBrowserAuth] Could not open browser automatically: " + e.getMessage());
        }

        // 4. Wait up to 120 seconds for user authentication
        boolean success = latch.await(120, TimeUnit.SECONDS);
        server.stop(0);

        if (success && capturedCredential.get() != null) {
            String cred = capturedCredential.get().trim();
            sessionManager.saveCredential(env, cred);
            System.err.println("✅ [Dubbo-MCP] 授权成功！登录凭证已持久化，会话已自动绑定。");
            return cred;
        } else {
            throw new IllegalStateException("Authentication timed out (120s) or was cancelled.");
        }
    }

    @Override
    public boolean ping(String adminUrl, String credential) {
        // Handled in DubboAdminDriver via OkHttp ping
        return true;
    }

    private static class AuthPageHandler implements HttpHandler {
        private final String adminUrl;
        private final int port;

        public AuthPageHandler(String adminUrl, int port) {
            this.adminUrl = adminUrl;
            this.port = port;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String bookmarklet = "javascript:(function(){" +
                    "fetch('http://127.0.0.1:" + port + "/callback',{method:'POST',body:document.cookie})" +
                    ".then(()=>{alert('✅ 登录凭证已成功同步至 Codex / Dubbo-MCP!')});" +
                    "})();";

            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                    "<title>Dubbo-MCP 授权助手</title>" +
                    "<style>" +
                    "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                    "margin: 40px auto; max-width: 650px; line-height: 1.6; color: #333; background: #f9fafb; padding: 20px; }" +
                    ".card { background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); }" +
                    "h2 { color: #1e3a8a; margin-top: 0; }" +
                    ".btn { display: inline-block; background: #2563eb; color: white; padding: 10px 20px; border-radius: 6px; text-decoration: none; font-weight: bold; margin: 10px 0; }" +
                    ".btn-bookmark { background: #059669; }" +
                    "textarea { width: 100%; height: 90px; margin-top: 10px; border: 1px solid #d1d5db; border-radius: 6px; padding: 8px; }" +
                    "</style></head><body><div class='card'>" +
                    "<h2>🧭 Dubbo-MCP 生产环境授权</h2>" +
                    "<p>请在已登录的 <b>Dubbo-Admin</b> 页面完成授权：</p>" +
                    "<ol>" +
                    "<li>确保你已经在浏览器中成功登录了企业 Dubbo-Admin（<a href='" + adminUrl + "' target='_blank'>打开 Dubbo-Admin</a>）；</li>" +
                    "<li><b>方式一（最推荐）：</b> 将下方绿色按钮拖拽到浏览器书签栏，在 Dubbo-Admin 页面点一下即可一键同步：<br>" +
                    "<a class='btn btn-bookmark' href=\"" + bookmarklet + "\">【📌 同步至 Dubbo-MCP】</a></li>" +
                    "<li><b>方式二：</b> 也可以直接在下方粘贴从浏览器中复制的 Cookie：<br>" +
                    "<form method='POST' action='/callback'>" +
                    "<textarea name='cookie' placeholder='粘贴 Cookie 文本，例如: SESSION=xxxx; ...'></textarea><br>" +
                    "<button type='submit' class='btn'>立即提交</button>" +
                    "</form></li>" +
                    "</ol></div></body></html>";

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class CallbackHandler implements HttpHandler {
        private final CountDownLatch latch;
        private final AtomicReference<String> captured;

        public CallbackHandler(CountDownLatch latch, AtomicReference<String> captured) {
            this.latch = latch;
            this.captured = captured;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Support CORS for bookmarklet fetch
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            String body = new String(baos.toByteArray(), StandardCharsets.UTF_8);

            // If submitted from form, extract 'cookie='
            if (body.startsWith("cookie=")) {
                body = java.net.URLDecoder.decode(body.substring("cookie=".length()), "UTF-8");
            }

            if (body != null && !body.trim().isEmpty()) {
                captured.set(body.trim());
                latch.countDown();
            }

            String reply = "<!DOCTYPE html><html><body style='text-align:center;font-family:sans-serif;margin-top:60px;'>" +
                    "<h2 style='color:#059669;'>✅ 授权成功！</h2>" +
                    "<p>登录态已成功同步至 Codex / Dubbo-MCP，您可以关闭此网页回到终端继续操作。</p>" +
                    "</body></html>";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
