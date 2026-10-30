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
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> paths = new ArrayList<>();
    @Before public void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); paths.add(path);
            if (path.endsWith("/test")) posts.incrementAndGet();
            String response; int status = 200;
            if (path.equals("/") && mode.equals("prefix-outage")) { status=503; response="temporary unavailable"; }
            else if (path.equals("/") && mode.equals("prefix-login")) response="<html>temporary login page</html>";
            else if (path.equals("/")) response = "<script src=/dubbo-admin/js/app.js></script>";
            else if (mode.equals("prefix-login")) { status=404; response="{}"; }
            else if (!"test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")) || !"session=example".equals(exchange.getRequestHeaders().getFirst("Cookie"))) { status=401; response="{}"; }
            else if (mode.equals("unauthorized")) { status=401; response="{}"; }
            else if (mode.equals("server-error")) { status=500; response="private backend error"; }
            else if (mode.startsWith("oversized-")) response="[\""+String.join("",Collections.nCopies(4096,"x"))+"\"]";
            else if (mode.equals("html")) response="<html>login</html>";
            else if (mode.equals("login-fallback") && path.endsWith("/services")) { status=404; response="{}"; }
            else if (mode.equals("metadata-broken") && path.contains("/service/")) { status=500; response="{}"; }
            else if ((path.endsWith("/services") || path.endsWith("/service")) && (mode.equals("two-pages") || mode.equals("repeat-page") || mode.equals("unknown-cursor") || mode.equals("second-page-broken") || mode.equals("mybatis-pages"))) {
                boolean second = String.valueOf(exchange.getRequestURI().getQuery()).contains("page=1")
                        || String.valueOf(exchange.getRequestURI().getQuery()).contains("current=2");
                String item = "{\"service\":\"com.example.Service\",\"version\":\"1\",\"group\":\"" + (second ? "red" : "blue") + "\"}";
                if (mode.equals("second-page-broken") && second) { status=404; response="{}"; }
                else if (mode.equals("unknown-cursor")) response="{\"content\":["+item+"],\"totalPages\":2}";
                else if (mode.equals("mybatis-pages")) response="{\"data\":{\"records\":["+item+"],\"pages\":2,\"current\":"+(second?2:1)+"}}";
                else response="{\"content\":["+item+"],\"totalPages\":2,\"number\":"+(second && !mode.equals("repeat-page")?1:0)+"}";
            }
            else if (path.endsWith("/services")) response=mode.equals("shape")?"{\"error\":true}":mode.equals("paged")?"{\"content\":[\"com.example.Service\"]}":(mode.equals("search-broken") || mode.equals("search-unsupported"))?"[\"blue/com.example.Service:1\"]":"[\"com.example.Service\"]";
            else if (path.endsWith("/service")) {
                if (mode.equals("search-broken") || mode.equals("search-unsupported")) { status = mode.equals("search-broken") ? 500 : 404; response = "{}"; }
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
            if (mode.equals("oversized-gzip")) {
                java.io.ByteArrayOutputStream compressed=new java.io.ByteArrayOutputStream();
                try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(bytes);}
                bytes=compressed.toByteArray(); exchange.getResponseHeaders().set("Content-Encoding","gzip");
            }
            exchange.sendResponseHeaders(status,mode.equals("oversized-chunked")?0:bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        ConfigManager.getInstance().getRawProperties().setProperty("env.mock.admin_url", "http://127.0.0.1:"+server.getAddress().getPort());
        ConfigManager.getInstance().getRawProperties().setProperty("env.mock.admin_cookie", "{\"cookie\":\"session=example\",\"authorization\":\"test-token\"}");
        driver=new DubboAdminDriver("mock");
    }
    @After public void teardown() { server.stop(0); ConfigManager.getInstance().getRawProperties().remove("env.mock.admin_url"); ConfigManager.getInstance().getRawProperties().remove("env.mock.admin_cookie"); ConfigManager.getInstance().getRawProperties().remove("env.mock.admin_max_response_bytes"); }
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
    @Test public void fallsBackToServicesWhenSearchIsUnsupported() throws Exception {
        mode = "search-unsupported";
        Map<String, Object> detail = driver.getServiceMetadata("com.example.Service");
        Assert.assertEquals("blue/com.example.Service:1", detail.get("service"));
    }
    @Test public void unpacksStringifiedDtoArgumentIntoMap() throws Exception {
        Object result = driver.invoke("com.example.Service", "query", null, new Object[]{"{\"id\":123}"}, null, null);
        Assert.assertTrue(((JSONObject)result).getBooleanValue("success"));
        Object param = invocation.getJSONArray("params").get(0);
        Assert.assertTrue("Parameter must be unpacked into a JSONObject", param instanceof JSONObject);
        Assert.assertEquals(123, ((JSONObject) param).getIntValue("id"));
    }
    @Test public void testInvokeWithAttachmentsPropagatesToPayload() throws Exception {
        Map<String, String> attachments = new HashMap<>();
        attachments.put("tenantId", "tenant_999");
        attachments.put("mcp-caller", "dubbo-mcp");
        Object result = driver.invoke("com.example.Service", "query", null, new Object[]{new HashMap<>()}, null, null, attachments);
        Assert.assertTrue(((JSONObject)result).getBooleanValue("success"));
        Assert.assertNotNull(invocation.getJSONObject("attachments"));
        Assert.assertEquals("tenant_999", invocation.getJSONObject("attachments").getString("tenantId"));
        Assert.assertEquals("dubbo-mcp", invocation.getJSONObject("attachments").getString("mcp-caller"));
    }
    @Test public void testTypeCoercionInInvoke() throws Exception {
        Object result = driver.invoke("com.example.Service", "query", new String[]{"java.lang.Long"}, new Object[]{"202610080001"}, null, null, null);
        Assert.assertTrue(((JSONObject)result).getBooleanValue("success"));
        Object param = invocation.getJSONArray("params").get(0);
        Assert.assertEquals(202610080001L, ((Number) param).longValue());
    }
    @Test public void testApplicationNameExtractedAndSearchable() throws Exception {
        String json = "[{\"service\":\"cn.corp.oms.OrderService\",\"application\":\"oms-provider\"}]";
        java.lang.reflect.Method m = DubboAdminDriver.class.getDeclaredMethod("extractServiceNames", Object.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) m.invoke(null, JSON.parse(json));
        Assert.assertEquals(1, list.size());
        Assert.assertEquals("cn.corp.oms.OrderService (app: oms-provider)", list.get(0));
    }
    @Test public void testEnhanceAdminExceptionCapturesAvailableMethods() {
        Exception raw = new RuntimeException("com.alibaba.dubbo.rpc.RpcException: Failed to invoke. Available methods: [getOrderDetail(cn.corp.oms.dto.OrderRequest)]");
        Exception enhanced = DubboAdminDriver.enhanceAdminException(raw, "cn.corp.oms.OrderService", "getOrderDetail");
        Assert.assertTrue(enhanced instanceof IllegalArgumentException);
        Assert.assertTrue(enhanced.getMessage().contains("Dubbo method signature mismatch"));
        Assert.assertTrue(enhanced.getMessage().contains("Available methods: [getOrderDetail(cn.corp.oms.dto.OrderRequest)]"));
    }

    @Test public void allPagesAreReadBeforeDecidingGroup() throws Exception {
        for (String pagedMode : Arrays.asList("two-pages", "mybatis-pages")) {
            mode = pagedMode;
            Assert.assertEquals(Collections.singletonList("com.example.Service"), driver.listServices(""));
            try { driver.invoke("com.example.Service", "query", new String[0], new Object[0]); Assert.fail(); }
            catch (IllegalArgumentException expected) {
                Assert.assertTrue(expected.getMessage().contains("blue"));
                Assert.assertTrue(expected.getMessage().contains("red"));
                Assert.assertEquals(0, calls.get());
            }
        }
    }
    @Test public void explicitGroupCanBeFoundOnSecondPage() throws Exception {
        mode="two-pages";
        Assert.assertEquals("red/com.example.Service:1", driver.getServiceMetadata("com.example.Service", "1", "red").get("service"));
    }
    @Test public void incompletePaginationCannotSelectProvider() throws Exception {
        for (String badMode : Arrays.asList("repeat-page", "unknown-cursor", "second-page-broken")) {
            mode=badMode;
            try { driver.invoke("com.example.Service", "query", new String[0], new Object[0]); Assert.fail(badMode); }
            catch (Exception expected) {
                Assert.assertTrue(expected.getMessage().contains("Incomplete")); Assert.assertEquals(0,calls.get());
            }
        }
    }
    @Test public void loginUsesSameFallbackAsDiscovery() throws Exception {
        mode="login-fallback";
        driver.verifyCredential("{\"cookie\":\"session=example\",\"authorization\":\"test-token\"}");
        Assert.assertEquals(1, driver.listServices("").size());
    }
    @Test public void authenticationAndServerErrorsArePreservedWithoutFallback() {
        for (String badMode : Arrays.asList("unauthorized", "server-error", "search-broken")) {
            mode=badMode; paths.clear();
            try { driver.getServiceMetadata("com.example.Service"); Assert.fail(); }
            catch (Exception expected) {
                Assert.assertTrue(expected.getMessage().contains(badMode.equals("unauthorized")?"HTTP 401":"HTTP 500"));
                Assert.assertFalse(expected.getMessage().contains("No matching"));
                Assert.assertFalse(paths.contains("/dubbo-admin/api/dev/services"));
            }
        }
    }
    @Test public void explicitTypesDoNotRequireMetadata() throws Exception {
        mode="metadata-broken";
        Object result=driver.invoke("blue/com.example.Service:1", "query", new String[]{"java.lang.Long"}, new Object[]{"123"});
        Assert.assertTrue(((JSONObject)result).getBooleanValue("success"));
        Assert.assertEquals(1,calls.get());
        Assert.assertFalse(paths.contains("/dubbo-admin/api/dev/service/blue*com.example.Service:1"));
    }
    @Test public void inferredTypesStillRequireMetadata() throws Exception {
        mode="metadata-broken";
        try { driver.invoke("blue/com.example.Service:1", "query", null, new Object[]{new HashMap<>()}); Assert.fail(); }
        catch(Exception expected) { Assert.assertTrue(expected.getMessage().contains("HTTP 500")); Assert.assertEquals(0,calls.get()); }
    }
    @Test public void prefixProbeRecoversAfterTemporaryFailure() {
        mode="prefix-outage";
        try {driver.listServices("");Assert.fail();}
        catch(Exception expected) {Assert.assertTrue(expected.getMessage().contains("HTTP 503"));}
        mode="ok";
        Assert.assertEquals(Collections.singletonList("com.example.Service"),driver.listServices(""));
        Assert.assertEquals(2,Collections.frequency(paths,"/"));
        Assert.assertTrue(paths.contains("/dubbo-admin/api/dev/services"));
    }
    @Test public void successfulTemporaryLoginPageDoesNotPoisonPrefix() {
        mode="prefix-login";
        try {driver.listServices("");Assert.fail();} catch(Exception expected) {Assert.assertTrue(expected.getMessage().contains("HTTP 404"));}
        mode="ok";
        Assert.assertEquals(Collections.singletonList("com.example.Service"),driver.listServices(""));
        Assert.assertTrue(Collections.frequency(paths,"/")>=2);
    }
    @Test public void explicitRootPrefixSkipsHomepageProbe() {
        ConfigManager.getInstance().getRawProperties().setProperty("env.mock.admin_api_prefix", "");
        try {
            DubboAdminDriver rootDriver=new DubboAdminDriver("mock");
            Assert.assertEquals(Collections.singletonList("com.example.Service"),rootDriver.listServices(""));
            Assert.assertFalse(paths.contains("/"));
        } finally {ConfigManager.getInstance().getRawProperties().remove("env.mock.admin_api_prefix");}
    }
    @Test public void oversizedFixedChunkedAndGzipResponsesFailWithoutRetries() throws Exception {
        ConfigManager.getInstance().getRawProperties().setProperty("env.mock.admin_max_response_bytes","256");
        DubboAdminDriver bounded=new DubboAdminDriver("mock");
        for (String oversizedMode:Arrays.asList("oversized-fixed","oversized-chunked","oversized-gzip")) {
            mode=oversizedMode;int before=posts.get();
            try {bounded.invoke("blue/com.example.Service:1","query",new String[0],new Object[0]);Assert.fail();}
            catch(java.io.IOException expected){Assert.assertTrue(expected.getMessage().contains("byte limit"));}
            Assert.assertEquals(before+1,posts.get());
        }
        mode="ok";
        Assert.assertEquals(Collections.singletonList("com.example.Service"),bounded.listServices(""));
    }
}
