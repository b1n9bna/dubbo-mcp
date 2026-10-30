package com.mcp.dubbo.driver;

import com.mcp.dubbo.config.ConfigManager;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.*;
import org.apache.zookeeper.data.Stat;
import org.junit.*;
import java.lang.reflect.*;
import java.net.URLEncoder;
import java.util.*;

public class RegistryNamespaceTest {
    @Test public void discoveryMetadataAndApplicationSearchUseRegistryNamespace() throws Exception {
        String[][] cases={
            {"zookeeper://127.0.0.1:2181", "/dubbo"},
            {"zookeeper://127.0.0.1:2181?group=team-a", "/team-a"},
            {"zk://127.0.0.1:2181?group=/team-a", "/team-a"},
            {"zookeeper://127.0.0.1:2181?group=/", "/"}
        };
        for(String[] scenario:cases) verify(scenario[0],scenario[1]);
    }
    private void verify(String registry, String root) throws Exception {
        String env="namespace_regression", service="com.example.InventoryService";
        String providers=root+(root.endsWith("/")?"":"/")+service+"/providers";
        String node=URLEncoder.encode("dubbo://127.0.0.1:20880/"+service+"?version=1&group=blue&application=warehouse-provider&methods=query","UTF-8");
        Map<String,List<String>> tree=new HashMap<>();
        tree.put(root,Collections.singletonList(service));tree.put(providers,Collections.singletonList(node));
        List<String> checked=new ArrayList<>();
        ExistsBuilder exists=(ExistsBuilder)Proxy.newProxyInstance(ExistsBuilder.class.getClassLoader(),new Class[]{ExistsBuilder.class},(p,m,a)->{
            if(m.getName().equals("forPath")){checked.add((String)a[0]);return tree.containsKey(a[0])?new Stat():null;}return p;
        });
        GetChildrenBuilder children=(GetChildrenBuilder)Proxy.newProxyInstance(GetChildrenBuilder.class.getClassLoader(),new Class[]{GetChildrenBuilder.class},(p,m,a)->
                m.getName().equals("forPath")?tree.get(a[0]):p);
        CuratorFramework client=(CuratorFramework)Proxy.newProxyInstance(CuratorFramework.class.getClassLoader(),new Class[]{CuratorFramework.class},(p,m,a)->{
            if(m.getName().equals("checkExists"))return exists;
            if(m.getName().equals("getChildren"))return children;
            return null;
        });
        ConfigManager config=ConfigManager.getInstance();
        config.getRawProperties().setProperty("env."+env+".registry",registry);
        DubboDirectDriver driver=null;
        try {
            driver=new DubboDirectDriver(env);
            Field field=DubboDirectDriver.class.getDeclaredField("curatorClient");field.setAccessible(true);field.set(driver,client);
            Assert.assertEquals(Collections.singletonList(service),driver.listServices(""));
            Assert.assertEquals(Collections.singletonList(service+" (app: warehouse-provider)"),driver.listServices("warehouse"));
            Map<String,Object> detail=driver.getServiceMetadata(service);
            Assert.assertEquals(1,detail.get("providerCount"));
            Map<String,Object> selected=driver.getServiceMetadata(service,"1","blue");
            Assert.assertEquals("1",selected.get("version"));Assert.assertEquals("blue",selected.get("group"));
            Assert.assertTrue(checked.contains(providers));
            if(!root.equals("/dubbo"))Assert.assertFalse(checked.contains("/dubbo/"+service+"/providers"));
        } finally {
            if(driver!=null)driver.close();config.getRawProperties().remove("env."+env+".registry");
        }
    }
}
