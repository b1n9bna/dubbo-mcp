package com.mcp.dubbo.transport;
import com.alibaba.fastjson2.*;
import org.junit.*;
public class StdioJsonRpcServerTest {
    private final StdioJsonRpcServer server=new StdioJsonRpcServer();
    @Test public void malformedJsonGetsErrorAndServerRecovers() {
        JSONObject error=JSON.parseObject(server.processLine("{"));
        Assert.assertEquals(-32700,error.getJSONObject("error").getIntValue("code")); Assert.assertTrue(error.containsKey("id"));
        Assert.assertNotNull(JSON.parseObject(server.processLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).get("result"));
    }
    @Test public void invalidToolArgumentsReceiveResponse() {
        JSONObject response=JSON.parseObject(server.processLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"dubbo_invoke\",\"arguments\":[]}}"));
        Assert.assertEquals(-32602,response.getJSONObject("error").getIntValue("code"));
    }
    @Test public void unknownEnvironmentIsToolError() {
        JSONObject response=JSON.parseObject(server.processLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"dubbo_switch_env\",\"arguments\":{\"env\":\"typo\"}}}"));
        Assert.assertTrue(response.getJSONObject("result").getBooleanValue("isError"));
    }
    @Test public void notificationsNeverExecuteTools() {
        Assert.assertNull(server.processLine("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"dubbo_switch_env\",\"arguments\":{\"env\":\"typo\"}}}"));
    }
}
