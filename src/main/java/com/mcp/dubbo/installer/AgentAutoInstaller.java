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

    public static void installAll() {
        System.err.println("\n🚀 [Dubbo-MCP] 正在自动检测并配置本机的 AI Coding Agents...\n");

        String jarPath = resolveJarPath();
        boolean anyConfigured = false;

        // 1. Configure Codex CLI / Desktop (~/.codex/config.toml)
        if (configureCodex(jarPath)) {
            anyConfigured = true;
        }

        // 2. Configure Claude Desktop (claude_desktop_config.json)
        if (configureClaudeDesktop(jarPath)) {
            anyConfigured = true;
        }

        // 3. Configure Cursor (~/.cursor/mcp.json)
        if (configureCursor(jarPath)) {
            anyConfigured = true;
        }

        System.err.println("\n==================================================================");
        if (anyConfigured) {
            System.err.println("🎉 [Dubbo-MCP] 配置完成！请重启您的 AI 编程工具即可开始使用。");
        } else {
            System.err.println("ℹ️ 未检测到运行中的 AI 工具配置文件。已为您默认生成 Codex 与 Cursor 配置。");
        }
        System.err.println("==================================================================\n");
    }

    private static boolean configureCodex(String jarPath) {
        String codexPath = System.getProperty("user.home") + "/.codex/config.toml";
        try {
            File configFile = new File(codexPath);
            File parentDir = configFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            String existing = readFile(configFile);
            if (existing.contains("[mcp.servers.dubbo]")) {
                System.err.println("✓ Codex: 配置文件已有 [mcp.servers.dubbo]，已跳过重复注入 (" + codexPath + ")");
                return true;
            }

            String tomlSnippet = "\n# --- Dubbo-MCP Server Configuration ---\n" +
                    "[mcp.servers.dubbo]\n" +
                    "command = \"java\"\n" +
                    "args = [\"-jar\", \"" + jarPath + "\"]\n";

            writeFile(configFile, existing + tomlSnippet);
            System.err.println("✅ Codex: 成功配置 " + codexPath);
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ Codex 配置跳过: " + e.getMessage());
            return false;
        }
    }

    private static boolean configureClaudeDesktop(String jarPath) {
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
            return false;
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

            JSONObject dubboServer = new JSONObject();
            dubboServer.put("command", "java");
            dubboServer.put("args", new String[]{"-jar", jarPath});
            mcpServers.put("dubbo", dubboServer);

            writeFile(configFile, root.toString(com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
            System.err.println("✅ Claude Desktop: 成功配置 " + configPath);
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ Claude Desktop 配置跳过: " + e.getMessage());
            return false;
        }
    }

    private static boolean configureCursor(String jarPath) {
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

            JSONObject dubboServer = new JSONObject();
            dubboServer.put("command", "java");
            dubboServer.put("args", new String[]{"-jar", jarPath});
            mcpServers.put("dubbo", dubboServer);

            writeFile(configFile, root.toString(com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat));
            System.err.println("✅ Cursor: 成功配置 " + cursorPath);
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ Cursor 配置跳过: " + e.getMessage());
            return false;
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

    private static String readFile(File file) {
        if (!file.exists()) return "";
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            return read > 0 ? new String(data, 0, read, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeFile(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
