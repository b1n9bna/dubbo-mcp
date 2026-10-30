package com.mcp.dubbo.router;

import com.alibaba.fastjson2.JSON;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.DubboAdminDriver;
import com.mcp.dubbo.driver.DubboDirectDriver;
import com.mcp.dubbo.driver.spi.RpcDriver;
import com.mcp.dubbo.security.SecurityGuard;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Environment profile router:
 * Resolves the target environment (test / uat / prod), instantiates the corresponding
 * RpcDriver, enforces SecurityGuard checks, and masks sensitive output data.
 */
public class ProfileRouter {

    private final ConfigManager configManager = ConfigManager.getInstance();
    private final Map<String, RpcDriver> driverCache = new ConcurrentHashMap<>();

    private static volatile ProfileRouter instance;

    private ProfileRouter() {
    }

    public static ProfileRouter getInstance() {
        if (instance == null) {
            synchronized (ProfileRouter.class) {
                if (instance == null) {
                    instance = new ProfileRouter();
                }
            }
        }
        return instance;
    }

    public Object invoke(String env, String interfaceName, String method, String[] paramTypes, Object[] args) throws Exception {
        return invoke(env, interfaceName, method, paramTypes, args, null, null);
    }

    public Object invoke(String env, String interfaceName, String method, String[] paramTypes, Object[] args, String version, String group) throws Exception {
        String targetEnv = resolveEnv(env);

        // 1. Safety check (blocks write operations if environment is read-only)
        SecurityGuard.checkSafety(targetEnv, interfaceName, method);

        // 2. Execute RPC via appropriate driver
        RpcDriver driver = getDriverForEnv(targetEnv);
        Object rawResult = driver.invoke(interfaceName, method, paramTypes, args, version, group);

        // 3. Mask sensitive data if data_masking is enabled
        boolean mask = configManager.getBoolean("env." + targetEnv + ".data_masking", false);
        if (mask) {
            String jsonStr = JSON.toJSONString(rawResult);
            String maskedJson = SecurityGuard.maskSensitiveData(jsonStr);
            return JSON.parse(maskedJson);
        }

        return rawResult;
    }

    public List<String> listServices(String env, String keyword) {
        String targetEnv = resolveEnv(env);
        return getDriverForEnv(targetEnv).listServices(keyword);
    }

    public Map<String, Object> getServiceMetadata(String env, String interfaceName) {
        String targetEnv = resolveEnv(env);
        return getDriverForEnv(targetEnv).getServiceMetadata(interfaceName);
    }

    public String resolveEnv(String explicitEnv) {
        String env = explicitEnv == null || explicitEnv.trim().isEmpty() ? configManager.getActiveEnv() : explicitEnv.trim();
        if (!env.matches("[A-Za-z0-9_-]+") || !getConfiguredEnvs().contains(env))
            throw new IllegalArgumentException("Unknown environment: " + env);
        return env;
    }

    public Map<String, Object> getServiceMetadata(String env, String iface, String version, String group) {
        return getDriverForEnv(resolveEnv(env)).getServiceMetadata(iface, version, group);
    }

    public synchronized RpcDriver getDriverForEnv(String env) {
        String checked = resolveEnv(env);
        return driverCache.computeIfAbsent(checked, e -> {
            String type = configManager.getProperty("env." + e + ".type");
            if (type == null) {
                if (configManager.getProperty("env." + e + ".admin_url") != null) type = "dubbo-admin";
                else if (configManager.getProperty("env." + e + ".registry") != null) type = "zookeeper";
            }
            if ("dubbo-admin".equalsIgnoreCase(type)) return new DubboAdminDriver(e);
            if ("zookeeper".equalsIgnoreCase(type)) return new DubboDirectDriver(e);
            throw new IllegalArgumentException("Unsupported driver type for " + e + ": " + type + " (set env." + e + ".type=dubbo-admin or zookeeper)");
        });
    }

    public List<String> getConfiguredEnvs() {
        Set<String> envs = new LinkedHashSet<>();
        Properties props = configManager.getRawProperties();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("env.")) {
                String sub = key.substring(4);
                int dot = sub.indexOf('.');
                if (dot > 0) {
                    envs.add(sub.substring(0, dot));
                }
            }
        }
        return new ArrayList<>(envs);
    }
}
