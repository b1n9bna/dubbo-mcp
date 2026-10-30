package com.mcp.dubbo.auth;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages enterprise dubbo-admin credentials (Cookie/Token), local session persistence,
 * and background keep-alive heartbeats.
 */
public class AuthSessionManager {

    private static final String SESSION_DIR = System.getProperty("user.home") + "/.dubbo-mcp";
    private static final Map<String, String> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dubbo-mcp-keepalive");
        t.setDaemon(true);
        return t;
    });

    private static volatile AuthSessionManager instance;

    private AuthSessionManager() {
        File dir = new File(SESSION_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static AuthSessionManager getInstance() {
        if (instance == null) {
            synchronized (AuthSessionManager.class) {
                if (instance == null) {
                    instance = new AuthSessionManager();
                }
            }
        }
        return instance;
    }

    public synchronized String getCredential(String env) {
        // 0. Check config properties for static cookie (e.g. env.prod.admin_cookie)
        String staticCookie = com.mcp.dubbo.config.ConfigManager.getInstance().getProperty("env." + env + ".admin_cookie");
        if (staticCookie != null && !staticCookie.trim().isEmpty()) {
            return staticCookie.trim();
        }

        // 1. Check memory
        String cred = MEMORY_CACHE.get(env);
        if (cred != null && !cred.trim().isEmpty()) {
            return cred;
        }

        // 2. Check disk file ~/.dubbo-mcp/session-{env}.json
        File sessionFile = getSessionFile(env);
        if (sessionFile.exists() && sessionFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(sessionFile)) {
                byte[] data = new byte[(int) sessionFile.length()];
                int read = fis.read(data);
                if (read > 0) {
                    JSONObject obj = JSON.parseObject(new String(data, 0, read, StandardCharsets.UTF_8));
                    if (obj != null) {
                        String savedCred = obj.getString("credential");
                        if (savedCred != null && !savedCred.trim().isEmpty()) {
                            MEMORY_CACHE.put(env, savedCred);
                            return savedCred;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[AuthSessionManager] Error loading session for env '" + env + "': " + e.getMessage());
            }
        }
        return null;
    }

    public synchronized void saveCredential(String env, String credential) {
        if (credential == null || credential.trim().isEmpty()) {
            return;
        }
        MEMORY_CACHE.put(env, credential);

        File sessionFile = getSessionFile(env);
        try (FileOutputStream fos = new FileOutputStream(sessionFile)) {
            JSONObject obj = new JSONObject();
            obj.put("env", env);
            obj.put("credential", credential);
            obj.put("updatedAt", System.currentTimeMillis());
            fos.write(obj.toJSONString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[AuthSessionManager] Error saving session to disk: " + e.getMessage());
        }
    }

    public synchronized void clearCredential(String env) {
        MEMORY_CACHE.remove(env);
        File file = getSessionFile(env);
        if (file.exists()) {
            file.delete();
        }
    }

    public void startKeepAlive(String env, Runnable pingTask) {
        // Run heartbeat every 5 minutes
        SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                if (getCredential(env) != null) {
                    pingTask.run();
                }
            } catch (Exception e) {
                System.err.println("[AuthSessionManager] Heartbeat ping failed for " + env + ": " + e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    private File getSessionFile(String env) {
        return new File(SESSION_DIR + "/session-" + env + ".json");
    }
}
