package com.mcp.dubbo.driver;

import com.alibaba.fastjson2.*;
import org.junit.*;

public class ServicePageTest {
    private ServicePage page(String json, int count) {
        Object response=JSON.parse(json);
        return ServicePage.inspect(response,DubboAdminDriver.toServiceArray(response),count);
    }
    @Test public void pageHelperUsesOneBasedPageNum() {
        ServicePage first=page("{\"data\":{\"list\":[\"a\"],\"pages\":2,\"pageNum\":1}}",1);
        Assert.assertTrue(first.more); Assert.assertEquals("pageNum",first.parameter); Assert.assertEquals(1,first.current);
        Assert.assertFalse(page("{\"list\":[\"b\"],\"pages\":2,\"pageNum\":2}",2).more);
    }
    @Test public void stringEncodedPaginationIsNotMistakenForCompleteList() {
        String json="{\"content\":[\"a\"],\"number\":0,\"totalPages\":2}";
        ServicePage first=ServicePage.inspect(json,DubboAdminDriver.toServiceArray(json),1);
        Assert.assertTrue(first.more); Assert.assertEquals("page",first.parameter);
    }
    @Test(expected=IllegalStateException.class) public void conflictingMetadataIsRejected() {
        page("{\"content\":[\"a\"],\"number\":0,\"totalPages\":2,\"last\":true}",1);
    }
    @Test(expected=IllegalStateException.class) public void truncatedEnvelopeWithoutCursorIsRejected() {
        page("{\"content\":[\"a\"],\"totalElements\":2}",1);
    }
    @Test(expected=IllegalStateException.class) public void negativePageCountIsRejected() {
        page("{\"content\":[\"a\"],\"number\":0,\"totalPages\":-1}",1);
    }
}
