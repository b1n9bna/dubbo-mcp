package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.spi.RpcDriver;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.service.GenericService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct Dubbo RPC driver:
 * 1. Uses pure Java 8 programmatic Dubbo 2.7.3 API (GenericService).
 * 2. Caches ReferenceConfig instances for sub-millisecond repeated invocations.
 * 3. Inspects ZooKeeper directly for service discovery and method listing.
 */
public class DubboDirectDriver implements RpcDriver {

    private final String env;
    private final String registryUrl;
    private final int timeout;
    private final ApplicationConfig applicationConfig;
    private final RegistryConfig registryConfig;

    private final Map<String, ReferenceConfig<GenericService>> referenceCache = new ConcurrentHashMap<>();
    private final Map<String, GenericService> genericServiceCache = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> metadataCache = new ConcurrentHashMap<>();
    private CuratorFramework curatorClient;

    public DubboDirectDriver(String env) {
        this.env = env;
        ConfigManager config = ConfigManager.getInstance();
        this.registryUrl = config.getProperty("env." + env + ".registry", "zookeeper://127.0.0.1:2181");
        this.timeout = config.getInt("env." + env + ".timeout", 5000);

        this.applicationConfig = new ApplicationConfig("dubbo-mcp-client");
        this.applicationConfig.setQosEnable(false);
        this.registryConfig = new RegistryConfig(registryUrl);
        this.registryConfig.setRegister(false);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "dubbo-mcp-close-" + env));
    }

    @Override
    public String getType() {
        return "zookeeper";
    }

    @Override
    public Object invoke(String interfaceName, String method, String[] paramTypes, Object[] args) throws Exception {
        return invoke(interfaceName, method, paramTypes, args, null, null);
    }

    @Override
    public Object invoke(String interfaceName, String method, String[] paramTypes, Object[] args, String version, String group) throws Exception {
        // Resolve version and group: explicit -> config default -> auto-detect from ZK
        String[] service = resolveService(interfaceName, version, group);
        String resolvedVersion = service[0], resolvedGroup = service[1];

        // Resolve paramTypes: auto-resolve from ZK metadata if paramTypes has generic fallback types (java.util.Map, java.lang.Object, or empty)
        String[] resolvedParamTypes = resolveParamTypes(interfaceName, method, paramTypes, args, resolvedVersion, resolvedGroup);

        GenericService genericService = getOrCreateGenericService(interfaceName, resolvedVersion, resolvedGroup);
        if (args == null) {
            args = new Object[0];
        }
        return genericService.$invoke(method, resolvedParamTypes, args);
    }

    private String[] resolveParamTypes(String interfaceName, String method, String[] paramTypes, Object[] args, String version, String group) {
        JSONObject metadata = paramTypes == null ? fetchServiceMetadata(interfaceName, version, group) : null;
        return MethodSignatures.resolve(metadata == null ? null : metadata.getJSONArray("methods"), method, paramTypes, args == null ? 0 : args.length);
    }

    private JSONObject fetchServiceMetadata(String interfaceName, String version, String group) {
        String cacheKey = interfaceName + ":" + (version != null ? version : "") + ":" + (group != null ? group : "");
        if (metadataCache.containsKey(cacheKey)) {
            return metadataCache.get(cacheKey);
        }

        CuratorFramework client = getCuratorClient();
        if (client == null) return null;

        try {
            String basePath = "/dubbo/metadata/" + interfaceName;
            if (client.checkExists().forPath(basePath) == null) {
                return null;
            }

            List<String> versionNodes = client.getChildren().forPath(basePath);
            if (versionNodes == null || versionNodes.isEmpty()) {
                return null;
            }

            String targetVer = null;
            if (version != null && versionNodes.contains(version.trim())) {
                targetVer = version.trim();
            } else if (version == null && versionNodes.size() == 1) {
                targetVer = versionNodes.get(0);
            } else {
                return null; // Never infer a DTO from another service version.
            }

            String providerPath = basePath + "/" + targetVer + (group == null || group.isEmpty() ? "" : "/" + group) + "/provider";
            if (client.checkExists().forPath(providerPath) == null) return null;

            List<String> appNodes = client.getChildren().forPath(providerPath);
            if (appNodes != null && !appNodes.isEmpty()) {
                for (String app : appNodes) {
                    String fullPath = providerPath + "/" + app;
                    byte[] data = client.getData().forPath(fullPath);
                    if (data != null && data.length > 0) {
                        String jsonStr = new String(data, StandardCharsets.UTF_8);
                        JSONObject parsed = JSON.parseObject(jsonStr);
                        if (parsed != null) {
                            metadataCache.put(cacheKey, parsed);
                            return parsed;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DubboDirectDriver] Could not load ZK metadata for " + interfaceName + ": " + e.getMessage());
        }
        return null;
    }

    private GenericService getOrCreateGenericService(String interfaceName, String version, String group) {
        String cacheKey = interfaceName + ":" + (group != null ? group : "") + ":" + (version != null ? version : "");
        return genericServiceCache.computeIfAbsent(cacheKey, key -> {
            ReferenceConfig<GenericService> reference = new ReferenceConfig<>();
            reference.setApplication(applicationConfig);
            reference.setRegistry(registryConfig);
            reference.setInterface(interfaceName);
            reference.setGeneric("true"); // Enable generic invocation (no interface jars needed)
            reference.setTimeout(timeout);
            reference.setRetries(0);
            reference.setCheck(false);    // Do not fail immediately on startup

            if (version != null && !version.trim().isEmpty()) {
                reference.setVersion(version.trim());
            }
            if (group != null && !group.trim().isEmpty()) {
                reference.setGroup(group.trim());
            }

            try {
                GenericService service = reference.get();
                referenceCache.put(key, reference);
                return service;
            } catch (RuntimeException e) { reference.destroy(); throw e; }
        });
    }

    private String[] resolveService(String iface, String version, String group) {
        ConfigManager cfg = ConfigManager.getInstance();
        if (version == null) version = cfg.getProperty("env." + env + ".version");
        if (group == null) group = cfg.getProperty("env." + env + ".group");
        Set<String> candidates = new TreeSet<>();
        try {
            CuratorFramework client = getCuratorClient();
            String path = "/dubbo/" + iface + "/providers";
            if (client.checkExists().forPath(path) != null) for (String node : client.getChildren().forPath(path)) {
                URL url = URL.valueOf(URLDecoder.decode(node, "UTF-8"));
                if (!url.getParameter("enabled", true) || url.getParameter("disabled", false)) continue;
                String v = url.getParameter("version", ""), g = url.getParameter("group", "");
                if ((version == null || version.trim().equals(v)) && (group == null || group.trim().equals(g)))
                    candidates.add(v + "\t" + g);
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot discover providers for " + iface, e); }
        return selectService(candidates, iface);
    }

    static String[] selectService(Set<String> candidates, String iface) {
        if (candidates.isEmpty()) throw new IllegalStateException("No matching providers for " + iface);
        if (candidates.size() == 1) return candidates.iterator().next().split("\t", -1);
        List<String> defaultGroup = new ArrayList<>();
        for (String c : candidates) {
            String[] p = c.split("\t", -1);
            if (p.length > 1 && p[1].isEmpty()) defaultGroup.add(c);
        }
        if (defaultGroup.size() == 1) return defaultGroup.get(0).split("\t", -1);
        throw new IllegalArgumentException("Multiple provider versions/groups for " + iface + ": " + candidates + "; specify version and group");
    }

    @Override
    public List<String> listServices(String keyword) {
        List<String> results = new ArrayList<>();
        CuratorFramework client = getCuratorClient();
        if (client == null) {
            return results;
        }

        try {
            if (client.checkExists().forPath("/dubbo") != null) {
                List<String> children = client.getChildren().forPath("/dubbo");
                String lowerKeyword = keyword != null ? keyword.toLowerCase() : "";
                for (String serviceName : children) {
                    if (serviceName.contains(".") && (lowerKeyword.isEmpty() || serviceName.toLowerCase(Locale.ROOT).contains(lowerKeyword))) {
                        results.add(serviceName);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("ZooKeeper service query failed", e);
        }
        Collections.sort(results);
        return results;
    }

    @Override
    public Map<String, Object> getServiceMetadata(String interfaceName) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("interface", interfaceName);

        CuratorFramework client = getCuratorClient();
        if (client == null) {
            meta.put("methods", Collections.emptyList());
            return meta;
        }

        Set<String> methodNames = new TreeSet<>();
        Set<String> versions = new TreeSet<>();
        Set<String> groups = new TreeSet<>();
        Set<String> applications = new TreeSet<>();
        List<String> providerUrls = new ArrayList<>();

        try {
            String providersPath = "/dubbo/" + interfaceName + "/providers";
            if (client.checkExists().forPath(providersPath) != null) {
                List<String> children = client.getChildren().forPath(providersPath);
                for (String rawUrl : children) {
                    String decodedUrl = URLDecoder.decode(rawUrl, "UTF-8");
                    providerUrls.add(decodedUrl);
                    URL url = URL.valueOf(decodedUrl);
                    String methodsParam = url.getParameter("methods");
                    if (methodsParam != null && !methodsParam.isEmpty()) {
                        String[] methods = methodsParam.split(",");
                        for (String m : methods) {
                            methodNames.add(m.trim());
                        }
                    }
                    String ver = url.getParameter("version");
                    if (ver != null && !ver.isEmpty()) {
                        versions.add(ver.trim());
                    }
                    String grp = url.getParameter("group");
                    if (grp != null && !grp.isEmpty()) {
                        groups.add(grp.trim());
                    }
                    String app = url.getParameter("application");
                    if (app != null && !app.isEmpty()) {
                        applications.add(app.trim());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("ZooKeeper provider query failed for " + interfaceName, e);
        }

        meta.put("methods", new ArrayList<>(methodNames));
        meta.put("versions", new ArrayList<>(versions));
        meta.put("groups", new ArrayList<>(groups));
        meta.put("applications", new ArrayList<>(applications));
        meta.put("providerCount", providerUrls.size());

        // Enrich with detailed ZooKeeper metadata report if available
        JSONObject metaReport = versions.size() <= 1 && groups.size() <= 1
                ? fetchServiceMetadata(interfaceName, versions.isEmpty() ? null : versions.iterator().next(), groups.isEmpty() ? null : groups.iterator().next()) : null;
        if (metaReport != null) {
            if (metaReport.containsKey("methods")) {
                JSONArray mArr = metaReport.getJSONArray("methods");
                List<Map<String, Object>> methodDetails = new ArrayList<>();
                for (int i = 0; i < mArr.size(); i++) {
                    JSONObject mObj = mArr.getJSONObject(i);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("name", mObj.getString("name"));
                    detail.put("parameterTypes", mObj.getJSONArray("parameterTypes"));
                    detail.put("returnType", mObj.getString("returnType"));
                    methodDetails.add(detail);
                }
                meta.put("methodDetails", methodDetails);
            }
            if (metaReport.containsKey("types")) {
                JSONArray tArr = metaReport.getJSONArray("types");
                Map<String, Object> typeMap = new LinkedHashMap<>();
                for (int i = 0; i < tArr.size(); i++) {
                    JSONObject tObj = tArr.getJSONObject(i);
                    String tName = tObj.getString("type");
                    JSONObject props = tObj.getJSONObject("properties");
                    if (tName != null && props != null && !props.isEmpty()) {
                        typeMap.put(tName, props.keySet());
                    }
                }
                if (!typeMap.isEmpty()) {
                    meta.put("typeProperties", typeMap);
                }
            }
        }
        return meta;
    }

    @Override public Map<String, Object> getServiceMetadata(String iface, String version, String group) {
        if (version == null && group == null) return getServiceMetadata(iface);
        String[] identity = resolveService(iface, version, group);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("interface", iface); result.put("version", identity[0]); result.put("group", identity[1]);
        JSONObject details = fetchServiceMetadata(iface, identity[0], identity[1]);
        if (details != null) result.put("metadata", details);
        return result;
    }

    private synchronized CuratorFramework getCuratorClient() {
        if (curatorClient != null) {
            return curatorClient;
        }

        if (registryUrl.startsWith("zookeeper://")) {
            String zkAddress = registryUrl.substring("zookeeper://".length()).split("[/?]", 2)[0];
            try {
                curatorClient = CuratorFrameworkFactory.builder()
                        .connectString(zkAddress)
                        .sessionTimeoutMs(timeout)
                        .connectionTimeoutMs(timeout)
                        .retryPolicy(new RetryOneTime(100))
                        .build();
                curatorClient.start();
                int connectTimeout = Math.min(timeout, 10000);
                if (!curatorClient.blockUntilConnected(connectTimeout, TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("ZooKeeper connection timed out after " + connectTimeout + "ms");
            } catch (Exception e) {
                if (curatorClient != null) { curatorClient.close(); curatorClient = null; }
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Cannot connect to ZooKeeper for " + env, e);
            }
        }
        if (curatorClient == null) throw new IllegalStateException("Unsupported registry URL for " + env);
        return curatorClient;
    }

    public synchronized void close() {
        for (ReferenceConfig<GenericService> reference : referenceCache.values()) reference.destroy();
        referenceCache.clear(); genericServiceCache.clear(); metadataCache.clear();
        if (curatorClient != null) { curatorClient.close(); curatorClient = null; }
    }
}
