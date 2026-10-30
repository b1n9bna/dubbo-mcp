package com.mcp.dubbo.driver;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;

public class DubboDirectDriverTest {

    @Test
    public void testEnhanceExceptionWithAvailableMethods() {
        Exception raw = new RuntimeException("org.apache.dubbo.rpc.RpcException: Failed to invoke. Available methods: [findOrder(java.lang.Long), findOrder(java.lang.String)]");
        Exception enhanced = DubboDirectDriver.enhanceException(raw, "com.corp.OrderService", "findOrder");
        Assert.assertTrue(enhanced instanceof IllegalArgumentException);
        Assert.assertTrue(enhanced.getMessage().contains("Dubbo method signature mismatch for com.corp.OrderService.findOrder"));
        Assert.assertTrue(enhanced.getMessage().contains("Available methods: [findOrder(java.lang.Long), findOrder(java.lang.String)]"));
        Assert.assertTrue(enhanced.getMessage().contains("Specify 'paramTypes' matching one of the available signatures"));
    }

    @Test
    public void testEnhanceExceptionWithoutMethodMismatchPassesThrough() {
        Exception raw = new RuntimeException("Connection refused to 127.0.0.1:20880");
        Exception result = DubboDirectDriver.enhanceException(raw, "com.corp.OrderService", "findOrder");
        Assert.assertSame(raw, result);
    }

    @Test
    public void testSelectServiceSingleCandidate() {
        TreeSet<String> candidates = new TreeSet<>(Collections.singletonList("1.0.0\tdefault"));
        String[] selected = DubboDirectDriver.selectService(candidates, "com.corp.OrderService");
        Assert.assertEquals("1.0.0", selected[0]);
        Assert.assertEquals("default", selected[1]);
    }

    @Test
    public void testSelectServicePrefersDefaultEmptyGroup() {
        TreeSet<String> candidates = new TreeSet<>(Arrays.asList("1.0.0\t", "1.0.0\tgray"));
        String[] selected = DubboDirectDriver.selectService(candidates, "com.corp.OrderService");
        Assert.assertEquals("1.0.0", selected[0]);
        Assert.assertEquals("", selected[1]);
    }

    @Test(expected = IllegalStateException.class)
    public void testSelectServiceEmptyThrows() {
        DubboDirectDriver.selectService(new TreeSet<>(), "com.corp.OrderService");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSelectServiceMultipleAmbiguousThrows() {
        TreeSet<String> candidates = new TreeSet<>(Arrays.asList("1.0.0\tblue", "1.0.0\tgreen"));
        DubboDirectDriver.selectService(candidates, "com.corp.OrderService");
    }
}
