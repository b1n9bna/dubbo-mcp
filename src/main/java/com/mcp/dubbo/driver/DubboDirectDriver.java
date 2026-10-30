package com.mcp.dubbo.driver;

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
        GenericService genericService = getOrCreateGenericService(interfaceName);
        if (paramTypes == null) {
            paramTypes = new String[0];
        }
        if (args == null) {
            args = new Object[0];
        }
        return genericService.$invoke(method, paramTypes, args);
    }

    private GenericService getOrCreateGenericService(String interfaceName) {
        return genericServiceCache.computeIfAbsent(interfaceName, iface -> {
            ReferenceConfig<GenericService> reference = new ReferenceConfig<>();
            reference.setApplication(applicationConfig);
            reference.setRegistry(registryConfig);
            reference.setInterface(iface);
            reference.setGeneric("true"); // Enable generic invocation (no interface jars needed)
            reference.setTimeout(timeout);
            reference.setCheck(false);    // Do not fail immediately on startup
            return reference.get();
        });
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
                }
            }
        } catch (Exception e) {
            System.err.println("[DubboDirectDriver] Error fetching metadata for " + interfaceName + ": " + e.getMessage());
        }

        meta.put("methods", new ArrayList<>(methodNames));
        meta.put("providerCount", providerUrls.size());
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
