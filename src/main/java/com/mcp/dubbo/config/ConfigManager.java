package com.mcp.dubbo.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Manages configuration for dubbo-mcp.
 * Priority:
 * 1. User home config: ~/.dubbo-mcp/config.properties
 * 2. Classpath default: dubbo-mcp.properties
 */
public class ConfigManager {

    private static final String DEFAULT_CONFIG_RESOURCE = "dubbo-mcp.properties";
    private static final String USER_CONFIG_PATH = System.getProperty("user.home") + "/.dubbo-mcp/config.properties";

    private final Properties properties = new Properties();
    private static volatile ConfigManager instance;

    private ConfigManager() {
        loadConfig();
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            synchronized (ConfigManager.class) {
                if (instance == null) {
                    instance = new ConfigManager();
                }
            }
        }
        return instance;
    }

    private void loadConfig() {
        // 1. Load from classpath first
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (Exception e) {
            System.err.println("[ConfigManager] Failed to load default classpath config: " + e.getMessage());
        }

        // 2. Override with ~/.dubbo-mcp/config.properties if present
        File userFile = new File(USER_CONFIG_PATH);
        if (userFile.exists() && userFile.isFile()) {
            try (InputStream in = new FileInputStream(userFile)) {
                Properties userProps = new Properties();
                userProps.load(in);
                properties.putAll(userProps);
            } catch (Exception e) {
                System.err.println("[ConfigManager] Failed to load user config file from " + USER_CONFIG_PATH + ": " + e.getMessage());
            }
        }
    }

    public String getProperty(String key) {
        String val = properties.getProperty(key);
        if (val == null) {
            val = System.getProperty(key);
        }
        if (val == null && key != null) {
            // Environment variable fallback: active.env -> ACTIVE_ENV or DUBBO_MCP_ACTIVE_ENV
            String envKey = key.replace('.', '_').replace('-', '_').toUpperCase(java.util.Locale.ROOT);
            val = System.getenv(envKey);
            if (val == null) {
                val = System.getenv("DUBBO_MCP_" + envKey);
            }
        }
        return val != null ? val.trim() : null;
    }

    public String getProperty(String key, String defaultValue) {
        String val = getProperty(key);
        return val != null ? val : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = properties.getProperty(key);
        if (val == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(val.trim());
    }

    public int getInt(String key, int defaultValue) {
        String val = properties.getProperty(key);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getActiveEnv() {
        return getProperty("active.env", "test");
    }

    public void setActiveEnv(String env) {
        properties.setProperty("active.env", env);
    }

    public Properties getRawProperties() {
        return properties;
    }
}
