package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.auth.AuthSessionManager;
import com.mcp.dubbo.auth.LoopbackBrowserAuth;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.spi.RpcDriver;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Dubbo-Admin HTTP Bridge Driver:
 * Connects to enterprise dubbo-admin REST APIs using OkHttp.
 * Bypasses network isolation, ZK ports, and raw Dubbo TCP firewalls.
 */
public class DubboAdminDriver implements RpcDriver {

    private final String env;
    private final String adminUrl;
    private final OkHttpClient httpClient;
    private final LoopbackBrowserAuth browserAuth = new LoopbackBrowserAuth();
    private final AuthSessionManager sessionManager = AuthSessionManager.getInstance();

    public DubboAdminDriver(String env) {
        this.env = env;
        ConfigManager config = ConfigManager.getInstance();
        String url = config.getProperty("env." + env + ".admin_url", "http://127.0.0.1:8080");
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.adminUrl = url;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false) // Do not auto-follow 302 to SSO login pages
                .build();

        // Start background keep-alive heartbeat every 5 minutes
        sessionManager.startKeepAlive(env, this::pingAdmin);
    }

    @Override
    public String getType() {
        return "dubbo-admin";
    }

    @Override
    public Object invoke(String interfaceName, String method, String[] paramTypes, Object[] args) throws Exception {
        return executeWithAuthRetry(() -> {
            String credential = ensureCredential();

            JSONObject payload = new JSONObject();
            payload.put("service", interfaceName);
            payload.put("method", method);
            payload.put("parameterTypes", paramTypes != null ? Arrays.asList(paramTypes) : Collections.emptyList());
            payload.put("params", args != null ? Arrays.asList(args) : Collections.emptyList());

            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    payload.toJSONString()
            );

            // Standard Dubbo-Admin test invocation endpoint
            String apiUrl = adminUrl + "/api/dev/test";

            Request.Builder reqBuilder = new Request.Builder()
                    .url(apiUrl)
                    .post(body);
            attachCredential(reqBuilder, credential);

            try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                if (response.code() == 401 || response.code() == 302) {
                    throw new SessionExpiredException("dubbo-admin session expired (HTTP " + response.code() + ")");
                }
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    throw new IOException("dubbo-admin API returned HTTP " + response.code() + ": " + errBody);
                }
                String respJson = response.body() != null ? response.body().string() : "{}";
                return JSON.parse(respJson);
            }
        });
    }

    @Override
    public List<String> listServices(String keyword) {
        List<String> result = new ArrayList<>();
        try {
            executeWithAuthRetry(() -> {
                String credential = ensureCredential();
                String apiUrl = adminUrl + "/api/dev/services";

                Request.Builder reqBuilder = new Request.Builder().url(apiUrl).get();
                attachCredential(reqBuilder, credential);

                try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                    if (response.code() == 401 || response.code() == 302) {
                        throw new SessionExpiredException("dubbo-admin session expired");
                    }
                    if (response.isSuccessful() && response.body() != null) {
                        String bodyStr = response.body().string();
                        Object parsed = JSON.parse(bodyStr);
                        List<String> services = extractServiceNames(parsed);
                        String lowerKw = keyword != null ? keyword.toLowerCase() : "";
                        for (String s : services) {
                            if (lowerKw.isEmpty() || s.toLowerCase().contains(lowerKw)) {
                                result.add(s);
                            }
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            System.err.println("[DubboAdminDriver] Error querying services from dubbo-admin: " + e.getMessage());
        }
        Collections.sort(result);
        return result;
    }

    @Override
    public Map<String, Object> getServiceMetadata(String interfaceName) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("interface", interfaceName);

        try {
            executeWithAuthRetry(() -> {
                String credential = ensureCredential();
                String apiUrl = adminUrl + "/api/dev/service/" + interfaceName + "/testMethod";

                Request.Builder reqBuilder = new Request.Builder().url(apiUrl).get();
                attachCredential(reqBuilder, credential);

                try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                    if (response.code() == 401 || response.code() == 302) {
                        throw new SessionExpiredException("dubbo-admin session expired");
                    }
                    if (response.isSuccessful() && response.body() != null) {
                        meta.put("details", JSON.parse(response.body().string()));
                    }
                }
                return null;
            });
        } catch (Exception e) {
            System.err.println("[DubboAdminDriver] Error fetching metadata from dubbo-admin: " + e.getMessage());
        }
        return meta;
    }

    private void attachCredential(Request.Builder builder, String credential) {
        if (credential == null || credential.trim().isEmpty()) {
            return;
        }
        if (credential.startsWith("Bearer ") || credential.startsWith("Token ")) {
            builder.header("Authorization", credential);
        } else {
            builder.header("Cookie", credential);
        }
    }

    private String ensureCredential() throws Exception {
        String existing = sessionManager.getCredential(env);
        if (existing != null && !existing.trim().isEmpty()) {
            return existing;
        }

        // Try username/password auto-login if configured
        ConfigManager cfg = ConfigManager.getInstance();
        String username = cfg.getProperty("env." + env + ".admin_username");
        String password = cfg.getProperty("env." + env + ".admin_password");
        if (username != null && !username.trim().isEmpty() && password != null && !password.trim().isEmpty()) {
            String loggedInCred = tryPasswordLogin(username.trim(), password.trim());
            if (loggedInCred != null && !loggedInCred.trim().isEmpty()) {
                sessionManager.saveCredential(env, loggedInCred);
                return loggedInCred;
            }
        }

        return browserAuth.getValidCredential(env, adminUrl);
    }

    private String tryPasswordLogin(String username, String password) {
        try {
            JSONObject loginJson = new JSONObject();
            loginJson.put("userName", username);
            loginJson.put("password", password);
            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    loginJson.toJSONString()
            );
            Request req = new Request.Builder()
                    .url(adminUrl + "/api/dev/login")
                    .post(body)
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    String setCookie = resp.header("Set-Cookie");
                    String respStr = resp.body() != null ? resp.body().string() : "";
                    JSONObject resObj = JSON.parseObject(respStr);
                    String token = resObj != null ? resObj.getString("token") : null;
                    if (token != null && !token.isEmpty()) {
                        return "Bearer " + token;
                    }
                    if (setCookie != null && !setCookie.isEmpty()) {
                        return setCookie;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DubboAdminDriver] Auto-login with username/password failed: " + e.getMessage());
        }
        return null;
    }

    private void pingAdmin() {
        try {
            String cred = sessionManager.getCredential(env);
            if (cred == null) return;
            Request.Builder b = new Request.Builder().url(adminUrl + "/api/dev/version").get();
            attachCredential(b, cred);
            try (Response resp = httpClient.newCall(b.build()).execute()) {
                if (resp.code() == 401 || resp.code() == 302) {
                    System.err.println("[DubboAdminDriver] Keep-alive detected expired session for " + env);
                    sessionManager.clearCredential(env);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> extractServiceNames(Object parsed) {
        List<String> list = new ArrayList<>();
        if (parsed instanceof JSONArray) {
            JSONArray arr = (JSONArray) parsed;
            for (Object item : arr) {
                if (item instanceof String) {
                    list.add((String) item);
                } else if (item instanceof JSONObject) {
                    String service = ((JSONObject) item).getString("service");
                    if (service != null) list.add(service);
                }
            }
        }
        return list;
    }

    private <T> T executeWithAuthRetry(Action<T> action) throws Exception {
        try {
            return action.run();
        } catch (SessionExpiredException e) {
            System.err.println("[DubboAdminDriver] " + e.getMessage() + ". Clearing credentials and re-authenticating...");
            sessionManager.clearCredential(env);
            // Retry once with new authentication
            return action.run();
        }
    }

    private interface Action<T> {
        T run() throws Exception;
    }

    private static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException(String msg) {
            super(msg);
        }
    }
}
