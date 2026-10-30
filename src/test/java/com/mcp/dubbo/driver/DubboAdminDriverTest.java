package com.mcp.dubbo.driver;
import com.alibaba.fastjson2.*;
import com.mcp.dubbo.config.ConfigManager;
import com.sun.net.httpserver.*;
import org.junit.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;

public class DubboAdminDriverTest {
    private HttpServer server;
    private DubboAdminDriver driver;
    private volatile String mode = "ok";
    private JSONObject invocation;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> paths = new ArrayList<>();
    @Before public void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); paths.add(path);
            String response; int status = 200;
            if (path.equals("/")) response = "<script src=/dubbo-admin/js/app.js></script>";
            else if (!"test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")) || !"session=example".equals(exchange.getRequestHeaders().getFirst("Cookie"))) { status=401; response="{}"; }
            else if (mode.equals("unauthorized")) { status=401; response="{}"; }
            else if (mode.equals("server-error")) { status=500; response="private backend error"; }
            else if (mode.equals("html")) response="<html>login</html>";
            else if (path.endsWith("/services")) response=mode.equals("shape")?"{\"error\":true}":mode.equals("paged")?"{\"content\":[\"com.example.Service\"]}":mode.equals("search-broken")?"[\"blue/com.example.Service:1\"]":"[\"com.example.Service\"]";
            else if (path.endsWith("/service")) {
                if (mode.equals("search-broken")) { status = 500; response = "{}"; }
                else response=mode.equals("ambiguous")?"[{\"service\":\"com.example.Service\",\"version\":\"1\"},{\"service\":\"com.example.Service\",\"version\":\"2\"}]":mode.equals("paged")?"{\"content\":[{\"service\":\"com.example.Service\",\"version\":\"1\",\"group\":\"blue\"}],\"pageable\":{},\"totalElements\":1}":"[{\"service\":\"com.example.Service\",\"version\":\"1\",\"group\":\"blue\"}]";
            }
            else if (path.contains("/service/")) response="{\"service\":\"blue/com.example.Service:1\",\"metadata\":{\"methods\":[{\"name\":\"query\",\"parameterTypes\":[\"com.example.QueryDTO\"]}]}}";
            else if (path.endsWith("/test")) {
                calls.incrementAndGet();
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(); int b;
                while((b=exchange.getRequestBody().read())!=-1) bytes.write(b);
                invocation=JSON.parseObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                response="{\"success\":true,\"data\":[]}";
            } else { status=404; response="{}"; }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        ConfigManager.getInstance().getRawProperties().setProperty("env.mock.admin_url", "http://127.0.0.1:"+server.getAddress().getPort());
        ConfigManager.getInstance().getRawProperties().setProperty("env.mock.admin_cookie", "{\"cookie\":\"session=example\",\"authorization\":\"test-token\"}");
        driver=new DubboAdminDriver("mock");
    }
    @After public void teardown() { server.stop(0); ConfigManager.getInstance().getRawProperties().remove("env.mock.admin_url"); ConfigManager.getInstance().getRawProperties().remove("env.mock.admin_cookie"); }
    @Test public void discoversContextAndUsesActualServiceKeyAndDto() throws Exception {
        Assert.assertEquals(Collections.singletonList("com.example.Service"),driver.listServices("example"));
        Map<String,Object> detail=driver.getServiceMetadata("com.example.Service");
        Assert.assertEquals("blue/com.example.Service:1",detail.get("service"));
        Object result=driver.invoke("com.example.Service","query",null,new Object[]{new HashMap<>()},null,null);
        Assert.assertTrue(((JSONObject)result).getBooleanValue("success"));
        Assert.assertEquals("blue/com.example.Service:1", invocation.getString("service"));
        Assert.assertEquals("com.example.QueryDTO",invocation.getJSONArray("parameterTypes").getString(0));
        Assert.assertTrue(paths.contains("/dubbo-admin/api/dev/service/blue*com.example.Service:1"));
        Assert.assertEquals(1,calls.get());
    }
    @Test public void supportsPagedDubboAdminResponses() throws Exception {
        mode = "paged";
        Assert.assertEquals(Collections.singletonList("com.example.Service"), driver.listServices("example"));
        Map<String, Object> detail = driver.getServiceMetadata("com.example.Service");
        Assert.assertEquals("blue/com.example.Service:1", detail.get("service"));
        Object result = driver.invoke("com.example.Service", "query", null, new Object[]{new HashMap<>()}, null, null);
        Assert.assertTrue(((JSONObject)result).getBooleanValue("success"));
        Assert.assertEquals("blue/com.example.Service:1", invocation.getString("service"));
    }
    @Test public void failuresAreNotEmptyResults() {
        for(String failure:Arrays.asList("unauthorized","server-error","html","shape")) {
            mode=failure;
            try { driver.listServices(""); Assert.fail(failure); } catch(RuntimeException expected) { Assert.assertFalse(expected.getMessage().contains("private backend error")); }
        }
    }
    @Test public void ambiguousVersionDoesNotInvoke() throws Exception {
        mode="ambiguous";
        try { driver.invoke("com.example.Service","query",new String[]{"com.example.QueryDTO"},new Object[]{new HashMap<>()}); Assert.fail(); }
        catch(IllegalArgumentException expected) { Assert.assertEquals(0,calls.get()); }
    }
    @Test public void rawAuthorizationIsNotChangedToBearer() {
        Request.Builder b=new Request.Builder().url("http://localhost/");
        DubboAdminDriver.attachCredential(b,"Authorization: token-with-padding=");
        Assert.assertEquals("token-with-padding=",b.build().header("Authorization"));
    }
    @Test public void supportsNestedApiEnvelopeResponses() {
        JSONObject envelope = JSON.parseObject("{\"code\":200,\"message\":\"ok\",\"data\":{\"content\":[\"com.corp.ServiceA\",\"com.corp.ServiceB\"]}}");
        JSONArray array = DubboAdminDriver.toServiceArray(envelope);
        Assert.assertNotNull(array);
        Assert.assertEquals(2, array.size());
    }
    @Test public void extractsMetadataFromDataEnvelope() {
        JSONObject envelope = JSON.parseObject("{\"code\":200,\"data\":{\"metadata\":{\"methods\":[{\"name\":\"test\"}]}}}");
        JSONObject meta = DubboAdminDriver.extractMetadata(envelope);
        Assert.assertNotNull(meta);
        Assert.assertTrue(meta.containsKey("methods"));
    }
    @Test public void skipsDirtyAndNullEntriesInServiceArray() {
        JSONArray dirty = JSON.parseArray("[null, {}, {\"invalid\":123}, {\"service\":\"com.corp.ValidService\"}]");
        JSONArray unwrapped = DubboAdminDriver.toServiceArray(dirty);
        Assert.assertNotNull(unwrapped);
        Assert.assertEquals(4, unwrapped.size());
    }
    @Test public void supportsVariousPaginationFormats() {
        // MyBatis-Plus style
        JSONObject mybatis = JSON.parseObject("{\"records\":[\"com.corp.ServiceA\"],\"total\":1,\"current\":1}");
        Assert.assertEquals(1, DubboAdminDriver.toServiceArray(mybatis).size());

        // PageHelper style
        JSONObject pagehelper = JSON.parseObject("{\"list\":[\"com.corp.ServiceB\"],\"pageNum\":1,\"pageSize\":10}");
        Assert.assertEquals(1, DubboAdminDriver.toServiceArray(pagehelper).size());

        // Single candidate array fallback
        JSONObject custom = JSON.parseObject("{\"customItems\":[\"com.corp.ServiceC\"],\"status\":\"ok\"}");
        Assert.assertEquals(1, DubboAdminDriver.toServiceArray(custom).size());

        // Stringified JSON
        String jsonStr = "{\"content\":[\"com.corp.ServiceD\"]}";
        Assert.assertEquals(1, DubboAdminDriver.toServiceArray(jsonStr).size());
    }
    @Test public void extractsStringifiedMetadata() {
        JSONObject detail = JSON.parseObject("{\"service\":\"com.corp.Service:1\",\"metadata\":\"{\\\"methods\\\":[{\\\"name\\\":\\\"findUser\\\"}]}\"}");
        JSONObject meta = DubboAdminDriver.extractMetadata(detail);
        Assert.assertNotNull(meta);
        Assert.assertTrue(meta.containsKey("methods"));
    }
    @Test public void fallsBackToServicesWhenSearchFails() throws Exception {
        mode = "search-broken";
        Map<String, Object> detail = driver.getServiceMetadata("com.example.Service");
        Assert.assertEquals("blue/com.example.Service:1", detail.get("service"));
    }
}
