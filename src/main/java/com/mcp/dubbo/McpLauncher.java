package com.mcp.dubbo;

import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.installer.CodexTomlInstaller;
import com.mcp.dubbo.transport.StdioJsonRpcServer;
import com.mcp.dubbo.transport.StdioLogIsolator;

/**
 * Main application launcher for dubbo-mcp.
 */
public class McpLauncher {

    public static void main(String[] args) {
        // Parse CLI arguments before initializing Stdio isolation
        if (args != null && args.length > 0) {
            for (String arg : args) {
                if ("--install-codex".equalsIgnoreCase(arg)) {
                    CodexTomlInstaller.install();
                    return;
                } else if (arg.startsWith("--env=")) {
                    String env = arg.substring("--env=".length()).trim();
                    ConfigManager.getInstance().setActiveEnv(env);
                } else if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                    printHelp();
                    return;
                }
            }
        }

        // 1. Isolate System.out immediately to protect the JSON-RPC STDIO channel
        StdioLogIsolator.init();

        // 2. Start MCP Stdio JSON-RPC Server
        StdioJsonRpcServer server = new StdioJsonRpcServer();
        server.start();
    }

    private static void printHelp() {
        System.err.println("🧭 Dubbo-MCP: The Enterprise Dubbo RPC Bridge for AI Coding Agents");
        System.err.println("Usage:");
        System.err.println("  java -jar dubbo-mcp.jar                  # Start MCP Stdio Server");
        System.err.println("  java -jar dubbo-mcp.jar --install-codex  # Register into ~/.codex/config.toml");
        System.err.println("  java -jar dubbo-mcp.jar --env=prod       # Start with default env=prod");
    }
}
