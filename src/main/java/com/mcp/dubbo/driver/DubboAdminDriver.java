package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.auth.AuthSessionManager;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.spi.RpcDriver;
import okhttp3.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Dubbo Admin API bridge. HTTP/auth/schema errors must never look like empty query results. */
public class DubboAdminDriver implements RpcDriver {
    private final String env, adminUrl;
    private final OkHttpClient httpClient;
    private final AuthSessionManager sessions = AuthSessionManager.getInstance();
    private volatile String detectedPrefix;

    public DubboAdminDriver(String env) {
        this.env = env;
        ConfigManager config = ConfigManager.getInstance();
        String url = config.getProperty("env." + env + ".admin_url");
        if (url == null || HttpUrl.parse(url) == null) throw new IllegalArgumentException("Invalid admin_url for " + env);
        this.adminUrl = url.replaceAll("/+$", "");
        String prefix = config.getProperty("env." + env + ".admin_api_prefix");
        if (prefix != null) detectedPrefix = prefix.replaceAll("/+$", "");
        if (adminUrl.endsWith("/dubbo-admin")) detectedPrefix = "";
        httpClient = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build();
    }
    @Override public String getType() { return "dubbo-admin"; }

    public void verifyCredential(String credential) throws Exception {
        Object result = request("/services", null, credential);
        extractServiceNames(result); // A successful HTML login page is not a successful login.
    }

