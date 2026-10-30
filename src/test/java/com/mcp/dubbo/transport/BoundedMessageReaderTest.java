package com.mcp.dubbo.transport;

import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class BoundedMessageReaderTest {
    @Test public void byteLimitIncludesMultibyteUtf8() throws Exception {
        try(BoundedMessageReader reader=new BoundedMessageReader(new ByteArrayInputStream("中文\nok\n".getBytes(StandardCharsets.UTF_8)),5)) {
            Assert.assertTrue(reader.readFrame().oversized);
            Assert.assertEquals("ok",reader.readFrame().text);
            Assert.assertNull(reader.readFrame());
        }
    }
    @Test public void exactLimitAndAllLineEndingsAreSupported() throws Exception {
        try(BoundedMessageReader reader=new BoundedMessageReader(new ByteArrayInputStream("1234\r\nabc\rxy\nend".getBytes(StandardCharsets.UTF_8)),4)) {
            Assert.assertEquals("1234",reader.readFrame().text);
            Assert.assertEquals("abc",reader.readFrame().text);
            Assert.assertEquals("xy",reader.readFrame().text);
            Assert.assertEquals("end",reader.readFrame().text);
            Assert.assertNull(reader.readFrame());
        }
    }
    @Test public void oversizedEofIsReportedOnce() throws Exception {
        try(BoundedMessageReader reader=new BoundedMessageReader(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)),4)) {
            Assert.assertTrue(reader.readFrame().oversized);Assert.assertNull(reader.readFrame());
        }
    }
}
