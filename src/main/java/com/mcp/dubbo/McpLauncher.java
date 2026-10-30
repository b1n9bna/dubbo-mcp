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
                    com.mcp.dubbo.installer.AgentAutoInstaller.installAll();
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
        String targetEnv = "prod";
        if (args != null) {
            for (String a : args) {
                if (a.startsWith("--env=")) {
                    targetEnv = a.substring("--env=".length()).trim();
                }
            }
        }
        System.out.println("🔐 [Dubbo-MCP] 生产环境凭据配置 (目标环境: " + targetEnv + ")");
        System.out.println("👉 请在浏览器已登录的 Dubbo-Admin 页面 (F12 控制台) 执行: copy(document.cookie)");
        System.out.print("👉 请在此粘贴 Cookie 内容并按回车: ");
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        if (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (!line.isEmpty()) {
                com.mcp.dubbo.auth.AuthSessionManager.getInstance().saveCredential(targetEnv, line);
                System.out.println("\n✅ [Dubbo-MCP] 凭据保存成功！已自动生效，重启或直接在 Codex 中调用即可。");
                return;
            }
        }
        System.out.println("⚠️ 未输入有效凭证，操作已取消。");
    }

    private static void printHelp() {
        System.err.println("🧭 Dubbo-MCP: The Enterprise Dubbo RPC Bridge for AI Coding Agents");
        System.err.println("Usage:");
        System.err.println("  dubbo-mcp                               # Start MCP Stdio Server");
        System.err.println("  dubbo-mcp install                       # Auto-detect and wire up all AI agents (Codex, Cursor, Claude)");
        System.err.println("  dubbo-mcp login                         # Paste and save session Cookie for production");
        System.err.println("  dubbo-mcp --env=prod                    # Start with default env=prod");
    }
}
