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
                if ("install".equalsIgnoreCase(arg) || "--install".equalsIgnoreCase(arg) || "-i".equalsIgnoreCase(arg)) {
                    if (!com.mcp.dubbo.installer.AgentAutoInstaller.installAll()) System.exit(1);
                    return;
                } else if ("login".equalsIgnoreCase(arg) || "--login".equalsIgnoreCase(arg)) {
                    runInteractiveLogin(args);
                    return;
                } else if ("--install-codex".equalsIgnoreCase(arg)) {
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

    private static void runInteractiveLogin(String[] args) {
        try { com.mcp.dubbo.auth.LoginCommand.run(args); }
        catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.err.println("🧭 Dubbo-MCP: The Enterprise Dubbo RPC Bridge for AI Coding Agents");
        System.err.println("Usage:");
        System.err.println("  dubbo-mcp                               # Start MCP Stdio Server");
        System.err.println("  dubbo-mcp install                       # Auto-detect and wire up all AI agents (Codex, Cursor, Claude)");
        System.err.println("  dubbo-mcp login                         # Validate and save Cookie + Authorization");
        System.err.println("  dubbo-mcp login --clipboard             # macOS: read credentials from clipboard");
        System.err.println("  dubbo-mcp login --stdin | --file PATH    # read credentials without terminal line limits");
        System.err.println("  dubbo-mcp --env=prod                    # Start with default env=prod");
    }
}
