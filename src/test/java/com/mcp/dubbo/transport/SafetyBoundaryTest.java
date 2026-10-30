package com.mcp.dubbo.transport;

import com.alibaba.fastjson2.*;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.spi.RpcDriver;
import com.mcp.dubbo.router.ProfileRouter;
import org.junit.*;
import java.lang.reflect.Field;
import java.util.*;

public class SafetyBoundaryTest {
    private final String env = "safety_regression";
    private final ConfigManager config = ConfigManager.getInstance();
    private Map<String,RpcDriver> drivers;
    private String previousEnv;
    private boolean fail = true;
    private int calls;
    private static final String PHONE = "13812345678";

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception {
        previousEnv = config.getActiveEnv();
        config.getRawProperties().setProperty("env."+env+".type", "zookeeper");
        config.getRawProperties().setProperty("env."+env+".readonly", "true");
        config.getRawProperties().setProperty("env."+env+".data_masking", "true");
        Field field = ProfileRouter.class.getDeclaredField("driverCache"); field.setAccessible(true);
        drivers = (Map<String,RpcDriver>)field.get(ProfileRouter.getInstance());
        drivers.put(env, new RpcDriver() {
            public String getType() { return "fake"; }
            public Object invoke(String i,String m,String[] t,Object[] a) {
                calls++;
                if (fail) throw new IllegalStateException("Provider failed",new IllegalArgumentException("phone="+PHONE));
                return Collections.singletonMap("phone", PHONE);
            }
            public List<String> listServices(String k) { return Collections.emptyList(); }
            public Map<String,Object> getServiceMetadata(String i) { return Collections.emptyMap(); }
        });
    }
    @After public void cleanup() {
        drivers.remove(env); config.setActiveEnv(previousEnv);
        for (String key : new String[]{"type","readonly","data_masking"}) config.getRawProperties().remove("env."+env+"."+key);
    }
    private JSONObject call(String method, boolean explicitEnv) {
        JSONObject args = new JSONObject();
        args.put("interfaceName","com.example.Service"); args.put("method",method);
        if (explicitEnv) args.put("env",env);
        JSONObject params = new JSONObject(); params.put("name","dubbo_invoke"); params.put("arguments",args);
        JSONObject request = new JSONObject(); request.put("jsonrpc","2.0"); request.put("id",1);
        request.put("method","tools/call"); request.put("params",params);
        return JSON.parseObject(new StdioJsonRpcServer().processLine(request.toJSONString())).getJSONObject("result");
    }
    @Test public void malformedReadonlyPreventsBusinessCall() {
        config.getRawProperties().setProperty("env."+env+".readonly","tru");
        JSONObject result = call("deleteOrder",true);
        Assert.assertTrue(result.getBooleanValue("isError"));
        Assert.assertTrue(result.toJSONString().contains("Invalid boolean")); Assert.assertEquals(0,calls);
    }
    @Test public void errorAndRootCauseAreMasked() {
        JSONObject result = call("query",true);
        Assert.assertTrue(result.getBooleanValue("isError"));
        Assert.assertFalse(result.toJSONString().contains(PHONE));
        Assert.assertTrue(result.toJSONString().contains("138****5678"));
    }
    @Test public void defaultEnvironmentAlsoMasksErrors() {
        config.setActiveEnv(env);
        Assert.assertFalse(call("query",false).toJSONString().contains(PHONE));
    }
    @Test public void explicitMaskingOptOutIsPreserved() {
        config.getRawProperties().setProperty("env."+env+".data_masking","false");
        Assert.assertTrue(call("query",true).toJSONString().contains(PHONE));
    }
    @Test public void successfulResponsesRemainMasked() {
        fail=false;
        JSONObject result=call("query",true);
        Assert.assertFalse(result.getBooleanValue("isError"));
        Assert.assertFalse(result.toJSONString().contains(PHONE));
    }
    @Test public void invalidPrivacySettingPreventsBusinessCall() {
        config.getRawProperties().setProperty("env."+env+".data_masking","tru");
        Assert.assertTrue(call("query",true).getBooleanValue("isError")); Assert.assertEquals(0,calls);
    }
}
