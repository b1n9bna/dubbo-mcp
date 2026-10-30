package com.mcp.dubbo.router;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.transport.protocol.McpTool;

import java.util.*;

/**
 * Two-stage MCP Tool Registry:
 * Exposes compact meta-tools to Codex / Cursor to prevent context-window token explosion.
 * Handles dispatching and parameter conversion.
 */
public class TwoStageToolRegistry {

    private final ProfileRouter profileRouter = ProfileRouter.getInstance();
    private final ConfigManager configManager = ConfigManager.getInstance();
    private final List<McpTool> registeredTools = new ArrayList<>();

    public TwoStageToolRegistry() {
        initTools();
    }

    private void initTools() {
        // 1. dubbo_list_services
        Map<String, Object> listServicesSchema = new LinkedHashMap<>();
        listServicesSchema.put("type", "object");
        Map<String, Object> listProps = new LinkedHashMap<>();
        listProps.put("keyword", Collections.singletonMap("type", "string"));
        listProps.put("env", Collections.singletonMap("type", "string"));
        listServicesSchema.put("properties", listProps);

        registeredTools.add(new McpTool(
                "dubbo_list_services",
                "Search and list registered Dubbo RPC services by keyword (e.g. 'order', 'user', 'trade')",
                listServicesSchema
        ));

        // 2. dubbo_get_service_detail
        Map<String, Object> detailSchema = new LinkedHashMap<>();
        detailSchema.put("type", "object");
        Map<String, Object> detailProps = new LinkedHashMap<>();
        detailProps.put("interfaceName", Collections.singletonMap("type", "string"));
        detailProps.put("env", Collections.singletonMap("type", "string"));
        detailSchema.put("properties", detailProps);
        detailSchema.put("required", Collections.singletonList("interfaceName"));

        registeredTools.add(new McpTool(
                "dubbo_get_service_detail",
                "Retrieve available methods, signatures, and provider details for a Dubbo interface",
                detailSchema
        ));

        // 3. dubbo_invoke
        Map<String, Object> invokeSchema = new LinkedHashMap<>();
        invokeSchema.put("type", "object");
        Map<String, Object> invokeProps = new LinkedHashMap<>();
        invokeProps.put("interfaceName", Collections.singletonMap("type", "string"));
        invokeProps.put("method", Collections.singletonMap("type", "string"));
        invokeProps.put("paramTypes", Collections.singletonMap("type", "array"));
        invokeProps.put("args", Collections.singletonMap("type", "array"));
        invokeProps.put("env", Collections.singletonMap("type", "string"));
        invokeSchema.put("properties", invokeProps);
        invokeSchema.put("required", Arrays.asList("interfaceName", "method"));

        registeredTools.add(new McpTool(
                "dubbo_invoke",
                "Invoke a Dubbo RPC service using generic invocation without any JAR dependencies. Returns result JSON.",
                invokeSchema
        ));

        // 4. dubbo_list_envs
        Map<String, Object> emptySchema = new LinkedHashMap<>();
        emptySchema.put("type", "object");

        registeredTools.add(new McpTool(
                "dubbo_list_envs",
                "List all configured enterprise environments (test, uat, prod) and show the currently active one",
                emptySchema
        ));

        // 5. dubbo_switch_env
        Map<String, Object> switchSchema = new LinkedHashMap<>();
        switchSchema.put("type", "object");
        switchSchema.put("properties", Collections.singletonMap("env", Collections.singletonMap("type", "string")));
        switchSchema.put("required", Collections.singletonList("env"));

        registeredTools.add(new McpTool(
                "dubbo_switch_env",
                "Switch the active default environment (e.g. 'test', 'prod') for subsequent invocations",
                switchSchema
        ));
    }

    public List<McpTool> getTools() {
        return Collections.unmodifiableList(registeredTools);
    }

    public String executeTool(String toolName, Map<String, Object> arguments) throws Exception {
        if (arguments == null) {
            arguments = Collections.emptyMap();
        }
        String env = (String) arguments.get("env");

        switch (toolName) {
            case "dubbo_list_services": {
                String keyword = (String) arguments.get("keyword");
                List<String> services = profileRouter.listServices(env, keyword);
                JSONObject res = new JSONObject();
                res.put("env", profileRouter.resolveEnv(env));
                res.put("count", services.size());
                res.put("services", services);
                return res.toJSONString();
            }

            case "dubbo_get_service_detail": {
                String iface = (String) arguments.get("interfaceName");
                if (iface == null || iface.trim().isEmpty()) {
                    throw new IllegalArgumentException("interfaceName is required");
                }
                Map<String, Object> meta = profileRouter.getServiceMetadata(env, iface.trim());
                return JSON.toJSONString(meta);
            }

            case "dubbo_invoke": {
                String iface = (String) arguments.get("interfaceName");
                String method = (String) arguments.get("method");
                if (iface == null || method == null) {
                    throw new IllegalArgumentException("interfaceName and method are required");
                }

                String[] paramTypes = parseStringArray(arguments.get("paramTypes"));
                Object[] args = parseObjectArray(arguments.get("args"));

                Object result = profileRouter.invoke(env, iface.trim(), method.trim(), paramTypes, args);
                JSONObject wrapper = new JSONObject();
                wrapper.put("status", "SUCCESS");
                wrapper.put("env", profileRouter.resolveEnv(env));
                wrapper.put("interface", iface.trim());
                wrapper.put("method", method.trim());
                wrapper.put("data", result);
                return wrapper.toJSONString();
            }

            case "dubbo_list_envs": {
                JSONObject res = new JSONObject();
                res.put("activeEnv", configManager.getActiveEnv());
                res.put("configuredEnvs", profileRouter.getConfiguredEnvs());
                return res.toJSONString();
            }

            case "dubbo_switch_env": {
                String targetEnv = (String) arguments.get("env");
                if (targetEnv == null || targetEnv.trim().isEmpty()) {
                    throw new IllegalArgumentException("env is required");
                }
                configManager.setActiveEnv(targetEnv.trim());
                return "{\"status\":\"SUCCESS\",\"activeEnv\":\"" + targetEnv.trim() + "\"}";
            }

            default:
                throw new UnsupportedOperationException("Unknown tool: " + toolName);
        }
    }

    private String[] parseStringArray(Object obj) {
        if (obj == null) return new String[0];
        if (obj instanceof String[]) return (String[]) obj;
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            String[] arr = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i) != null ? list.get(i).toString() : "";
            }
            return arr;
        }
        return new String[]{obj.toString()};
    }

    private Object[] parseObjectArray(Object obj) {
        if (obj == null) return new Object[0];
        if (obj instanceof Object[]) return (Object[]) obj;
        if (obj instanceof List) {
            return ((List<?>) obj).toArray();
        }
        return new Object[]{obj};
    }
}