    private String credential() throws Exception {
        String saved = sessions.getCredential(env);
        if (saved != null) return saved;
        ConfigManager cfg = ConfigManager.getInstance();
        String user = cfg.getProperty("env." + env + ".admin_username");
        String password = cfg.getProperty("env." + env + ".admin_password");
        if (user != null && !user.isEmpty() && password != null && !password.isEmpty()) {
            // The deployed Admin uses GET /user/login and returns a raw token.
            String query = new HttpUrl.Builder().scheme("http").host("localhost").addPathSegment("user")
                    .addPathSegment("login").addQueryParameter("userName", user).addQueryParameter("password", password)
                    .build().encodedPath() + "?" + new HttpUrl.Builder().scheme("http").host("localhost")
                    .addQueryParameter("userName", user).addQueryParameter("password", password).build().encodedQuery();
            String prefix = detectedPrefix;
            if (prefix == null) { detectPrefixFromPage(); prefix = detectedPrefix; }
            Request req = new Request.Builder().url(adminUrl + prefix + "/api/dev" + query).get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Dubbo Admin password login failed (HTTP " + resp.code() + ")");
                String token = resp.body().string().trim();
                if (token.startsWith("\"")) token = JSON.parseObject(token, String.class);
                if (token.isEmpty() || token.startsWith("<") || token.startsWith("{")) throw new IOException("Unexpected password login response");
                JSONObject cred = new JSONObject(); cred.put("authorization", token);
                verifyCredential(cred.toJSONString());
                sessions.saveCredential(env, cred.toJSONString()); return cred.toJSONString();
            }
        }
        throw new IllegalStateException("No credentials for " + env + ". Run dubbo-mcp login --env=" + env
                + " --clipboard (macOS), copying cookie AND localStorage token as shown by login.");
    }

    private synchronized void detectPrefixFromPage() throws IOException {
        if (detectedPrefix != null) return;
        try (Response response = httpClient.newCall(new Request.Builder().url(adminUrl + "/").get().build()).execute()) {
            String html = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IOException("Cannot detect Admin context; set admin_api_prefix explicitly");
            detectedPrefix = html.contains("/dubbo-admin/") ? "/dubbo-admin" : "";
        }
    }

    private Object request(String path, JSONObject payload, String credential) throws Exception {
        if (detectedPrefix == null) detectPrefixFromPage();
        Request.Builder builder = new Request.Builder().url(adminUrl + detectedPrefix + "/api/dev" + path);
        if (payload == null) builder.get();
        else builder.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), payload.toJSONString()));
        attachCredential(builder, credential);
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            int code = response.code();
            if (code == 401 || code == 403 || (code >= 300 && code < 400))
                throw new IllegalStateException("Dubbo Admin authentication failed (HTTP " + code + ") for " + env
                        + "; log in again with cookie AND localStorage token. Run dubbo-mcp login --env=" + env + " --clipboard");
            if (!response.isSuccessful()) throw new IOException("Dubbo Admin API returned HTTP " + code + " for " + path.split("\\?")[0]);
            String body = response.body() == null ? "" : response.body().string().trim();
            if (body.startsWith("<") || body.isEmpty()) throw new IOException("Dubbo Admin returned HTML/empty response; check authentication and admin_api_prefix");
            try { return JSON.parse(body); }
            catch (Exception e) { throw new IOException("Dubbo Admin returned invalid JSON"); }
        } catch (java.io.InterruptedIOException e) {
            throw new IOException("Dubbo Admin request timed out; check network and configured endpoint");
        }
    }

    static void attachCredential(Request.Builder builder, String value) {
        value = AuthSessionManager.normalizeCredential(value);
        if (value.startsWith("{")) {
            JSONObject obj = JSON.parseObject(value);
            if (obj.getString("cookie") != null && !obj.getString("cookie").isEmpty()) builder.header("Cookie", obj.getString("cookie"));
            if (obj.getString("authorization") != null && !obj.getString("authorization").isEmpty()) builder.header("Authorization", obj.getString("authorization"));
        } else if (value.startsWith("Authorization:")) builder.header("Authorization", value.substring(14).trim());
        else if (value.startsWith("Bearer ") || value.startsWith("Token ") || !value.contains("=")) builder.header("Authorization", value);
        else builder.header("Cookie", value);
    }

    @Override public List<String> listServices(String keyword) {
        try {
            List<String> services = extractServiceNames(request("/services", null, credential()));
            String kw = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
            services.removeIf(s -> !s.toLowerCase(Locale.ROOT).contains(kw));
            Collections.sort(services); return services;
        } catch (Exception e) { throw propagate(e); }
    }
    private static List<String> extractServiceNames(Object parsed) {
        if (!(parsed instanceof JSONArray)) throw new IllegalStateException("Unexpected Dubbo Admin services response (expected array)");
        Set<String> names = new LinkedHashSet<>();
        for (Object item : (JSONArray) parsed) {
            String name = item instanceof String ? (String) item : item instanceof JSONObject ? ((JSONObject)item).getString("service") : null;
            if (name == null) throw new IllegalStateException("Unexpected service entry in Dubbo Admin response");
            names.add(name);
        }
        return new ArrayList<>(names);
    }

    private String serviceKey(String iface, String version, String group, String cred) throws Exception {
        // A fully qualified service key can be supplied directly.
        if (iface.contains(":") || iface.contains("/")) {
            if (version != null || group != null) throw new IllegalArgumentException("Use either a full service key or separate version/group");
            return iface;
        }
        ConfigManager cfg = ConfigManager.getInstance();
        if (version == null) version = cfg.getProperty("env." + env + ".version");
        if (group == null) group = cfg.getProperty("env." + env + ".group");
        HttpUrl query = new HttpUrl.Builder().scheme("http").host("localhost").addPathSegment("service")
                .addQueryParameter("pattern", "service").addQueryParameter("filter", "*" + iface + "*").build();
        Object response = request(query.encodedPath() + "?" + query.encodedQuery(), null, cred);
        if (!(response instanceof JSONArray)) throw new IllegalStateException("Unexpected Dubbo Admin service search response");
        Set<String> keys = new TreeSet<>();
        for (Object item : (JSONArray)response) {
            if (!(item instanceof JSONObject)) continue;
            JSONObject row = (JSONObject)item;
            String name = row.getString("service");
            if (name == null) continue;
            String rowGroup = row.getString("group"), rowVersion = row.getString("version");
            int slash = name.indexOf('/'), colon = name.lastIndexOf(':');
            if (slash >= 0) { rowGroup = name.substring(0, slash); name = name.substring(slash + 1); }
            colon = name.lastIndexOf(':');
            if (colon >= 0) { rowVersion = name.substring(colon + 1); name = name.substring(0, colon); }
            if (!iface.equals(name) || (version != null && !version.equals(empty(rowVersion))) || (group != null && !group.equals(empty(rowGroup)))) continue;
            keys.add((empty(rowGroup).isEmpty() ? "" : rowGroup + "/") + name + (empty(rowVersion).isEmpty() ? "" : ":" + rowVersion));
        }
        if (keys.isEmpty()) throw new IllegalStateException("No matching Dubbo Admin service: " + iface);
        if (keys.size() != 1) throw new IllegalArgumentException("Multiple service versions/groups: " + keys + "; specify version and group");
        return keys.iterator().next();
    }
    private static String empty(String s) { return s == null ? "" : s.trim(); }
    private static String detailPath(String key) {
        return new HttpUrl.Builder().scheme("http").host("localhost").addPathSegment("service")
                .addPathSegment(key.replace('/', '*')).build().encodedPath();
    }
    @Override public Map<String, Object> getServiceMetadata(String iface) { return getServiceMetadata(iface, null, null); }
    @Override public Map<String, Object> getServiceMetadata(String iface, String version, String group) {
        try {
            String cred = credential(), key = serviceKey(iface, version, group, cred);
            Object detail = request(detailPath(key), null, cred);
            if (!(detail instanceof JSONObject)) throw new IllegalStateException("Unexpected service detail response");
            Map<String, Object> result = new LinkedHashMap<>(); result.put("interface", iface); result.put("service", key); result.put("details", detail);
            return result;
        } catch (Exception e) { throw propagate(e); }
    }
    @Override public Object invoke(String iface, String method, String[] types, Object[] args) throws Exception { return invoke(iface, method, types, args, null, null); }
    @Override public Object invoke(String iface, String method, String[] types, Object[] args, String version, String group) throws Exception {
        String cred = credential(), key = serviceKey(iface, version, group, cred);
        Object[] values = args == null ? new Object[0] : args;
        JSONObject detail = (JSONObject) request(detailPath(key), null, cred);
        JSONObject metadata = detail.getJSONObject("metadata");
        types = MethodSignatures.resolve(metadata == null ? null : metadata.getJSONArray("methods"), method, types, values.length);
        JSONObject payload = new JSONObject(); payload.put("service", key); payload.put("method", method);
        payload.put("parameterTypes", types); payload.put("params", values);
        // Never retry a test invocation: a timeout can occur after a mutation has completed.
        return request("/test", payload, cred);
    }
    private static RuntimeException propagate(Exception e) { return e instanceof RuntimeException ? (RuntimeException)e : new IllegalStateException(e.getMessage()); }
}
