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
        listProps.put("keyword", paramProp("string", "Keyword to filter Dubbo service interface names (e.g. 'order', 'user')"));
        listProps.put("env", paramProp("string", "Target environment profile (test / uat / prod). Defaults to active env"));
        listServicesSchema.put("properties", listProps);

        registeredTools.add(new McpTool(
                "dubbo_list_services",
                "Search and list registered Dubbo RPC services by keyword. Use this tool to discover available microservice interfaces in the cluster.",
                listServicesSchema
        ));

        // 2. dubbo_get_service_detail
        Map<String, Object> detailSchema = new LinkedHashMap<>();
        detailSchema.put("type", "object");
        Map<String, Object> detailProps = new LinkedHashMap<>();
        detailProps.put("interfaceName", paramProp("string", "Fully qualified interface name (e.g. 'cn.corp.trade.OrderService') or service key"));
        detailProps.put("version", paramProp("string", "Optional Dubbo service version (e.g. '1.0.0')"));
        detailProps.put("group", paramProp("string", "Optional Dubbo service group"));
        detailProps.put("env", paramProp("string", "Target environment profile (test / uat / prod)"));
        detailSchema.put("properties", detailProps);
        detailSchema.put("required", Collections.singletonList("interfaceName"));

        registeredTools.add(new McpTool(
                "dubbo_get_service_detail",
                "Retrieve available methods, signatures, and parameter DTO structures for a Dubbo interface. Use this before invoking to inspect parameter schemas.",
                detailSchema
        ));

        // 3. dubbo_invoke
        Map<String, Object> invokeSchema = new LinkedHashMap<>();
        invokeSchema.put("type", "object");
        Map<String, Object> invokeProps = new LinkedHashMap<>();
        invokeProps.put("interfaceName", paramProp("string", "Target interface name (e.g. 'cn.corp.trade.OrderService')"));
        invokeProps.put("method", paramProp("string", "Target method name to invoke"));
        invokeProps.put("paramTypes", arrayProp("string", "Optional parameter types array (e.g. ['java.lang.String']). If omitted, auto-inferred from metadata."));
        invokeProps.put("args", paramProp("array", "Arguments array matching method parameters (supports primitives and JSON objects)"));
        invokeProps.put("version", paramProp("string", "Optional Dubbo service version"));
        invokeProps.put("group", paramProp("string", "Optional Dubbo service group"));
        invokeProps.put("env", paramProp("string", "Target environment profile (test / uat / prod)"));
        invokeSchema.put("properties", invokeProps);
        invokeSchema.put("required", Arrays.asList("interfaceName", "method"));

        registeredTools.add(new McpTool(
                "dubbo_invoke",
                "Invoke a Dubbo RPC service using generic invocation without any JAR dependencies. Returns result JSON. Read-only checks are enforced in production.",
                invokeSchema
        ));

        // 4. dubbo_list_envs
        Map<String, Object> emptySchema = new LinkedHashMap<>();
        emptySchema.put("type", "object");

        registeredTools.add(new McpTool(
                "dubbo_list_envs",
                "List all configured enterprise environments (test, uat, prod) and show the currently active one.",
                emptySchema
        ));

        // 5. dubbo_switch_env
        Map<String, Object> switchSchema = new LinkedHashMap<>();
        switchSchema.put("type", "object");
        switchSchema.put("properties", Collections.singletonMap("env", paramProp("string", "Environment name to switch to (e.g. 'test', 'uat', 'prod')")));
        switchSchema.put("required", Collections.singletonList("env"));

        registeredTools.add(new McpTool(
                "dubbo_switch_env",
                "Switch the active default environment for subsequent invocations.",
                switchSchema
        ));
    }

    private static Map<String, Object> paramProp(String type, String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", type);
        prop.put("description", description);
        return prop;
    }

    private static Map<String, Object> arrayProp(String itemType, String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        prop.put("items", Collections.singletonMap("type", itemType));
        prop.put("description", description);
        return prop;
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
                Map<String, Object> meta = profileRouter.getServiceMetadata(env, iface.trim(), (String) arguments.get("version"), (String) arguments.get("group"));
                return JSON.toJSONString(meta);
            }

            case "dubbo_invoke": {
                String iface = (String) arguments.get("interfaceName");
                String method = (String) arguments.get("method");
                if (iface == null || iface.trim().isEmpty() || method == null || method.trim().isEmpty()) {
                    throw new IllegalArgumentException("interfaceName and method are required");
                }

                boolean hasParamTypes = arguments.get("paramTypes") != null;
                String[] rawParamTypes = hasParamTypes ? parseStringArray(arguments.get("paramTypes")) : null;
                Object[] args = parseObjectArray(arguments.get("args"));
                if (hasParamTypes && rawParamTypes.length != args.length)
                    throw new IllegalArgumentException("paramTypes count (" + rawParamTypes.length + ") must match args count (" + args.length + ")");
                String[] paramTypes = hasParamTypes ? rawParamTypes : null;
                for (int i = 0; i < args.length; i++) {
                    // Legacy clients sometimes wrap DTO JSON in a string. Keep actual String parameters intact.
                    if (paramTypes != null && args[i] instanceof String && !"java.lang.String".equals(paramTypes[i])) {
                        String value = ((String) args[i]).trim();
                        if (value.startsWith("{") || value.startsWith("[")) {
                            try { args[i] = JSON.parse(value); }
                            catch (Exception e) { throw new IllegalArgumentException("Invalid JSON argument at index " + i); }
                        }
                    }
                }
                String version = (String) arguments.get("version");
                String group = (String) arguments.get("group");

                Object result = profileRouter.invoke(env, iface.trim(), method.trim(), paramTypes, args, version, group);
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
                configManager.setActiveEnv(profileRouter.resolveEnv(targetEnv));
                JSONObject res = new JSONObject();
                res.put("status", "SUCCESS");
                res.put("activeEnv", targetEnv.trim());
                return res.toJSONString();
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
                if (!(list.get(i) instanceof String) || ((String) list.get(i)).trim().isEmpty())
                    throw new IllegalArgumentException("paramTypes entries must be nonempty strings");
                arr[i] = ((String)list.get(i)).trim();
            }
            return arr;
        }
        throw new IllegalArgumentException("paramTypes must be an array of strings");
    }

    private Object[] parseObjectArray(Object obj) {
        if (obj == null) return new Object[0];
        if (obj instanceof Object[]) return (Object[]) obj;
        if (obj instanceof List) {
            return ((List<?>) obj).toArray();
        }
        throw new IllegalArgumentException("args must be an array");
    }
}
