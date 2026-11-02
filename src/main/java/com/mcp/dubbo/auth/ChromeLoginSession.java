package com.mcp.dubbo.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.driver.BoundedHttpBody;
import okhttp3.*;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one isolated browser process. It never attaches to a user's ordinary browser profile. */
final class ChromeLoginSession implements BrowserLogin.Session {
    private final HttpUrl admin;
    private final OkHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Path profile;
    private final FileChannel lockFile;
    private final FileLock lock;
    private final Object lifecycle = new Object();
    private volatile Process process;
    private int port;
    private String browserSocket;
    private final PagePresence pages = new PagePresence();
    private final Thread shutdownHook = new Thread(this::close, "browser-login-cleanup");

    static ChromeLoginSession open(String env, String url, String choice, long timeoutMillis) throws Exception {
        ChromeLoginSession session = new ChromeLoginSession(env, url);
        try { session.launch(choice, Math.min(timeoutMillis, 15000)); return session; }
        catch (Exception failure) { session.close(); throw failure; }
    }

    private ChromeLoginSession(String env, String url) throws Exception {
        if (env == null || !env.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid environment name");
        admin = HttpUrl.parse(url == null ? "" : url);
        if (admin == null || !admin.username().isEmpty() || !admin.password().isEmpty())
            throw new IllegalArgumentException("Browser login requires an HTTP(S) Admin URL without embedded credentials");
        client = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.SECONDS).proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
        String origin = admin.scheme() + "://" + admin.host() + ":" + admin.port();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 6; i++) suffix.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
        profile = Paths.get(System.getProperty("user.home"), ".dubbo-mcp", "browser", env + "-" + suffix);
        if (Files.isSymbolicLink(profile) || Files.isSymbolicLink(profile.getParent()))
            throw new IOException("专用浏览器目录不能是符号链接，请使用独立登录目录。");
        Files.createDirectories(profile);
        try { Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
        Path lockPath = profile.resolve(".login.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("专用浏览器锁文件无效。");
        lockFile = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock obtained;
        try { obtained = lockFile.tryLock(); }
        catch (OverlappingFileLockException conflict) { obtained = null; }
        catch (IOException failure) { lockFile.close(); throw failure; }
        if (obtained == null) {
            lockFile.close(); client.dispatcher().executorService().shutdownNow();
            throw new IOException("该环境已有浏览器登录正在进行，请完成或关闭后重试。");
        }
        lock = obtained;
    }

    private void launch(String choice, long timeoutMillis) throws Exception {
        Path executable = findBrowser(choice);
        Path active = profile.resolve("DevToolsActivePort");
        // An interrupted previous CLI may have left its own browser running. Do not attach to it.
        if (Files.exists(active)) {
            if (Files.isSymbolicLink(active)) throw new IOException("专用浏览器状态文件不能是符号链接。");
            if (Files.size(active) > 4096) throw new IOException("专用浏览器状态文件无效，请关闭登录窗口后重试。");
            List<String> previous = Files.readAllLines(active, StandardCharsets.UTF_8);
            if (!previous.isEmpty()) {
                try (Socket socket = new Socket()) {
                    int oldPort = Integer.parseInt(previous.get(0));
                    if (oldPort > 0 && oldPort <= 65535) {
                        socket.connect(new InetSocketAddress("127.0.0.1", oldPort), 200);
                        throw new ActiveBrowserException();
                    }
                } catch (ActiveBrowserException existing) {
                    throw new IOException("专用登录浏览器仍在运行，请先关闭该窗口再重试。");
                } catch (IOException | NumberFormatException stale) { /* Stale file from a stopped browser. */ }
            }
            Files.delete(active);
        }
        List<String> command = Arrays.asList(executable.toString(), "--user-data-dir=" + profile,
                "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1",
                "--no-first-run", "--no-default-browser-check", "--new-window", admin.toString());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        synchronized (lifecycle) {
            if (closed.get()) throw new IOException("浏览器登录已取消。");
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        }
        // Drain without retaining browser output: URLs and browser diagnostics can contain credentials.
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096]; while (input.read(buffer) != -1) { }
            } catch (IOException ignored) { }
        }, "browser-login-output");
        drain.setDaemon(true); drain.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IOException("浏览器未能启动或已关闭，请检查浏览器设置；也可使用 login --clipboard。");
            if (Files.exists(active) && Files.size(active) <= 4096) {
                List<String> lines = Files.readAllLines(active, StandardCharsets.UTF_8);
                if (lines.size() >= 2) {
                    try {
                        int candidate = Integer.parseInt(lines.get(0));
                        if (candidate > 0 && candidate <= 65535 && lines.get(1).startsWith("/devtools/browser/")) {
                            port = candidate; browserSocket = "ws://127.0.0.1:" + port + lines.get(1); return;
                        }
                    } catch (NumberFormatException incomplete) { }
                }
            }
            Thread.sleep(100);
        }
        throw new IOException("浏览器连接未能就绪，可能受企业策略限制；可使用 login --clipboard。");
    }

    @Override public boolean isAlive() { return !closed.get() && process != null && process.isAlive(); }

    @Override public String readCredential(long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        JSONArray targets = targets(timeoutMillis);
        pages.observe(targets, System.nanoTime());
        for (int i = 0; i < targets.size(); i++) {
            JSONObject target = targets.getJSONObject(i);
            if (!"page".equals(target.getString("type")) || !sameOrigin(admin, target.getString("url"))) continue;
            String socket = localSocket(target.getString("webSocketDebuggerUrl"), port, "/devtools/page/");
            if (socket == null) continue;
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) return null;
            try (CdpConnection connection = new CdpConnection(client, socket, remaining)) {
                JSONObject params = new JSONObject();
                // Recheck origin inside the page: navigation can race the target-list response.
                params.put("expression", captureExpression(admin.toString())); params.put("returnByValue", true);
                JSONObject evaluated = connection.call("Runtime.evaluate", params, budget(deadline));
                JSONObject value = evaluated.getJSONObject("result");
                if (value == null || value.getString("value") == null) continue;
                JSONObject captured = JSON.parseObject(value.getString("value"));
                if (captured != null && captured.getBooleanValue("tooLarge"))
                    throw new IllegalArgumentException("Browser credential exceeds size limit");
                if (captured == null || !sameOrigin(admin, captured.getString("url"))) continue;
                JSONObject query = new JSONObject(); query.put("urls", Collections.singletonList(captured.getString("url")));
                JSONArray cookies = connection.call("Network.getCookies", query, budget(deadline)).getJSONArray("cookies");
                return credential(captured.getString("authorization"), cookies);
            } catch (IOException transientNavigation) {
                if (!isAlive()) throw new IOException("浏览器已关闭，登录已取消；原凭据未更改。");
                // A login redirect can destroy a target or its execution context. Retry until the deadline.
            } catch (RuntimeException invalidReply) { throw new IOException("浏览器返回了无法识别的登录信息；原凭据未更改。"); }
        }
        return null;
    }

    private JSONArray targets(long timeoutMillis) throws IOException {
        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/json/list").build();
        Call call = client.newCall(request);
        call.timeout().timeout(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("无法连接专用登录浏览器。");
            Object parsed = JSON.parse(BoundedHttpBody.read(response.body(), 1024 * 1024));
            if (!(parsed instanceof JSONArray)) throw new IOException("浏览器页面列表格式不正确。");
            return (JSONArray) parsed;
        } catch (RuntimeException invalid) { throw new IOException("浏览器页面列表格式不正确。"); }
    }

    static String captureExpression(String adminUrl) {
        return "(()=>{if(location.origin!==new URL(" + JSON.toJSONString(adminUrl) + ").origin)return null;"
                + "const authorization=localStorage.getItem('token')||localStorage.getItem('Admin-Token')||'';"
                + "if(authorization.length>65536||location.href.length>8192)return JSON.stringify({tooLarge:true});"
                + "return JSON.stringify({url:location.href,authorization:authorization});})()";
    }

    static boolean sameOrigin(HttpUrl admin, String url) {
        HttpUrl page = url == null ? null : HttpUrl.parse(url);
        return page != null && page.scheme().equals(admin.scheme()) && page.host().equals(admin.host()) && page.port() == admin.port();
    }

    static String localSocket(String value, int port, String prefix) {
        try {
            URI uri = URI.create(value);
            if (!"ws".equals(uri.getScheme()) || !Arrays.asList("127.0.0.1", "localhost").contains(uri.getHost())
                    || uri.getPort() != port || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() == null || !uri.getPath().startsWith(prefix)) return null;
            return "ws://127.0.0.1:" + port + uri.getRawPath();
        } catch (RuntimeException invalid) { return null; }
    }

    static String credential(String authorization, JSONArray cookies) {
        StringBuilder cookie = new StringBuilder();
        if (cookies != null) {
            List<JSONObject> sorted = new ArrayList<>();
            for (int i = 0; i < cookies.size(); i++) sorted.add(cookies.getJSONObject(i));
            sorted.sort((a, b) -> Integer.compare(b.getString("path") == null ? 0 : b.getString("path").length(),
                    a.getString("path") == null ? 0 : a.getString("path").length()));
            for (JSONObject item : sorted) {
                String name = item.getString("name"), value = item.getString("value");
                if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null) continue;
                if (cookie.length() > 0) cookie.append("; ");
                cookie.append(name).append('=').append(value);
            }
        }
        if (cookie.length() == 0 && (authorization == null || authorization.isEmpty())) return null;
        JSONObject result = new JSONObject();
        result.put("cookie", cookie.toString()); result.put("authorization", authorization == null ? "" : authorization);
        return AuthSessionManager.normalizeCredential(result.toJSONString());
    }

    private static long budget(long deadline) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new IOException("Browser capture timed out");
        return remaining;
    }

    static Path findBrowser(String choice) throws IOException {
        String selected = choice == null ? "" : choice.trim();
        if (!selected.isEmpty() && !"chrome".equalsIgnoreCase(selected) && !"edge".equalsIgnoreCase(selected)) {
            Path path = Paths.get(selected).toAbsolutePath();
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path;
            throw new IOException("指定的浏览器可执行文件不可用。");
        }
        boolean chrome = !"edge".equalsIgnoreCase(selected), edge = !"chrome".equalsIgnoreCase(selected);
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            if (chrome) candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            if (edge) candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            if (chrome) candidates.add(System.getProperty("user.home") + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            if (edge) candidates.add(System.getProperty("user.home") + "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else if (os.contains("win")) {
            for (String root : Arrays.asList(System.getenv("PROGRAMFILES"), System.getenv("PROGRAMFILES(X86)"), System.getenv("LOCALAPPDATA"))) {
                if (root == null) continue;
                if (chrome) candidates.add(Paths.get(root, "Google", "Chrome", "Application", "chrome.exe").toString());
                if (edge) candidates.add(Paths.get(root, "Microsoft", "Edge", "Application", "msedge.exe").toString());
            }
        }
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isEmpty()) continue;
            if (chrome) for (String name : Arrays.asList("google-chrome", "google-chrome-stable", "chromium", "chromium-browser"))
                candidates.add(Paths.get(directory, name).toString());
            if (edge) for (String name : Arrays.asList("microsoft-edge", "microsoft-edge-stable")) candidates.add(Paths.get(directory, name).toString());
        }
        for (String candidate : candidates) {
            Path file = Paths.get(candidate);
            if (Files.isRegularFile(file) && Files.isExecutable(file)) return file;
        }
        throw new IOException("未找到 Chrome/Edge；可用 --browser=/完整路径 指定，或继续使用 login --clipboard。");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Serialize with process creation so Ctrl+C cannot leave a newly started browser orphaned.
        Process owned;
        synchronized (lifecycle) { owned = process; }
        try {
            if (owned != null && owned.isAlive() && browserSocket != null) {
                try (CdpConnection connection = new CdpConnection(client, browserSocket, 1000)) {
                    connection.call("Browser.close", new JSONObject(), 1000);
                } catch (IOException ignored) { }
            }
            if (owned != null && owned.isAlive()) {
                if (!owned.waitFor(2, TimeUnit.SECONDS)) {
                    owned.destroy();
                    if (!owned.waitFor(2, TimeUnit.SECONDS)) owned.destroyForcibly();
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); if (owned != null) owned.destroyForcibly();
        } finally {
            client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
            try { lock.release(); } catch (IOException ignored) { }
            try { lockFile.close(); } catch (IOException ignored) { }
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { }
        }
    }

    private static final class ActiveBrowserException extends IOException { }

    /** On macOS closing the last window can leave Chrome's process alive. */
    static final class PagePresence {
        private boolean observed;
        private Long emptySince;
        void observe(JSONArray targets, long now) throws IOException {
            for (int i = 0; i < targets.size(); i++) {
                if ("page".equals(targets.getJSONObject(i).getString("type"))) {
                    observed = true; emptySince = null; return;
                }
            }
            if (!observed) return;
            if (emptySince == null) emptySince = now;
            else if (now - emptySince >= TimeUnit.SECONDS.toNanos(1))
                throw new IOException("浏览器窗口已关闭，登录已取消；原凭据未更改。");
        }
    }
}
