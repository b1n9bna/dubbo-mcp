package com.mcp.dubbo.driver;
import com.alibaba.fastjson2.JSON;
import org.junit.*;
import java.util.*;
public class MethodSignaturesTest {
    @Test(expected=IllegalArgumentException.class) public void overloadNeedsExplicitTypes() {
        MethodSignatures.resolve(JSON.parseArray("[{\"name\":\"query\",\"parameterTypes\":[\"java.lang.String\"]},{\"name\":\"query\",\"parameterTypes\":[\"long\"]}]"),"query",null,1);
    }
    @Test public void explicitMapIsPreserved() { Assert.assertArrayEquals(new String[]{"java.util.Map"},MethodSignatures.resolve(null,"query",new String[]{"java.util.Map"},1)); }
    @Test(expected=IllegalArgumentException.class) public void missingArgsAreNotMatchedToNonzeroArity() { MethodSignatures.resolve(null,"query",new String[]{"long"},0); }
    @Test(expected=IllegalArgumentException.class) public void multipleProviderIdentitiesAreRejected() { DubboDirectDriver.selectService(new HashSet<>(Arrays.asList("1\tblue","2\tblue")),"com.example.Service"); }
    @Test public void groupAndVersionAreSelectedTogether() { Assert.assertArrayEquals(new String[]{"1","blue"},DubboDirectDriver.selectService(Collections.singleton("1\tblue"),"com.example.Service")); }
    @Test public void defaultGroupIsPreferredWhenUnspecified() { Assert.assertArrayEquals(new String[]{"1.0.0",""}, DubboDirectDriver.selectService(new HashSet<>(Arrays.asList("1.0.0\t", "1.0.0\tJF")), "com.example.Service")); }
    @Test public void resolveWithObjectTypeDescriptor() {
        String[] types = MethodSignatures.resolve(JSON.parseArray("[{\"name\":\"query\",\"parameterTypes\":[{\"type\":\"java.lang.String\"}]}]"), "query", null, 1);
        Assert.assertArrayEquals(new String[]{"java.lang.String"}, types);
    }
}
