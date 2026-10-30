package com.mcp.dubbo.installer;

import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;

public class CodexTomlInstaller {
    static String configure(String existing, String jarPath) {
        Pattern current = Pattern.compile("(?m)^\\s*\\[mcp_servers\\.dubbo]\\s*(?:#.*)?$");
        Pattern legacy = Pattern.compile("(?m)^\\s*\\[mcp\\.servers\\.dubbo]\\s*(?:#.*)?$");
        if (current.matcher(existing).find()) return existing;
        if (legacy.matcher(existing).find()) return legacy.matcher(existing).replaceFirst("[mcp_servers.dubbo]");
        return existing + "\n# --- Dubbo-MCP Server Configuration ---\n[mcp_servers.dubbo]\ncommand = \"java\"\nargs = [\"-jar\", "
                + JSON.toJSONString(jarPath) + "]\n";
    }
    public static void install() {
        try {
            Path file = Paths.get(System.getProperty("user.home"), ".codex", "config.toml");
            Files.createDirectories(file.getParent());
            String jar = Paths.get(CodexTomlInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            if (!jar.endsWith(".jar")) jar = Paths.get("target/dubbo-mcp-1.0.0-SNAPSHOT.jar").toAbsolutePath().toString();
            String existing = Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
            String updated = configure(existing, jar);
            if (!existing.equals(updated)) Files.write(file, updated.getBytes(StandardCharsets.UTF_8));
            System.err.println("Codex MCP configuration ready: " + file);
        } catch (Exception e) { throw new IllegalStateException("Cannot update Codex MCP configuration", e); }
    }
}
