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
        boolean insecureSsl = config.getBoolean("env." + env + ".admin_insecure_ssl",
                config.getBoolean("security.insecure_ssl", false));
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false);
        if (insecureSsl) {
            configureInsecureSsl(clientBuilder);
        }
        httpClient = clientBuilder.build();
    }

    private static void configureInsecureSsl(OkHttpClient.Builder builder) {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }
            };
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {
        }
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
            String location = response.header("Location");
            String html = response.body() == null ? "" : response.body().string();
            if ((location != null && location.contains("/dubbo-admin/")) || html.contains("/dubbo-admin/")) {
                detectedPrefix = "/dubbo-admin";
            } else {
                detectedPrefix = "";
            }
        } catch (Exception e) {
            // Gracefully default to root context if prefix auto-detection fails
            detectedPrefix = "";
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
            Object resp;
            try {
                resp = request("/services", null, credential());
            } catch (Exception e) {
                // Fallback for Dubbo Admin deployments that do not expose /services, querying /service instead
                resp = request("/service?pattern=service&filter=*", null, credential());
            }
            List<String> services = extractServiceNames(resp);
            String kw = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
            services.removeIf(s -> !s.toLowerCase(Locale.ROOT).contains(kw));
            Collections.sort(services); return services;
        } catch (Exception e) { throw propagate(e); }
    }
    static JSONArray toServiceArray(Object response) {
        if (response instanceof JSONArray) return (JSONArray) response;
        if (response instanceof String) {
            String s = ((String) response).trim();
            if (s.startsWith("[") || s.startsWith("{")) {
                try {
                    return toServiceArray(JSON.parse(s));
                } catch (Exception ignored) {
                }
            }
        }
        if (response instanceof JSONObject) {
            JSONObject obj = (JSONObject) response;
            // 1. Direct pagination/list property matching across Spring Data, MyBatis-Plus, PageHelper
            String[] arrayKeys = {
                "content", "data", "records", "list", "rows", "items",
                "services", "result", "elements", "serviceList", "serviceDTOList"
            };
            for (String key : arrayKeys) {
                if (obj.get(key) instanceof JSONArray) return obj.getJSONArray(key);
            }
            // 2. Standard API envelopes: {"code":200, "data": {...}}, {"result": {...}}, {"page": {...}}
            String[] nestedKeys = {"data", "result", "response", "page", "body", "payload"};
            for (String key : nestedKeys) {
                if (obj.get(key) instanceof JSONObject) {
                    JSONArray nested = toServiceArray(obj.getJSONObject(key));
                    if (nested != null) return nested;
                }
            }
            // 3. Fallback: If there is exactly one JSONArray entry at top level, treat it as the service list
            JSONArray singleCandidate = null;
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    if (singleCandidate == null) {
                        singleCandidate = (JSONArray) entry.getValue();
                    } else {
                        singleCandidate = null;
                        break;
                    }
                }
            }
            if (singleCandidate != null) return singleCandidate;
        }
        return null;
    }

    private static List<String> extractServiceNames(Object parsed) {
        JSONArray array = toServiceArray(parsed);
        if (array == null) throw new IllegalStateException("Unexpected Dubbo Admin services response (expected array or paged envelope)");
        Set<String> names = new LinkedHashSet<>();
        for (Object item : array) {
            String name = null;
            String app = null;
            if (item instanceof String) {
                name = (String) item;
            } else if (item instanceof JSONObject) {
                JSONObject obj = (JSONObject) item;
                name = obj.getString("service");
                if (name == null || (!name.contains(".") && obj.getString("serviceName") != null && obj.getString("serviceName").contains("."))) {
                    name = obj.getString("serviceName");
                }
                if (name == null) name = obj.getString("serviceKey");
                if (name == null) name = obj.getString("interfaceName");
                if (name == null) name = obj.getString("name");
                if (name == null) name = obj.getString("interface");

                app = obj.getString("application");
                if (app == null) app = obj.getString("app");
                if (app == null) app = obj.getString("appName");
            }
            if (name != null && !name.trim().isEmpty()) {
                String cleanName = name.trim();
                if (app != null && !app.trim().isEmpty() && !cleanName.contains(" (app: ")) {
                    cleanName = cleanName + " (app: " + app.trim() + ")";
                }
                names.add(cleanName);
            }
        }
        return new ArrayList<>(names);
    }

    private String serviceKey(String iface, String version, String group, String cred) throws Exception {
        if (iface != null && iface.contains(" (app: ")) {
            iface = iface.split(" \\(app: ")[0].trim();
        }
        // A fully qualified service key can be supplied directly.
        if (iface.contains(":") || iface.contains("/")) {
            if (version != null || group != null) throw new IllegalArgumentException("Use either a full service key or separate version/group");
            return iface;
        }
        ConfigManager cfg = ConfigManager.getInstance();
        if (version == null) version = cfg.getProperty("env." + env + ".version");
        if (group == null) group = cfg.getProperty("env." + env + ".group");

        Set<String> keys = new TreeSet<>();
        // 1. Search via /service?pattern=service&filter=*iface*
        try {
            HttpUrl query = new HttpUrl.Builder().scheme("http").host("localhost").addPathSegment("service")
                    .addQueryParameter("pattern", "service").addQueryParameter("filter", "*" + iface + "*").build();
            Object response = request(query.encodedPath() + "?" + query.encodedQuery(), null, cred);
            JSONArray rows = toServiceArray(response);
            if (rows != null) {
                collectMatchingKeys(rows, iface, version, group, keys);
            }
        } catch (Exception ignored) {
            // Service search endpoint may not be supported or returned an error; continue to fallback
        }

        // 2. Fallback: If no service found via /service query, inspect /services list
        if (keys.isEmpty()) {
            try {
                Object allServicesResp = request("/services", null, cred);
                JSONArray allServices = toServiceArray(allServicesResp);
                if (allServices != null) {
                    collectMatchingKeys(allServices, iface, version, group, keys);
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Fallback: If still not found, but caller provided an explicit version or group, synthesize the key directly
        if (keys.isEmpty()) {
            if (version != null || group != null) {
                return (empty(group).isEmpty() ? "" : group + "/") + iface + (empty(version).isEmpty() ? "" : ":" + version);
            }
            throw new IllegalStateException("No matching Dubbo Admin service: " + iface);
        }
        if (keys.size() != 1) {
            throw new IllegalArgumentException("Multiple service versions/groups found for " + iface + ": " + keys + "; please specify version and group");
        }
        return keys.iterator().next();
    }

    private static void collectMatchingKeys(JSONArray rows, String iface, String version, String group, Set<String> keys) {
        for (Object item : rows) {
            String name = null;
            String rowGroup = null, rowVersion = null;
            if (item instanceof JSONObject) {
                JSONObject row = (JSONObject) item;
                name = row.getString("service");
                if (name == null || (!name.contains(".") && row.getString("serviceName") != null && row.getString("serviceName").contains("."))) {
                    name = row.getString("serviceName");
                }
                if (name == null) name = row.getString("serviceKey");
                if (name == null) name = row.getString("interfaceName");
                if (name == null) name = row.getString("name");
                if (name == null) name = row.getString("interface");
                rowGroup = row.getString("group");
                rowVersion = row.getString("version");
            } else if (item instanceof String) {
                name = (String) item;
            }
            if (name == null) continue;
            int slash = name.indexOf('/'), colon = name.lastIndexOf(':');
            if (slash >= 0) { rowGroup = name.substring(0, slash); name = name.substring(slash + 1); }
            colon = name.lastIndexOf(':');
            if (colon >= 0) { rowVersion = name.substring(colon + 1); name = name.substring(0, colon); }
            if (!iface.equals(name) || (version != null && !version.equals(empty(rowVersion))) || (group != null && !group.equals(empty(rowGroup)))) continue;
            keys.add((empty(rowGroup).isEmpty() ? "" : rowGroup + "/") + name + (empty(rowVersion).isEmpty() ? "" : ":" + rowVersion));
        }
    }
    private static String empty(String s) { return s == null ? "" : s.trim(); }
    private static String detailPath(String key) {
        return new HttpUrl.Builder().scheme("http").host("localhost").addPathSegment("service")
                .addPathSegment(key.replace('/', '*')).build().encodedPath();
    }
    static JSONObject extractMetadata(JSONObject detail) {
        if (detail == null) return null;
        if (detail.get("metadata") instanceof JSONObject) {
            return detail.getJSONObject("metadata");
        }
        if (detail.get("metadata") instanceof String) {
            try {
                return JSON.parseObject(detail.getString("metadata"));
            } catch (Exception ignored) {
            }
        }
        for (String wrapperKey : new String[]{"data", "result", "response"}) {
            if (detail.get(wrapperKey) instanceof JSONObject) {
                JSONObject dataObj = detail.getJSONObject(wrapperKey);
                if (dataObj.get("metadata") instanceof JSONObject) {
                    return dataObj.getJSONObject("metadata");
                }
                if (dataObj.get("metadata") instanceof String) {
                    try {
                        return JSON.parseObject(dataObj.getString("metadata"));
                    } catch (Exception ignored) {
                    }
                }
                if (dataObj.containsKey("methods")) {
                    return dataObj;
                }
            }
        }
        return detail;
    }
    @Override public Map<String, Object> getServiceMetadata(String iface) { return getServiceMetadata(iface, null, null); }
    @Override public Map<String, Object> getServiceMetadata(String iface, String version, String group) {
        try {
            if (iface != null && iface.contains(" (app: ")) {
                iface = iface.split(" \\(app: ")[0].trim();
            }
            String cred = credential(), key = serviceKey(iface, version, group, cred);
            Object detail = request(detailPath(key), null, cred);
            if (!(detail instanceof JSONObject)) throw new IllegalStateException("Unexpected service detail response");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("interface", iface);
            result.put("service", key);
            result.put("details", detail);
            JSONObject metadata = extractMetadata((JSONObject) detail);
            if (metadata == null || !metadata.containsKey("methods") || metadata.getJSONArray("methods") == null || metadata.getJSONArray("methods").isEmpty()) {
                result.put("metadataReport", "ABSENT");
                result.put("_hint", "Service metadata does not contain method signatures. Pass explicit 'paramTypes' when invoking.");
            } else {
                result.put("metadataReport", "AVAILABLE");
            }
            return result;
        } catch (Exception e) { throw propagate(e); }
    }
    @Override public Object invoke(String iface, String method, String[] types, Object[] args) throws Exception {
        return invoke(iface, method, types, args, null, null, null);
    }
    @Override public Object invoke(String iface, String method, String[] types, Object[] args, String version, String group) throws Exception {
        return invoke(iface, method, types, args, version, group, null);
    }
    @Override public Object invoke(String iface, String method, String[] types, Object[] args, String version, String group, Map<String, String> attachments) throws Exception {
        if (iface != null && iface.contains(" (app: ")) {
            iface = iface.split(" \\(app: ")[0].trim();
        }
        String cred = credential(), key = serviceKey(iface, version, group, cred);
        Object[] values = args == null ? new Object[0] : args.clone();
        JSONObject detail = (JSONObject) request(detailPath(key), null, cred);
        JSONObject metadata = extractMetadata(detail);
        types = MethodSignatures.resolve(metadata == null ? null : metadata.getJSONArray("methods"), method, types, values.length);
        for (int i = 0; i < values.length; i++) {
            if (types != null && i < types.length) {
                values[i] = TypeCoercion.coerce(values[i], types[i]);
            }
        }
        JSONObject payload = new JSONObject();
        payload.put("service", key);
        payload.put("method", method);
        payload.put("parameterTypes", types);
        payload.put("params", values);
        if (attachments != null && !attachments.isEmpty()) {
            payload.put("attachments", attachments);
        }
        // Never retry a test invocation: a timeout can occur after a mutation has completed.
        try {
            return request("/test", payload, cred);
        } catch (Exception e) {
            throw enhanceAdminException(e, iface, method);
        }
    }

    static Exception enhanceAdminException(Exception e, String iface, String method) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("Available methods:") || msg.contains("Not found method") || msg.contains("NoSuchMethodException"))) {
            int idx = msg.indexOf("Available methods:");
            String available = idx >= 0 ? msg.substring(idx) : msg;
            return new IllegalArgumentException("Dubbo method signature mismatch for " + iface + "." + method +
                    ". " + available + ". Specify 'paramTypes' matching one of the available signatures.", e);
        }
        return e;
    }
    private static RuntimeException propagate(Exception e) { return e instanceof RuntimeException ? (RuntimeException)e : new IllegalStateException(e.getMessage()); }
}
