package com.mcp.dubbo.driver;

import com.mcp.dubbo.config.ConfigManager;
import org.apache.dubbo.config.*;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.zookeeper.server.*;
import org.junit.*;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Exercises the actual Curator/ZooKeeper/Dubbo/Hessian path against a disposable local server. */
public class LocalDubboCompatibilityTest {
    @Test(timeout=30000) public void discoversAndInvokesGenericServiceWithoutJackson() throws Exception {
        Path directory=Files.createTempDirectory("dubbo-dependency-test");
        String env="local_dependency_regression";
        ConfigManager config=ConfigManager.getInstance();
        String[] keys={"env."+env+".registry","env."+env+".timeout"};
        String[] previous={config.getProperty(keys[0]),config.getProperty(keys[1])};
        ServerCnxnFactory factory=null;ZooKeeperServer server=null;
        ServiceConfig<GenericService> provider=null;DubboDirectDriver driver=null;
        try {
            server=new ZooKeeperServer(directory.toFile(),directory.toFile(),2000);
            factory=ServerCnxnFactory.createFactory(new InetSocketAddress("127.0.0.1",0),16);
            factory.startup(server);
            String registry="zookeeper://127.0.0.1:"+factory.getLocalPort()+"?group=dependency-regression";
            config.getRawProperties().setProperty(keys[0],registry);
            config.getRawProperties().setProperty(keys[1],"5000");
            ApplicationConfig application=new ApplicationConfig("dependency-regression-provider");application.setQosEnable(false);
            provider=new ServiceConfig<>();provider.setApplication(application);
            provider.setScope("remote"); // Force Netty/Hessian rather than the same-JVM shortcut.
            provider.setRegistry(new RegistryConfig(registry));
            // Dubbo treats hosts starting with "127." as unspecified and binds all interfaces.
            // Java's single-number IPv4 notation keeps this test strictly on loopback.
            String loopback="2130706433";
            Assert.assertTrue(InetAddress.getByName(loopback).isLoopbackAddress());
            int port;
            try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getByName(loopback))){port=reservation.getLocalPort();}
            ProtocolConfig protocol=new ProtocolConfig("dubbo",port);protocol.setHost(loopback);provider.setProtocol(protocol);
            provider.setInterface("com.example.DependencyProbe");provider.setVersion("1.0.0");
            provider.setRef((method,types,args)->Collections.singletonMap("echo",args[0]));
            provider.export();
            driver=new DubboDirectDriver(env);
            Assert.assertFalse(driver.listServices("DependencyProbe").isEmpty());
            Object result=driver.invoke("com.example.DependencyProbe","query",new String[]{"java.lang.String"},new Object[]{"loopback-only"},"1.0.0","");
            Assert.assertTrue(result instanceof Map);Assert.assertEquals("loopback-only",((Map<?,?>)result).get("echo"));
            try {Class.forName("com.fasterxml.jackson.databind.ObjectMapper");Assert.fail("Jackson should not be on the runtime classpath");}
            catch(ClassNotFoundException expected) { }
        } finally {
            if(driver!=null)driver.close();if(provider!=null)provider.unexport();
            if(factory!=null)factory.shutdown();if(server!=null)server.shutdown();
            for(int i=0;i<keys.length;i++)if(previous[i]==null)config.getRawProperties().remove(keys[i]);else config.getRawProperties().setProperty(keys[i],previous[i]);
            try(Stream<Path> paths=Files.walk(directory)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
            }
        }
    }
}
