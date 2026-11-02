package com.mcp.dubbo.auth;

import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.DubboAdminDriver;

import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.*;

/** Browser login is opt-in; existing credentials are replaced only after API validation. */
public final class BrowserLogin {
    interface Verifier { void verify(String credential) throws Exception; }
    interface Session extends AutoCloseable {
        String readCredential(long timeoutMillis) throws IOException;
        boolean isAlive();
        @Override void close();
    }
    interface Opener { Session open(long timeoutMillis) throws Exception; }

    private final AuthSessionManager sessions;
    private final Verifier verifier;
    private final Opener opener;
    private final PrintStream progress;
    private final long timeoutMillis;

    BrowserLogin(AuthSessionManager sessions, Verifier verifier, Opener opener, PrintStream progress, long timeoutMillis) {
        this.sessions = sessions; this.verifier = verifier; this.opener = opener;
        this.progress = progress; this.timeoutMillis = timeoutMillis;
    }

    public static void run(String env, String browser) throws Exception {
        ConfigManager config = ConfigManager.getInstance();
        int seconds = config.getInt("auth.browser.timeout_seconds", 180);
        if (seconds < 1 || seconds > 900)
            throw new IllegalArgumentException("auth.browser.timeout_seconds must be between 1 and 900");
        String url = config.getProperty("env." + env + ".admin_url");
        DubboAdminDriver driver = new DubboAdminDriver(env);
        new BrowserLogin(AuthSessionManager.getInstance(), driver::verifyCredential,
                remaining -> ChromeLoginSession.open(env, url, browser, remaining), System.err, seconds * 1000L).login(env);
    }

    void login(String env) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "browser-login-verify"); thread.setDaemon(true); return thread;
        });
        try {
            progress.println("正在检查已有登录态…");
            String existing = null;
            try { existing = sessions.getCredential(env); }
            catch (IllegalStateException unreadable) { progress.println("已有登录态无法读取，将重新进行浏览器登录。"); }
            if (existing != null && verify(executor, existing, remaining(deadline))) {
                progress.println("✓ 已有登录态有效，当前 MCP 已可使用，无需重新登录。");
                return;
            }
            progress.println("正在打开专用浏览器窗口，请完成登录…");
            progress.println("首次可能需要重新扫码；原来的 --clipboard、--stdin、--file 仍然可用。按 Ctrl+C 取消。");
            try (Session browser = opener.open(remaining(deadline))) {
                String lastCandidate = null;
                long nextAttempt = 0;
                while (true) {
                    long remaining = remaining(deadline);
                    if (!browser.isAlive()) throw new IOException("浏览器已关闭，登录已取消；原凭据未更改。");
                    String candidate = browser.readCredential(Math.min(remaining, 2000));
                    if (candidate != null && (!candidate.equals(lastCandidate) || System.nanoTime() >= nextAttempt)) {
                        progress.println("已检测到登录信息，正在验证访问权限…");
                        if (verify(executor, candidate, remaining(deadline))) {
                            remaining(deadline);
                            if (!browser.isAlive()) throw new IOException("浏览器已关闭，登录已取消；原凭据未更改。");
                            sessions.saveCredential(env, candidate);
                            progress.println("✓ 登录成功，凭据已保存；运行中的 MCP 将在下次请求自动读取。");
                            return;
                        }
                        if (!candidate.equals(lastCandidate)) progress.println("登录信息尚未通过验证，请在浏览器继续完成登录。");
                        lastCandidate = candidate;
                        nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    }
                    Thread.sleep(Math.min(250, remaining(deadline)));
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw new IOException("浏览器登录已取消；原凭据未更改。");
        } finally { executor.shutdownNow(); }
    }

    private boolean verify(ExecutorService executor, String credential, long remaining) throws Exception {
        Future<?> request = executor.submit(() -> { verifier.verify(credential); return null; });
        try { request.get(Math.min(remaining, 10000), TimeUnit.MILLISECONDS); return true; }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof DubboAdminDriver.AuthenticationException) return false;
            throw new IOException("无法访问 Dubbo Admin 完成登录验证，请检查网络或配置；原凭据未更改。", failure.getCause());
        } catch (TimeoutException timeout) {
            throw new IOException("Dubbo Admin 登录验证超时，请检查网络后重试；原凭据未更改。");
        } finally { request.cancel(true); }
    }

    private static long remaining(long deadline) throws IOException {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new IOException("浏览器登录等待超时；原凭据未更改。可重试或使用 login --clipboard。");
        return millis;
    }
}
