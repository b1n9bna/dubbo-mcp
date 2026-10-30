package com.mcp.dubbo.transport;

import java.io.PrintStream;

/**
 * Isolates System.out to prevent third-party libraries (Dubbo, ZooKeeper, Netty)
 * from polluting stdout, which would break the Codex / MCP JSON-RPC protocol.
 */
public class StdioLogIsolator {

    private static PrintStream rpcOut;

    public static synchronized void init() {
        if (rpcOut != null) {
            return;
        }
        // Save the pristine standard output stream specifically for JSON-RPC
        rpcOut = System.out;

        // Redirect standard System.out to System.err
        // This ensures any log4j/slf4j/e.printStackTrace() leaking to stdout is rerouted to stderr.
        System.setOut(System.err);
    }

    public static PrintStream getRpcOut() {
        if (rpcOut == null) {
            init();
        }
        return rpcOut;
    }
}
