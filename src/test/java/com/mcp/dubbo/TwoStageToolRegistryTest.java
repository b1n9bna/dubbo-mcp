package com.mcp.dubbo;

import com.mcp.dubbo.router.TwoStageToolRegistry;
import com.mcp.dubbo.security.SecurityGuard;
import com.mcp.dubbo.transport.protocol.McpTool;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TwoStageToolRegistryTest {

    @Test
    public void testRegisteredToolsCount() {
        TwoStageToolRegistry registry = new TwoStageToolRegistry();
        List<McpTool> tools = registry.getTools();
        Assert.assertNotNull(tools);
        Assert.assertEquals(5, tools.size());
    }

    @Test
    public void testListEnvsToolExecution() throws Exception {
        TwoStageToolRegistry registry = new TwoStageToolRegistry();
        String result = registry.executeTool("dubbo_list_envs", Collections.emptyMap());
        Assert.assertTrue(result.contains("activeEnv"));
        Assert.assertTrue(result.contains("configuredEnvs"));
    }

    @Test
    public void testSwitchEnvToolExecution() throws Exception {
        TwoStageToolRegistry registry = new TwoStageToolRegistry();
        Map<String, Object> args = new HashMap<>();
        args.put("env", "prod");
        String result = registry.executeTool("dubbo_switch_env", args);
        Assert.assertTrue(result.contains("SUCCESS"));
        Assert.assertTrue(result.contains("prod"));
    }

    @Test(expected = SecurityException.class)
    public void testSecurityGuardBlocksWriteMethodOnProd() {
        // prod is configured with readonly=true in dubbo-mcp.properties
        SecurityGuard.checkSafety("prod", "com.corp.OrderService", "deleteOrderById");
    }

    @Test
    public void testSecurityGuardAllowsReadMethodOnProd() {
        // 'getOrderById' matches the readonly regex
        SecurityGuard.checkSafety("prod", "com.corp.OrderService", "getOrderById");
    }

    @Test(expected = SecurityException.class)
    public void testSecurityGuardRejectsNullMethod() {
        SecurityGuard.checkSafety("test", "com.corp.OrderService", null);
    }

    @Test
    public void testSensitiveDataMasking() {
        String json = "{\"phone\":\"13812345678\",\"idCard\":\"110101199003072345\"}";
        String masked = SecurityGuard.maskSensitiveData(json);
        Assert.assertTrue(masked.contains("138****5678"));
        Assert.assertTrue(masked.contains("110101********2345"));
    }
}
