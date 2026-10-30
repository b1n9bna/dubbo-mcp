package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;

public class TypeCoercionTest {

    @Test
    public void testStringToNumberCoercion() {
        // String to Long
        Object longVal = TypeCoercion.coerce("202610080001", "java.lang.Long");
        Assert.assertEquals(202610080001L, longVal);
        Assert.assertEquals(202610080001L, TypeCoercion.coerce("202610080001", "long"));

        // String to Integer
        Object intVal = TypeCoercion.coerce("1001", "java.lang.Integer");
        Assert.assertEquals(1001, intVal);
        Assert.assertEquals(1001, TypeCoercion.coerce("1001", "int"));

        // String to Double
        Object doubleVal = TypeCoercion.coerce("99.95", "java.lang.Double");
        Assert.assertEquals(99.95, (Double) doubleVal, 0.0001);

        // String to BigDecimal
        Object bigDecVal = TypeCoercion.coerce("199.99", "java.math.BigDecimal");
        Assert.assertEquals(new BigDecimal("199.99"), bigDecVal);
    }

    @Test
    public void testNumberToStringCoercion() {
        // Number to String
        Object strVal = TypeCoercion.coerce(202610080001L, "java.lang.String");
        Assert.assertEquals("202610080001", strVal);

        Object intToStr = TypeCoercion.coerce(1001, "java.lang.String");
        Assert.assertEquals("1001", intToStr);
    }

    @Test
    public void testBooleanCoercion() {
        Assert.assertEquals(Boolean.TRUE, TypeCoercion.coerce("true", "boolean"));
        Assert.assertEquals(Boolean.FALSE, TypeCoercion.coerce("false", "java.lang.Boolean"));
        Assert.assertEquals("true", TypeCoercion.coerce(true, "java.lang.String"));
    }

    @Test
    public void testStringJsonToDtoMapCoercion() {
        Object dtoVal = TypeCoercion.coerce("{\"orderNo\":\"202610080001\",\"tenantId\":1001}", "cn.corp.oms.OrderQueryDTO");
        Assert.assertTrue(dtoVal instanceof JSONObject);
        JSONObject obj = (JSONObject) dtoVal;
        Assert.assertEquals("202610080001", obj.getString("orderNo"));
        Assert.assertEquals(1001, obj.getIntValue("tenantId"));
    }
}
