package com.mcp.dubbo.router;

import com.alibaba.fastjson2.*;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.spi.RpcDriver;
import org.junit.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class InvocationValidationTest {
    private final ConfigManager config = ConfigManager.getInstance();
    private Map<String, RpcDriver> drivers;
    private int calls;
    private Object response;
    private String oldLimit;
    private final String env = "validation_regression";

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception {
        oldLimit = config.getRawProperties().getProperty("mcp.max_response_bytes");
        config.getRawProperties().setProperty("mcp.max_response_bytes", "24576");
        config.getRawProperties().setProperty("env." + env + ".type", "zookeeper");
        config.getRawProperties().setProperty("env." + env + ".readonly", "false");
        Field field = ProfileRouter.class.getDeclaredField("driverCache"); field.setAccessible(true);
        drivers = (Map<String,RpcDriver>) field.get(ProfileRouter.getInstance());
        drivers.put(env, new RpcDriver() {
            public String getType() { return "fake"; }
            public Object invoke(String iface, String method, String[] types, Object[] args) { calls++; return response; }
            public List<String> listServices(String keyword) { return Collections.emptyList(); }
            public Map<String,Object> getServiceMetadata(String iface) { return Collections.emptyMap(); }
        });
    }
    @After public void cleanup() {
        drivers.remove(env);
        config.getRawProperties().remove("env." + env + ".type");
        config.getRawProperties().remove("env." + env + ".readonly");
        if (oldLimit == null) config.getRawProperties().remove("mcp.max_response_bytes");
        else config.getRawProperties().setProperty("mcp.max_response_bytes", oldLimit);
    }
    private Map<String,Object> arguments() {
        Map<String,Object> args = new HashMap<>(); args.put("env", env);
        args.put("interfaceName", "com.example.Service"); args.put("method", "createOrder"); return args;
    }
    @Test public void invalidProjectionDoesNotExecuteWrite() throws Exception {
        Map<String,Object> args = arguments(); args.put("jsonPath", "$[");
        try { new TwoStageToolRegistry().executeTool("dubbo_invoke", args); Assert.fail(); }
        catch (IllegalArgumentException expected) { Assert.assertEquals(0, calls); }
    }
    @Test public void compiledProjectionStillExtractsResult() throws Exception {
        response = Collections.singletonMap("id", "order-1");
        Map<String,Object> args = arguments(); args.put("jsonPath", "$.id");
        JSONObject result = JSON.parseObject(new TwoStageToolRegistry().executeTool("dubbo_invoke", args));
        Assert.assertEquals("order-1", result.getString("data")); Assert.assertEquals(1, calls);
    }
    @Test public void utf8BytesTriggerTruncationAndFitLimit() throws Exception {
        response = Collections.singletonMap("message", String.join("", Collections.nCopies(10000, "中文😀")));
        String result = new TwoStageToolRegistry().executeTool("dubbo_invoke", arguments());
        Assert.assertEquals("SUCCESS_TRUNCATED", JSON.parseObject(result).getString("status"));
        Assert.assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 24576);
    }
    @Test public void fallbackEnvelopeAlsoFitsUtf8Limit() {
        JSONObject wrapper = new JSONObject();
        wrapper.put("interface", String.join("", Collections.nCopies(1000, "中")));
        wrapper.put("data", String.join("", Collections.nCopies(1000, "😀")));
        String result = TwoStageToolRegistry.formatTruncatedPayload(wrapper, 7000, 512);
        Assert.assertEquals("SUCCESS_TRUNCATED", JSON.parseObject(result).getString("status"));
        Assert.assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 512);
    }
    @Test public void impossibleLimitIsRejectedBeforeWrite() throws Exception {
        config.getRawProperties().setProperty("mcp.max_response_bytes", "1");
        try { new TwoStageToolRegistry().executeTool("dubbo_invoke", arguments()); Assert.fail(); }
        catch (IllegalArgumentException expected) { Assert.assertEquals(0, calls); }
    }
}
