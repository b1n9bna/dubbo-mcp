package com.mcp.dubbo.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private ConfigManager() { this(Paths.get(USER_CONFIG_PATH)); }

    ConfigManager(Path userConfig) { loadConfig(userConfig.toFile()); }

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

    private void loadConfig(File userFile) {
        // 1. Load from classpath first
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (in == null) throw new IOException("Bundled configuration is missing");
            try (Reader reader = utf8Reader(in)) { properties.load(reader); }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load bundled configuration; startup aborted", e);
        }

        // 2. Override with ~/.dubbo-mcp/config.properties if present
        if (userFile.exists()) {
            if (!userFile.isFile()) throw new IllegalStateException("User configuration is not a regular file: " + userFile);
            try (InputStream in = new FileInputStream(userFile)) {
                Properties userProps = new Properties();
                try (Reader reader = utf8Reader(in)) { userProps.load(reader); }
                properties.putAll(userProps);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read user configuration at " + userFile + "; startup aborted", e);
            }
        }
    }

    private static Reader utf8Reader(InputStream input) throws IOException {
        PushbackReader reader = new PushbackReader(new InputStreamReader(input,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)), 1);
        int first = reader.read();
        if (first != -1 && first != 0xFEFF) reader.unread(first);
        return reader;
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
        String val = getProperty(key);
        if (val == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(val)) return true;
        if ("false".equalsIgnoreCase(val)) return false;
        throw new IllegalArgumentException("Invalid boolean configuration for " + key + "; expected true or false");
    }

    public int getInt(String key, int defaultValue) {
        String val = getProperty(key);
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

    public java.util.Map<String, String> getEnvAttachments(String env) {
        java.util.Map<String, String> attachments = new java.util.LinkedHashMap<>();
        if (env == null || env.trim().isEmpty()) return attachments;
        String prefix = "env." + env.trim() + ".attachment.";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String attachKey = key.substring(prefix.length());
                if (!attachKey.isEmpty()) {
                    attachments.put(attachKey, properties.getProperty(key));
                }
            }
        }
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String attachKey = key.substring(prefix.length());
                if (!attachKey.isEmpty()) {
                    attachments.put(attachKey, System.getProperty(key));
                }
            }
        }
        String envPrefix = "ENV_" + env.trim().toUpperCase(java.util.Locale.ROOT) + "_ATTACHMENT_";
        for (java.util.Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith(envPrefix)) {
                String attachKey = entry.getKey().substring(envPrefix.length()).toLowerCase(java.util.Locale.ROOT);
                if (!attachKey.isEmpty()) {
                    attachments.put(attachKey, entry.getValue());
                }
            }
        }
        return attachments;
    }

    public Properties getRawProperties() {
        return properties;
    }
}
