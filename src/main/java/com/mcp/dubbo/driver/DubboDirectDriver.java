package com.mcp.dubbo.driver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.spi.RpcDriver;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
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

    private final Map<String, GenericService> genericServiceCache = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> metadataCache = new ConcurrentHashMap<>();
    private CuratorFramework curatorClient;

    public DubboDirectDriver(String env) {
        this.env = env;
        ConfigManager config = ConfigManager.getInstance();
        this.registryUrl = config.getProperty("env." + env + ".registry", "zookeeper://127.0.0.1:2181");
        this.timeout = config.getInt("env." + env + ".timeout", 5000);

        this.applicationConfig = new ApplicationConfig("dubbo-mcp-client");
        this.registryConfig = new RegistryConfig(registryUrl);
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
        String resolvedVersion = resolveVersion(interfaceName, version);
        String resolvedGroup = resolveGroup(interfaceName, group);

        // Resolve paramTypes: auto-resolve from ZK metadata if paramTypes has generic fallback types (java.util.Map, java.lang.Object, or empty)
        String[] resolvedParamTypes = resolveParamTypes(interfaceName, method, paramTypes, args, resolvedVersion);

        GenericService genericService = getOrCreateGenericService(interfaceName, resolvedVersion, resolvedGroup);
        if (args == null) {
            args = new Object[0];
        }
        return genericService.$invoke(method, resolvedParamTypes, args);
    }

    private String[] resolveParamTypes(String interfaceName, String method, String[] paramTypes, Object[] args, String version) {
        boolean needsResolution = false;
        if (paramTypes == null || paramTypes.length == 0) {
            needsResolution = true;
        } else {
            for (String pt : paramTypes) {
                if (pt == null || pt.isEmpty() || "java.util.Map".equals(pt) || "java.lang.Object".equals(pt)) {
                    needsResolution = true;
                    break;
                }
            }
        }

        if (!needsResolution) {
            return paramTypes;
        }

        JSONObject meta = fetchServiceMetadata(interfaceName, version);
        if (meta != null && meta.containsKey("methods")) {
            JSONArray methods = meta.getJSONArray("methods");
            if (methods != null) {
                int argCount = args != null ? args.length : 0;
                for (int i = 0; i < methods.size(); i++) {
                    JSONObject m = methods.getJSONObject(i);
                    if (method.equals(m.getString("name"))) {
                        JSONArray pts = m.getJSONArray("parameterTypes");
                        if (pts != null && (pts.size() == argCount || argCount == 0)) {
                            String[] resolved = new String[pts.size()];
                            for (int j = 0; j < pts.size(); j++) {
                                resolved[j] = pts.getString(j);
                            }
                            return resolved;
                        }
                    }
                }
            }
        }

        return paramTypes != null ? paramTypes : new String[0];
    }

    private JSONObject fetchServiceMetadata(String interfaceName, String version) {
        String cacheKey = interfaceName + ":" + (version != null ? version : "");
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
            } else {
                targetVer = versionNodes.get(0);
            }

            String providerPath = basePath + "/" + targetVer + "/provider";
            if (client.checkExists().forPath(providerPath) == null) {
                providerPath = basePath + "/" + targetVer;
            }

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
            reference.setCheck(false);    // Do not fail immediately on startup

            if (version != null && !version.trim().isEmpty()) {
                reference.setVersion(version.trim());
            }
            if (group != null && !group.trim().isEmpty()) {
                reference.setGroup(group.trim());
            }

            return reference.get();
        });
    }

    private String resolveVersion(String interfaceName, String version) {
        if (version != null && !version.trim().isEmpty()) {
            return version.trim();
        }
        // Check config default: env.<env>.version
        ConfigManager cfg = ConfigManager.getInstance();
        String defaultVersion = cfg.getProperty("env." + env + ".version");
        if (defaultVersion != null && !defaultVersion.trim().isEmpty()) {
            return defaultVersion.trim();
        }

        // Auto-detect from ZooKeeper providers
        Set<String> discoveredVersions = getDiscoveredVersions(interfaceName);
        if (!discoveredVersions.isEmpty()) {
            // Use the first discovered version (e.g. "1.0.0")
            return discoveredVersions.iterator().next();
        }
        return null;
    }

    private String resolveGroup(String interfaceName, String group) {
        if (group != null && !group.trim().isEmpty()) {
            return group.trim();
        }
        // Check config default: env.<env>.group
        ConfigManager cfg = ConfigManager.getInstance();
        String defaultGroup = cfg.getProperty("env." + env + ".group");
        if (defaultGroup != null && !defaultGroup.trim().isEmpty()) {
            return defaultGroup.trim();
        }
        return null;
    }

    private Set<String> getDiscoveredVersions(String interfaceName) {
        Set<String> versions = new LinkedHashSet<>();
        CuratorFramework client = getCuratorClient();
        if (client == null) return versions;

        try {
            String providersPath = "/dubbo/" + interfaceName + "/providers";
            if (client.checkExists().forPath(providersPath) != null) {
                List<String> children = client.getChildren().forPath(providersPath);
                for (String rawUrl : children) {
                    String decodedUrl = URLDecoder.decode(rawUrl, "UTF-8");
                    URL url = URL.valueOf(decodedUrl);
                    String ver = url.getParameter("version");
                    if (ver != null && !ver.trim().isEmpty()) {
                        versions.add(ver.trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return versions;
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
                    if (lowerKeyword.isEmpty() || serviceName.toLowerCase().contains(lowerKeyword)) {
                        results.add(serviceName);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DubboDirectDriver] Error querying ZooKeeper services: " + e.getMessage());
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
            System.err.println("[DubboDirectDriver] Error fetching metadata for " + interfaceName + ": " + e.getMessage());
        }

        meta.put("methods", new ArrayList<>(methodNames));
        meta.put("versions", new ArrayList<>(versions));
        meta.put("groups", new ArrayList<>(groups));
        meta.put("applications", new ArrayList<>(applications));
        meta.put("providerCount", providerUrls.size());

        // Enrich with detailed ZooKeeper metadata report if available
        JSONObject metaReport = fetchServiceMetadata(interfaceName, null);
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

    private synchronized CuratorFramework getCuratorClient() {
        if (curatorClient != null) {
            return curatorClient;
        }

        if (registryUrl.startsWith("zookeeper://")) {
            String zkAddress = registryUrl.replace("zookeeper://", "");
            try {
                curatorClient = CuratorFrameworkFactory.builder()
                        .connectString(zkAddress)
                        .sessionTimeoutMs(timeout)
                        .connectionTimeoutMs(timeout)
                        .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                        .build();
                curatorClient.start();
            } catch (Exception e) {
                System.err.println("[DubboDirectDriver] Failed to connect Curator to ZooKeeper at " + zkAddress + ": " + e.getMessage());
            }
        }
        return curatorClient;
    }
}
