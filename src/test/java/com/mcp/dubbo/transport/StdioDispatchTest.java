package com.mcp.dubbo.transport;

import com.alibaba.fastjson2.*;
import com.mcp.dubbo.config.ConfigManager;
import com.mcp.dubbo.driver.spi.RpcDriver;
import com.mcp.dubbo.router.ProfileRouter;
import org.junit.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class StdioDispatchTest {
    private final ConfigManager config=ConfigManager.getInstance();
    private final String env="dispatch_regression";
    private Map<String,RpcDriver> drivers;
    private final CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1), finished=new CountDownLatch(1);
    private final AtomicInteger calls=new AtomicInteger();
    private PipedOutputStream input;
    private Thread server;
    private final BlockingQueue<String> responses=new LinkedBlockingQueue<>();
    private final Map<String,String> saved=new HashMap<>();

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception {
        for(String key:Arrays.asList("mcp.request_timeout_ms","mcp.shutdown_timeout_ms","mcp.max_request_bytes","env."+env+".type","env."+env+".readonly"))
            saved.put(key,config.getRawProperties().getProperty(key));
        config.getRawProperties().setProperty("mcp.request_timeout_ms","10000");
        config.getRawProperties().setProperty("mcp.shutdown_timeout_ms","100");
        config.getRawProperties().setProperty("env."+env+".type","zookeeper");
        config.getRawProperties().setProperty("env."+env+".readonly","true");
        Field field=ProfileRouter.class.getDeclaredField("driverCache");field.setAccessible(true);
        drivers=(Map<String,RpcDriver>)field.get(ProfileRouter.getInstance());
        drivers.put(env,new RpcDriver() {
            public String getType(){return "fake";}
            public Object invoke(String i,String m,String[] t,Object[] a)throws Exception {
                calls.incrementAndGet();started.countDown();
                try {release.await();return "ok";} finally {finished.countDown();}
            }
            public List<String> listServices(String k){return Collections.emptyList();}
            public Map<String,Object> getServiceMetadata(String i){return Collections.emptyMap();}
        });
    }
    private void start() throws Exception {
        PipedInputStream pipe=new PipedInputStream(65536);input=new PipedOutputStream(pipe);
        OutputStream capture=new OutputStream() {
            private final ByteArrayOutputStream line=new ByteArrayOutputStream();
            public void write(int b) {
                if(b=='\n'){responses.add(new String(line.toByteArray(),StandardCharsets.UTF_8));line.reset();}
                else line.write(b);
            }
        };
        PrintStream output=new PrintStream(capture,true,"UTF-8");
        server=new Thread(()->new StdioJsonRpcServer().serve(pipe,output));server.setDaemon(true);server.start();
    }
    private void send(String line)throws Exception {input.write((line+"\n").getBytes(StandardCharsets.UTF_8));input.flush();}
    private void invoke(int id)throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"method\":\"tools/call\",\"params\":{\"name\":\"dubbo_invoke\",\"arguments\":{\"env\":\""+env+"\",\"interfaceName\":\"com.example.Service\",\"method\":\"query\"}}}");
    }
    private JSONObject response()throws Exception {
        String line=responses.poll(2,TimeUnit.SECONDS);Assert.assertNotNull("Protocol response missing",line);return JSON.parseObject(line);
    }
    @After public void cleanup()throws Exception {
        release.countDown();if(input!=null)input.close();if(server!=null){server.join(2000);Assert.assertFalse("Server did not exit",server.isAlive());}
        drivers.remove(env);
        for(Map.Entry<String,String> entry:saved.entrySet()) {
            if(entry.getValue()==null)config.getRawProperties().remove(entry.getKey());
            else config.getRawProperties().setProperty(entry.getKey(),entry.getValue());
        }
    }
    @Test public void pingDoesNotWaitForBusinessCall()throws Exception {
        start();invoke(1);Assert.assertTrue(started.await(2,TimeUnit.SECONDS));
        send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        Assert.assertEquals(2,response().getIntValue("id"));
        release.countDown();Assert.assertEquals(1,response().getIntValue("id"));
    }
    @Test public void cancellationSuppressesResponseAndFreesWorker()throws Exception {
        start();invoke(1);Assert.assertTrue(started.await(2,TimeUnit.SECONDS));
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}");
        Assert.assertTrue(finished.await(2,TimeUnit.SECONDS));
        send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        Assert.assertEquals(2,response().getIntValue("id"));Assert.assertNull(responses.poll(100,TimeUnit.MILLISECONDS));
    }
    @Test public void queuedCancellationPreventsInvocation()throws Exception {
        start();invoke(1);Assert.assertTrue(started.await(2,TimeUnit.SECONDS));invoke(2);
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":2}}");
        send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");Assert.assertEquals(3,response().getIntValue("id"));
        release.countDown();Assert.assertEquals(1,response().getIntValue("id"));
        input.close();server.join(2000);Assert.assertEquals(1,calls.get());
    }
    @Test public void timeoutRespondsOnceAndInterruptsWorker()throws Exception {
        config.getRawProperties().setProperty("mcp.request_timeout_ms","300");start();invoke(1);
        JSONObject result=response();Assert.assertEquals(1,result.getIntValue("id"));Assert.assertTrue(result.getJSONObject("result").getBooleanValue("isError"));
        Assert.assertTrue(finished.await(2,TimeUnit.SECONDS));Assert.assertNull(responses.poll(100,TimeUnit.MILLISECONDS));
    }
    @Test public void saturatedQueueRejectsExtraWorkWithoutBlockingPing()throws Exception {
        start();invoke(1);Assert.assertTrue(started.await(2,TimeUnit.SECONDS));for(int id=2;id<=18;id++)invoke(id);
        JSONObject rejected=response();Assert.assertEquals(18,rejected.getIntValue("id"));Assert.assertNotNull(rejected.getJSONObject("error"));
        send("{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"ping\"}");Assert.assertEquals(19,response().getIntValue("id"));
    }
    @Test public void eofHasBoundedShutdown()throws Exception {
        start();invoke(1);Assert.assertTrue(started.await(2,TimeUnit.SECONDS));input.close();server.join(2000);
        Assert.assertFalse(server.isAlive());Assert.assertTrue(finished.await(2,TimeUnit.SECONDS));
    }
    @Test public void oversizedFrameIsDiscardedAndPingStillWorks() throws Exception {
        config.getRawProperties().setProperty("mcp.max_request_bytes","256");start();
        send(String.join("",Collections.nCopies(1024,"x")));
        JSONObject error=response();Assert.assertNotNull(error.getJSONObject("error"));
        send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        Assert.assertEquals(2,response().getIntValue("id"));Assert.assertEquals(0,calls.get());
    }
}
