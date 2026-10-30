package com.mcp.dubbo.transport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.router.TwoStageToolRegistry;
import com.mcp.dubbo.transport.protocol.JsonRpcRequest;
import com.mcp.dubbo.transport.protocol.JsonRpcResponse;
import com.mcp.dubbo.transport.protocol.McpTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Standard I/O JSON-RPC 2.0 Server implementing Model Context Protocol (MCP).
 * Communicates with AI clients (Codex CLI, Codex Desktop, Cursor, Claude Code)
 * via stdin / stdout using newline-delimited JSON.
 */
public class StdioJsonRpcServer {

    private final TwoStageToolRegistry toolRegistry = new TwoStageToolRegistry();

    public void start() {
        // Guarantee that stdout is reserved exclusively for JSON-RPC
        PrintStream rpcOut = StdioLogIsolator.getRpcOut();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String response = processLine(line);
                if (response != null) { rpcOut.println(response); rpcOut.flush(); }

            }
        } catch (Exception e) {
            System.err.println("[StdioJsonRpcServer] Stdio connection closed: " + e.getMessage());
        }
    }

    String processLine(String line) {
        JSONObject object;
        try { object = JSON.parseObject(line); }
        catch (Exception e) { return JSON.toJSONString(JsonRpcResponse.error(null, -32700, "Parse error")); }
        if (object == null || !"2.0".equals(object.getString("jsonrpc")) || !(object.get("method") instanceof String)
                || (object.get("id") != null && !(object.get("id") instanceof String) && !(object.get("id") instanceof Number)))
            return JSON.toJSONString(JsonRpcResponse.error(null, -32600, "Invalid Request"));
        // Notifications never invoke business tools or generate a response.
        if (!object.containsKey("id") || object.get("id") == null) return null;
        Object id = object.get("id");
        try {
            if (object.get("params") != null && !(object.get("params") instanceof Map))
                return JSON.toJSONString(JsonRpcResponse.error(id, -32602, "params must be an object"));
            return JSON.toJSONString(handleRequest(object.toJavaObject(JsonRpcRequest.class)));
        } catch (Exception e) {
            return JSON.toJSONString(JsonRpcResponse.error(id, -32602, "Invalid request parameters"));
        }
    }

    private JsonRpcResponse handleRequest(JsonRpcRequest request) {
        String method = request.getMethod();
        Object id = request.getId();

        switch (method) {
            case "initialize": {
                Map<String, Object> serverInfo = new LinkedHashMap<>();
                serverInfo.put("name", "dubbo-mcp");
                serverInfo.put("version", "1.0.0");

                Map<String, Object> capabilities = new LinkedHashMap<>();
                capabilities.put("tools", Collections.emptyMap());

                Map<String, Object> initResult = new LinkedHashMap<>();
                initResult.put("protocolVersion", "2024-11-05");
                initResult.put("serverInfo", serverInfo);
                initResult.put("capabilities", capabilities);

                return JsonRpcResponse.success(id, initResult);
            }

            case "notifications/initialized": {
                // Client acknowledgment notification; no reply needed
                return null;
            }

            case "ping": {
                return JsonRpcResponse.success(id, Collections.emptyMap());
            }

            case "tools/list": {
                List<McpTool> tools = toolRegistry.getTools();
                Map<String, Object> result = Collections.singletonMap("tools", tools);
                return JsonRpcResponse.success(id, result);
            }

            case "tools/call": {
                Map<String, Object> params = request.getParams();
                if (params == null) {
                    return JsonRpcResponse.error(id, -32602, "Invalid params: params object is missing");
                }

                if (!(params.get("name") instanceof String) || (params.get("arguments") != null && !(params.get("arguments") instanceof Map)))
                    return JsonRpcResponse.error(id, -32602, "name must be a string and arguments must be an object");
                String toolName = (String) params.get("name");
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

                try {
                    String output = toolRegistry.executeTool(toolName, arguments);
                    return JsonRpcResponse.successText(id, output, false);
                } catch (Exception e) {
                    String errorMsg = "⚠️ [Dubbo-MCP Execution Error] " + e.getMessage();
                    return JsonRpcResponse.successText(id, errorMsg, true);
                }
            }

            default:
                return JsonRpcResponse.error(id, -32601, "Method not found: " + method);
        }
    }
}
