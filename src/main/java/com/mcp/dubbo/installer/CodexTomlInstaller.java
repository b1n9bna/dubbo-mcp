package com.mcp.dubbo.installer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Automatically installs and registers dubbo-mcp into ~/.codex/config.toml
 * for Codex CLI, Codex Desktop, and IDE plugins.
 */
public class CodexTomlInstaller {

    private static final String CODEX_CONFIG_PATH = System.getProperty("user.home") + "/.codex/config.toml";

    public static void install() {
        try {
            File configFile = new File(CODEX_CONFIG_PATH);
            File parentDir = configFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Determine jar location
            String jarPath = new File(CodexTomlInstaller.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();

            if (!jarPath.endsWith(".jar")) {
                File candidate = new File("target/dubbo-mcp-1.0.0-SNAPSHOT.jar");
                if (candidate.exists()) {
                    jarPath = candidate.getAbsolutePath();
                }
            }

            String existingContent = "";
            if (configFile.exists()) {
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    byte[] data = new byte[(int) configFile.length()];
                    int read = fis.read(data);
                    if (read > 0) {
                        existingContent = new String(data, 0, read, StandardCharsets.UTF_8);
                    }
                }
            }

            if (existingContent.contains("[mcp.servers.dubbo]")) {
                System.err.println("ℹ️ [CodexInstaller] ~/.codex/config.toml already contains [mcp.servers.dubbo]. Skipping injection.");
                return;
            }

            String tomlSnippet = "\n# --- Dubbo-MCP Server Configuration ---\n" +
                    "[mcp.servers.dubbo]\n" +
                    "command = \"java\"\n" +
                    "args = [\"-jar\", \"" + jarPath + "\"]\n";

            try (FileOutputStream fos = new FileOutputStream(configFile, true)) {
                fos.write(tomlSnippet.getBytes(StandardCharsets.UTF_8));
            }

            System.err.println("==================================================================");
            System.err.println("🎉 [CodexInstaller] 成功将 dubbo-mcp 注册至 Codex 配置文件！");
            System.err.println("📁 配置文件路径: " + CODEX_CONFIG_PATH);
            System.err.println("🚀 现在重启 Codex CLI 或打开 Codex，即可直接使用 dubbo_invoke 等工具！");
            System.err.println("==================================================================");

        } catch (Exception e) {
            System.err.println("❌ [CodexInstaller] Failed to update ~/.codex/config.toml: " + e.getMessage());
        }
    }
}
