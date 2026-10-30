package com.mcp.dubbo.installer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Universal AI Agent Auto-Configurator.
 * Inspired by tools like CodeGraph: automatically detects installed AI coding agents
 * on the user's machine (Codex, Cursor, Claude Desktop) and wires them up with zero friction.
 */
public class AgentAutoInstaller {

    private enum Result { CONFIGURED, SKIPPED, FAILED }

    public static boolean installAll() {
        System.err.println("Installing Dubbo-MCP client configurations...");
        String jarPath = resolveJarPath();
        Result[] results = {configureCodex(jarPath), configureClaudeDesktop(jarPath), configureCursor(jarPath)};
        int configured = 0, skipped = 0, failed = 0;
        for (Result result : results) {
            if (result == Result.CONFIGURED) configured++;
            else if (result == Result.SKIPPED) skipped++;
            else failed++;
        }
        System.err.println("MCP configuration summary: configured=" + configured + ", skipped=" + skipped + ", failed=" + failed);
        if (failed > 0) {
            System.err.println("MCP configuration failed; resolve the errors above and rerun install.");
            return false;
        }
        System.err.println("MCP configuration complete. Restart your client to load the configuration.");
        return true;
    }

    private static Result configureCodex(String jarPath) {
        String codexPath = System.getProperty("user.home") + "/.codex/config.toml";
        try {
            File configFile = new File(codexPath);
            File parentDir = configFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            String existing = readFile(configFile);
            String updated = CodexTomlInstaller.configure(existing, jarPath);
            if (!updated.equals(existing)) writeFile(configFile, updated);
            System.err.println("✅ Codex: 成功配置 " + codexPath);
            return Result.CONFIGURED;
        } catch (Exception e) {
            System.err.println("⚠️ Codex 配置跳过: " + e.getMessage());
            return Result.FAILED;
        }
    }

    private static Result configureClaudeDesktop(String jarPath) {
        String os = System.getProperty("os.name").toLowerCase();
        String configPath;
        if (os.contains("mac")) {
            configPath = System.getProperty("user.home") + "/Library/Application Support/Claude/claude_desktop_config.json";
        } else if (os.contains("win")) {
            configPath = System.getenv("APPDATA") + "\\Claude\\claude_desktop_config.json";
        } else {
            configPath = System.getProperty("user.home") + "/.config/Claude/claude_desktop_config.json";
        }

        File configFile = new File(configPath);
        // Only configure Claude Desktop if the Claude directory exists or file exists
        if (!configFile.getParentFile().exists() && !configFile.exists()) {
            return Result.SKIPPED;
        }

        try {
            JSONObject root;
            if (configFile.exists()) {
                String content = readFile(configFile);
                root = content.trim().isEmpty() ? new JSONObject() : JSON.parseObject(content);
            } else {
                root = new JSONObject();
            }

            JSONObject mcpServers = root.getJSONObject("mcpServers");
            if (mcpServers == null) {
                mcpServers = new JSONObject();
                root.put("mcpServers", mcpServers);
            }

            if (mcpServers.containsKey("dubbo")) {
                System.err.println("Existing dubbo MCP entry preserved: " + configPathFor(configFile));
                return Result.CONFIGURED;
            }
            JSONObject dubboServer = new JSONObject();
            dubboServer.put("command", "java");
            dubboServer.put("args", new String[]{"-jar", jarPath});
            mcpServers.put("dubbo", dubboServer);

            writeFile(configFile, root.toString(com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
            System.err.println("✅ Claude Desktop: 成功配置 " + configPath);
            return Result.CONFIGURED;
        } catch (Exception e) {
            System.err.println("⚠️ Claude Desktop 配置跳过: " + e.getMessage());
            return Result.FAILED;
        }
    }

    private static Result configureCursor(String jarPath) {
        String cursorPath = System.getProperty("user.home") + "/.cursor/mcp.json";
        try {
            File configFile = new File(cursorPath);
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

            JSONObject root;
            if (configFile.exists()) {
                String content = readFile(configFile);
                root = content.trim().isEmpty() ? new JSONObject() : JSON.parseObject(content);
            } else {
                root = new JSONObject();
            }

            JSONObject mcpServers = root.getJSONObject("mcpServers");
            if (mcpServers == null) {
                mcpServers = new JSONObject();
                root.put("mcpServers", mcpServers);
            }

            if (mcpServers.containsKey("dubbo")) {
                System.err.println("Existing dubbo MCP entry preserved: " + configPathFor(configFile));
                return Result.CONFIGURED;
            }
            JSONObject dubboServer = new JSONObject();
            dubboServer.put("command", "java");
            dubboServer.put("args", new String[]{"-jar", jarPath});
            mcpServers.put("dubbo", dubboServer);

            writeFile(configFile, root.toString(com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
            System.err.println("✅ Cursor: 成功配置 " + cursorPath);
            return Result.CONFIGURED;
        } catch (Exception e) {
            System.err.println("⚠️ Cursor 配置跳过: " + e.getMessage());
            return Result.FAILED;
        }
    }

    private static String resolveJarPath() {
        try {
            String jarPath = new File(AgentAutoInstaller.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();

            if (!jarPath.endsWith(".jar")) {
                File defaultHomeJar = new File(System.getProperty("user.home") + "/.dubbo-mcp/dubbo-mcp.jar");
                if (defaultHomeJar.exists()) {
                    return defaultHomeJar.getAbsolutePath();
                }
                File candidate = new File("target/dubbo-mcp-1.0.0-SNAPSHOT.jar");
                if (candidate.exists()) {
                    return candidate.getAbsolutePath();
                }
            }
            return jarPath;
        } catch (Exception e) {
            return System.getProperty("user.home") + "/.dubbo-mcp/dubbo-mcp.jar";
        }
    }

    private static String readFile(File file) throws java.io.IOException {
        if (!file.exists()) return "";
        try (FileInputStream fis = new FileInputStream(file)) {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
    }

    private static String configPathFor(File file) { return file.getAbsolutePath(); }

    private static void writeFile(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
