package com.mcp.dubbo.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.config.ConfigManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.*;

/** Disk is the source of truth so a running MCP sees credentials saved by another CLI. */
public class AuthSessionManager {
    private static final AuthSessionManager INSTANCE = new AuthSessionManager();
    private final Path directory;
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dubbo-mcp-keepalive"); t.setDaemon(true); return t;
    });
    public AuthSessionManager(Path directory) { this.directory = directory; }
    private AuthSessionManager() { this(Paths.get(System.getProperty("user.home"), ".dubbo-mcp")); }
    public static AuthSessionManager getInstance() { return INSTANCE; }

    public synchronized String getCredential(String env) {
        Path file = sessionFile(env);
        if (Files.exists(file)) {
            try {
                return normalizeCredential(JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getString("credential"));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read saved credentials for " + env + "; run dubbo-mcp login again.");
            }
        }
        String configured = ConfigManager.getInstance().getProperty("env." + env + ".admin_cookie");
        return configured == null || configured.trim().isEmpty() ? null : normalizeCredential(configured);
    }

    public synchronized void saveCredential(String env, String credential) {
        Path file = sessionFile(env);
        String normalized = normalizeCredential(credential);
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, ".session-", ".tmp");
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            JSONObject data = new JSONObject();
            data.put("env", env); data.put("credential", normalized); data.put("updatedAt", System.currentTimeMillis());
            Files.write(temp, data.toJSONString().getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save credentials for " + env + "; check ~/.dubbo-mcp permissions.");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    public synchronized void clearCredential(String env) {
        try { Files.deleteIfExists(sessionFile(env)); }
        catch (IOException e) { throw new IllegalStateException("Cannot remove credentials for " + env); }
    }
    private Path sessionFile(String env) {
        if (env == null || !env.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid environment name");
        return directory.resolve("session-" + env + ".json");
    }
    public static String normalizeCredential(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Credential is empty");
        String v = value.trim();
        if (v.length() > 65536) throw new IllegalArgumentException("Credential exceeds 64 KiB");
        if (v.startsWith("{")) {
            try {
                JSONObject obj = JSON.parseObject(v);
                String cookie = obj.getString("cookie"), authorization = obj.getString("authorization");
                if ((cookie == null || cookie.isEmpty()) && (authorization == null || authorization.isEmpty()))
                    throw new IllegalArgumentException();
                validateHeader(cookie); validateHeader(authorization);
                return obj.toJSONString();
            } catch (Exception e) { throw new IllegalArgumentException("Expected JSON containing cookie and/or authorization"); }
        }
        validateHeader(v);
        return v;
    }
    private static void validateHeader(String v) {
        if (v != null && (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\0') >= 0))
            throw new IllegalArgumentException("Credential contains invalid header characters");
    }
    public void startKeepAlive(String env, Runnable task) {
        SCHEDULER.scheduleWithFixedDelay(() -> { try { if (getCredential(env) != null) task.run(); } catch (Exception ignored) { } }, 5, 5, TimeUnit.MINUTES);
    }
}
