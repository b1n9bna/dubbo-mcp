package com.mcp.dubbo.transport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.router.TwoStageToolRegistry;
import com.mcp.dubbo.transport.protocol.JsonRpcRequest;
import com.mcp.dubbo.transport.protocol.JsonRpcResponse;
import com.mcp.dubbo.transport.protocol.McpTool;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standard I/O JSON-RPC 2.0 Server implementing Model Context Protocol (MCP).
 * Communicates with AI clients (Codex CLI, Codex Desktop, Cursor, Claude Code)
 * via stdin / stdout using newline-delimited JSON.
 */
public class StdioJsonRpcServer {

    private final TwoStageToolRegistry toolRegistry = new TwoStageToolRegistry();

    public void start() {
        serve(System.in, StdioLogIsolator.getRpcOut());
    }

    void serve(java.io.InputStream input, PrintStream output) {
        com.mcp.dubbo.config.ConfigManager config = com.mcp.dubbo.config.ConfigManager.getInstance();
        int timeout = config.getInt("mcp.request_timeout_ms", 60000);
        int shutdown = config.getInt("mcp.shutdown_timeout_ms", 5000);
        int maxRequestBytes = config.getInt("mcp.max_request_bytes", 1024 * 1024);
        if (maxRequestBytes <= 0 || maxRequestBytes > 8 * 1024 * 1024)
            throw new IllegalArgumentException("mcp.max_request_bytes must be between 1 and 8388608");
        if (timeout <= 0 || shutdown < 0) throw new IllegalArgumentException("Invalid MCP timeout configuration");
        // Preserve tool/environment ordering while keeping protocol control messages responsive.
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), r -> daemon(r, "dubbo-mcp-tool"));
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "dubbo-mcp-timeout"));
        timer.setRemoveOnCancelPolicy(true);
        Map<Object, PendingCall> pending = new ConcurrentHashMap<>();
        try (BoundedMessageReader reader = new BoundedMessageReader(input, maxRequestBytes)) {
            BoundedMessageReader.Frame frame;
            while ((frame = reader.readFrame()) != null) {
                if (frame.oversized) {
                    write(output, JSON.toJSONString(JsonRpcResponse.error(null, -32600,
                            "Message exceeds mcp.max_request_bytes; frame discarded")));
                    continue;
                }
                String line = frame.text;
                if (line.trim().isEmpty()) continue;
                JSONObject request;
                try { request = JSON.parseObject(line); }
                catch (Exception e) { write(output, processLine(line)); continue; }
                if (request != null && "2.0".equals(request.get("jsonrpc"))
                        && "notifications/cancelled".equals(request.get("method")) && !request.containsKey("id")) {
                    Object params = request.get("params");
                    if (params instanceof Map) {
                        Object id = ((Map<?,?>) params).get("requestId");
                        PendingCall call = id == null ? null : pending.get(id);
                        if (call != null) call.cancel();
                    }
                    continue;
                }
                Object id = request == null ? null : request.get("id");
                if (request == null || !"2.0".equals(request.get("jsonrpc")) || !(id instanceof String || id instanceof Number)
                        || !"tools/call".equals(request.get("method"))) {
                    write(output, processLine(line));
                    continue;
                }
                PendingCall call = new PendingCall(id, line, output, worker, pending);
                if (pending.putIfAbsent(id, call) != null) {
                    write(output, JSON.toJSONString(JsonRpcResponse.error(id, -32600, "Duplicate in-flight request id")));
                    continue;
                }
                call.deadline = timer.schedule(call::timeout, timeout, TimeUnit.MILLISECONDS);
                try { worker.execute(call.task); }
                catch (RejectedExecutionException e) {
                    call.finish(JSON.toJSONString(JsonRpcResponse.error(id, -32000, "Tool queue is full; request was not executed")));
                }
            }
        } catch (Exception e) {
            System.err.println("[StdioJsonRpcServer] Stdio connection closed: " + e.getClass().getSimpleName());
        } finally {
            worker.shutdown();
            try { worker.awaitTermination(shutdown, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (PendingCall call : pending.values()) call.cancel();
            worker.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); return thread;
    }
    private static void write(PrintStream output, String response) {
        if (response == null) return;
        synchronized (output) { output.println(response); output.flush(); }
    }

    private final class PendingCall {
        final Object id;
        final PrintStream output;
        final ThreadPoolExecutor worker;
        final Map<Object, PendingCall> pending;
        final AtomicBoolean done = new AtomicBoolean();
        final FutureTask<Void> task;
        volatile ScheduledFuture<?> deadline;
        PendingCall(Object id, String line, PrintStream output, ThreadPoolExecutor worker, Map<Object, PendingCall> pending) {
            this.id = id; this.output = output; this.worker = worker; this.pending = pending;
            task = new FutureTask<>(() -> {
                try { if (!done.get()) finish(processLine(line)); }
                finally { cleanup(); }
                return null;
            });
        }
        void finish(String response) {
            if (done.compareAndSet(false, true)) { write(output, response); cleanup(); }
        }
        void timeout() {
            finish(JSON.toJSONString(JsonRpcResponse.successText(id,
                    "Request timed out. Remote operations may already have completed; do not automatically retry writes.", true)));
            task.cancel(true); worker.remove(task);
        }
        void cancel() {
            // MCP cancellation notifications suppress the original response; they do not roll back remote writes.
            if (done.compareAndSet(false, true)) {
                task.cancel(true); worker.remove(task); cleanup();
            }
        }
        void cleanup() {
            if (deadline != null) deadline.cancel(false);
            pending.remove(id, this);
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
                serverInfo.put("version", "1.0.2");

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
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    String msg = e.getMessage() != null && !e.getMessage().trim().isEmpty()
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
                    if (root != e && root.getMessage() != null && !root.getMessage().trim().isEmpty() && !msg.contains(root.getMessage())) {
                        msg = msg + " (Root cause: " + root.getMessage() + ")";
                    }
                    // Errors are also output data: provider/root-cause messages may contain PII.
                    String env = arguments != null && arguments.get("env") instanceof String
                            ? ((String) arguments.get("env")).trim() : null;
                    com.mcp.dubbo.config.ConfigManager config = com.mcp.dubbo.config.ConfigManager.getInstance();
                    if (env == null || env.isEmpty()) env = config.getActiveEnv();
                    boolean mask = true;
                    try { mask = config.getBoolean("env." + env + ".data_masking", false); }
                    catch (IllegalArgumentException ignored) { /* Invalid privacy settings fail closed. */ }
                    if (mask) msg = com.mcp.dubbo.security.SecurityGuard.maskSensitiveData(msg);
                    String errorMsg = "⚠️ [Dubbo-MCP Execution Error] " + msg;
                    return JsonRpcResponse.successText(id, errorMsg, true);
                }
            }

            default:
                return JsonRpcResponse.error(id, -32601, "Method not found: " + method);
        }
    }
}
